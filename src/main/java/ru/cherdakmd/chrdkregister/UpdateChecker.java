package ru.cherdakmd.chrdkregister;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Фоновая проверка обновлений ядра через GitHub Releases API.
 *
 * <p>Запрос выполняется асинхронно (игровой поток никогда не блокируется) и читает маленький
 * JSON ответа. Найденной новинке — три канала: предупреждение в консоль, событие {@code update.available}
 * в Discord-вебхук (если оно есть в {@code notifications.events}) и сообщение администраторам
 * (право {@code chrdkregister.admin}) — сразу при обнаружении и при входе на сервер.</p>
 *
 * <p>Вся сетевая часть необязательна и безопасно отключается ({@code updates.enabled: false});
 * любые сетевые ошибки глушатся на уровне FINE и никогда не влияют на работу плагина.</p>
 */
public final class UpdateChecker implements Listener {

    /** Право, которому показываются уведомления о новой версии. */
    private static final String ADMIN_PERMISSION = "chrdkregister.admin";
    /** Задержка первой проверки после старта (даём серверу подняться), тики. */
    private static final long FIRST_CHECK_DELAY_TICKS = 20L * 60L;
    /** Пауза перед сообщением администратору после входа, чтобы не тонуло в приветствиях, тики. */
    private static final long JOIN_NOTIFY_DELAY_TICKS = 60L;

    private final RegisterPlugin plugin;
    private final AccessManager accessManager;
    private final Logger logger;
    private final HttpClient http;

    private volatile BukkitTask task;
    private volatile String latestVersion = "";
    private volatile String latestUrl = "";
    private volatile boolean updateAvailable;

    public UpdateChecker(RegisterPlugin plugin, AccessManager accessManager, Logger logger) {
        this.plugin = plugin;
        this.accessManager = accessManager;
        this.logger = logger;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Запускает периодическую проверку, если она включена в конфиге. */
    public void start() {
        PluginConfig config = accessManager.config();
        if (!config.updatesEnabled()) {
            return;
        }
        long intervalHours = Math.max(1, config.updatesIntervalHours());
        long periodTicks = 20L * 3600L * intervalHours;
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::checkSafely, FIRST_CHECK_DELAY_TICKS, periodTicks);
    }

    /** Останавливает фоновую задачу (вызывается из onDisable). */
    public void shutdown() {
        BukkitTask running = this.task;
        if (running != null) {
            running.cancel();
            this.task = null;
        }
    }

    /** true, если известна более новая версия, чем установленная. */
    public boolean updateAvailable() {
        return updateAvailable;
    }

    private void checkSafely() {
        try {
            checkNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            logger.log(Level.FINE, "Проверка обновлений не удалась", exception);
        }
    }

    private void checkNow() throws Exception {
        PluginConfig config = accessManager.config();
        if (!config.updatesEnabled()) {
            return;
        }
        String repo = config.updatesRepo();
        if (repo == null || repo.isBlank()) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/" + repo.trim() + "/releases/latest"))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "CHRDK_Core-update-checker")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            logger.log(Level.FINE, "GitHub Releases вернул HTTP {0} — проверка обновлений пропущена",
                    response.statusCode());
            return;
        }
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        String remote = json.has("tag_name") && !json.get("tag_name").isJsonNull()
                ? json.get("tag_name").getAsString() : "";
        String url = json.has("html_url") && !json.get("html_url").isJsonNull()
                ? json.get("html_url").getAsString() : "";
        String current = currentVersion();
        String latest = normalizeVersion(remote);
        if (latest.isEmpty() || compareVersions(latest, current) <= 0) {
            return; // сидим на свежей (или новее опубликованной — сборка из исходников)
        }
        boolean firstDetection = !updateAvailable;
        this.latestVersion = latest;
        this.latestUrl = url;
        this.updateAvailable = true;
        logger.warning("[updates] Доступна новая версия CHRDK_Core v" + latest
                + " (у вас v" + current + "): " + url);
        if (firstDetection) {
            notifyDiscord(latest, current, url);
        }
        notifyOnlineAdmins();
    }

    /** Сообщение в Discord-вебхук; срабатывает один раз на версию и только если событие включено. */
    private void notifyDiscord(String latest, String current, String url) {
        Messages messages = plugin.messages();
        if (messages == null) {
            return;
        }
        accessManager.notifications().notify("update.available",
                messages.raw("update.notify-title"),
                messages.raw("update.notify-desc")
                        .replace("%latest%", latest)
                        .replace("%current%", current)
                        .replace("%url%", url),
                "info");
    }

    /** Рассылка онлайн-администраторам из асинхронного контекста (по образцу AccessManager). */
    private void notifyOnlineAdmins() {
        if (!accessManager.config().updatesNotifyAdmins()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.hasPermission(ADMIN_PERMISSION)) {
                    sendAdminNotice(player);
                }
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!updateAvailable || !accessManager.config().updatesNotifyAdmins()) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                sendAdminNotice(player);
            }
        }, JOIN_NOTIFY_DELAY_TICKS);
    }

    private void sendAdminNotice(Player player) {
        Messages messages = plugin.messages();
        if (messages == null) {
            return;
        }
        messages.send(player, "update.admin-join",
                "%latest%", latestVersion,
                "%current%", currentVersion(),
                "%url%", latestUrl);
    }

    private String currentVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    /**
     * Приводит тег релиза к сравнимой версии: {@code "v1.4.66"} → {@code "1.4.66"},
     * суффиксы вроде {@code -rc1} отбрасываются. Пустая/битая строка → {@code ""}.
     */
    public static String normalizeVersion(String tag) {
        if (tag == null) {
            return "";
        }
        String value = tag.trim();
        if (value.startsWith("v") || value.startsWith("V")) {
            value = value.substring(1);
        }
        StringBuilder digits = new StringBuilder(value.length());
        for (char symbol : value.toCharArray()) {
            if (Character.isDigit(symbol) || symbol == '.') {
                digits.append(symbol);
            } else {
                break; // суффикс (-rc1, -beta, +meta) дальше не нужен
            }
        }
        String normalized = digits.toString();
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * Сравнивает версии вида {@code 1.4.65} почленно (недостающие компоненты = 0):
     * отрицательное — left старее, 0 — равны, положительное — left новее.
     */
    public static int compareVersions(String left, String right) {
        String[] leftParts = normalizeVersion(left).split("\\.");
        String[] rightParts = normalizeVersion(right).split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            int leftValue = index < leftParts.length ? parseComponent(leftParts[index]) : 0;
            int rightValue = index < rightParts.length ? parseComponent(rightParts[index]) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    private static int parseComponent(String component) {
        if (component == null || component.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(component);
        } catch (NumberFormatException exception) {
            return 0; // неожиданные символы внутри — считаем нулём, не роняем проверку
        }
    }
}
