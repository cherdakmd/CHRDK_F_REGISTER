package ru.cherdakmd.chrdksocial;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkvk.VkPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Единая точка обязательной привязки Minecraft ↔ ВК/MAX внутри одного плагина.
 *
 * <p>Игрока впускают на сервер, но до подтверждения кода ботом он заморожен:
 * запрещены движение, чат, инвентарь и команды вне whitelist'а. Код гасится
 * ровно один раз и принимается из любого из двух мессенджеров; после успешной
 * записи ограничения снимаются без релога. Staff и переходный период
 * ({@code grace-days}) не замораживаются, но видят приглашение привязаться.</p>
 */
public final class SocialService {

    public static final String PROVIDER_VK = "vk";
    public static final String PROVIDER_MAX = "max";

    private final VkPlugin plugin;
    private volatile SocialConfig config;
    private final SocialLinkStore store;
    private final SocialLinkCodeManager codes = new SocialLinkCodeManager();
    private final SocialAudit audit;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final ExecutorService newsExecutor;
    private final HttpClient newsClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private volatile MaxService max;
    private BukkitTask reminderTask;
    private BukkitTask newsTask;

    public SocialService(VkPlugin plugin, SocialConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.store = new SocialLinkStore(plugin.getDataFolder().toPath(), plugin.getLogger());
        this.audit = new SocialAudit(plugin.getDataFolder().toPath(), plugin.getLogger(),
                java.time.ZoneId.of("Europe/Moscow"));
        this.newsExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "CHRDK-Social-News");
            thread.setDaemon(true);
            return thread;
        });
        store.load();
        if (store.loadFailed() && config.failOpenOnCorruption()) {
            long until = System.currentTimeMillis() + config.emergencyMinutes() * 60_000L;
            store.acknowledgeCorruption(until);
            audit.write("social.storage-corruption", "", "",
                    "fail-open emergency до " + until);
        }
    }

    public void attachMax(MaxService service) {
        this.max = service;
    }

    public MaxService max() {
        return max;
    }

    public SocialConfig config() {
        return config;
    }

    public org.bukkit.plugin.Plugin plugin() {
        return plugin;
    }

    public void sendFrozenNotice(Player player, String code) {
        if (player == null || !player.isOnline()) return;
        plugin.messages().send(player, "social.command-blocked", "code", code);
    }

    public SocialLinkStore store() {
        return store;
    }

    public SocialLinkCodeManager codes() {
        return codes;
    }

    public void clearFailures(String senderKey) {
        codes.clearFailures(senderKey);
    }

    public SocialAudit audit() {
        return audit;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(new SocialGateListener(this), plugin);
        SocialCommandHandler commands = new SocialCommandHandler(this, plugin);
        plugin.registerSocialCommands(commands, commands);
        registerCoreBridges();
        long period = 20L * Math.max(10, config.reminderSeconds());
        reminderTask = Bukkit.getScheduler().runTaskTimer(plugin, this::remindPending, period, period);
        long newsPeriod = 20L * 60L * Math.max(5, config.newsPollMinutes());
        newsTask = Bukkit.getScheduler().runTaskTimer(plugin, this::scheduleNewsCheck,
                20L * 30L, newsPeriod);
        plugin.getLogger().info("[CHRDK_SOCIAL] Обязательная привязка "
                + (config.mandatoryActive() ? "включена" : "выключена")
                + "; привязок: " + store.linkCount()
                + "; grace: " + config.graceDays() + " дн.");
    }

    public void applyConfig(SocialConfig next) {
        this.config = next;
    }

    public void shutdown() {
        if (reminderTask != null) reminderTask.cancel();
        if (newsTask != null) newsTask.cancel();
        newsExecutor.shutdownNow();
        store.saveIfDirty();
    }

    /* ------------------------------ политика входа ------------------------------ */

    public boolean staffBypass(Player player) {
        if (player == null) return false;
        String permission = config.bypassPermission();
        return player.isOp() || (permission != null && !permission.isBlank()
                && player.hasPermission(permission));
    }

    public boolean emergencyActive(long now) {
        return now < store.emergencyUntil();
    }

    public boolean graceActive(long now) {
        long deadline = store.rolloutStartedAt() + config.graceMillis();
        return now < deadline;
    }

    /** Заморозка действует только после grace и только для не-привязанных не-staff игроков. */
    public boolean isFrozen(Player player) {
        if (player == null || !config.mandatoryActive()) return false;
        if (staffBypass(player)) return false;
        if (store.loadFailed()) return !config.failOpenOnCorruption();
        long now = System.currentTimeMillis();
        if (emergencyActive(now) || graceActive(now)) return false;
        return !store.isLinked(player.getName());
    }

    public boolean needsInvitation(Player player) {
        if (player == null || !config.enabled() || store.isLinked(player.getName())) return false;
        return staffBypass(player) || graceActive(System.currentTimeMillis())
                || emergencyActive(System.currentTimeMillis()) || isFrozen(player);
    }

    public void onJoin(Player player) {
        if (!config.enabled()) return;
        String key = SocialLinkCodeManager.canonical(player.getName());
        if (!store.isLinked(player.getName()) && !staffBypass(player)) {
            pending.add(key);
            String code = issueCode(player);
            plugin.messages().send(player, "social.gate-header");
            plugin.messages().send(player, "social.gate-code",
                    "code", code, "minutes", Math.max(1, config.codeTtlSeconds() / 60),
                    "vk", config.vkCommunityUrl(), "max", config.maxBotUrl());
            plugin.messages().send(player, "social.gate-howto");
            if (!isFrozen(player)) plugin.messages().send(player, "social.gate-grace");
            audit.write("social.join-pending", player.getName(), "", "выдан код входа");
        } else {
            pending.remove(key);
        }
    }

    public void onQuit(Player player) {
        pending.remove(SocialLinkCodeManager.canonical(player.getName()));
    }

    /** Возвращает живой код игрока, перевыпуская только по истечении паузы. */
    public String issueCode(Player player) {
        long now = System.currentTimeMillis();
        SocialLinkCodeManager.IssueResult result = codes.issue(player.getName(),
                config.codeLength(), config.codeTtlSeconds(), config.codeCooldownSeconds(), now);
        if (result.status() == SocialLinkCodeManager.Status.COOLDOWN) {
            plugin.messages().send(player, "social.code-cooldown",
                    "seconds", result.remainingCooldownSeconds());
        }
        audit.write("social.code-issued", player.getName(), "", result.status().name());
        return result.code();
    }

    private void remindPending() {
        if (!config.enabled()) return;
        long now = System.currentTimeMillis();
        for (String key : pending) {
            Player player = Bukkit.getPlayerExact(key);
            if (player == null || !player.isOnline()) continue;
            if (store.isLinked(player.getName())) {
                pending.remove(key);
                continue;
            }
            Optional<String> code = codes.peek(player.getName(), now);
            if (code.isEmpty()) {
                code = Optional.of(issueCode(player));
            }
            if (isFrozen(player)) {
                plugin.messages().send(player, "social.reminder", "code", code.get());
            }
        }
    }

    /* ------------------------- подтверждение кода ботом ------------------------- */

    /**
     * @return текст ответа боту, или {@code null}, если код не принадлежит
     *         обязательной привязке (тогда ВК обрабатывает его как старый /vk link).
     */
    public String handleIncomingCode(String rawCode, String provider, long externalId,
                                     String externalName, String senderKey, long now,
                                     boolean fromMax) {
        if (!config.enabled()) return "Привязка временно отключена на сервере.";
        String safeProvider = PROVIDER_MAX.equals(provider) ? PROVIDER_MAX : PROVIDER_VK;
        SocialLinkCodeManager.VerifyResult verified = codes.verify(rawCode, senderKey,
                config.maxAttempts(), 60L, now);
        if (verified.status() == SocialLinkCodeManager.Verify.LOCKED) {
            audit.write("social.code-locked", "", safeProvider, senderKey);
            return "🔒 Слишком много неудачных попыток. Повторите через "
                    + verified.lockRemainingSeconds() + " сек.";
        }
        if (verified.status() == SocialLinkCodeManager.Verify.NOT_FOUND) {
            return fromMax ? "❌ Код не найден или истёк. Запросите новый код в игре: /link" : null;
        }
        String playerName = verified.playerName();
        if (store.isLinked(playerName)) {
            codes.consume(rawCode);
            return "✅ Аккаунт " + playerName + " уже привязан.";
        }
        Optional<String> owner = store.ownerOf(safeProvider, externalId);
        if (owner.isPresent() && !owner.get().equals(SocialLinkCodeManager.canonical(playerName))) {
            audit.write("social.link-rejected", playerName, safeProvider, "профиль занят другим игроком");
            return "❌ Этот профиль уже привязан к другому игровому аккаунту. Обратитесь к администрации.";
        }
        SocialLinkStore.LinkStatus status = store.link(playerName, safeProvider, externalId,
                externalName, config.maxAccountsPerProfile(), now);
        if (status != SocialLinkStore.LinkStatus.LINKED) {
            audit.write("social.link-rejected", playerName, safeProvider, status.name());
            return "❌ Не удалось сохранить привязку (" + status.name() + "). Код ещё действителен.";
        }
        codes.consume(rawCode);
        pending.remove(SocialLinkCodeManager.canonical(playerName));
        audit.write("social.link", playerName, safeProvider,
                "external=" + externalId + ", sender=" + senderKey);
        if (PROVIDER_VK.equals(safeProvider)) mirrorVkLink(playerName, externalId, now);
        notifyLinked(playerName, safeProvider, externalId);
        return "✅ Аккаунт " + playerName + " привязан к "
                + (PROVIDER_VK.equals(safeProvider) ? "ВКонтакте" : "MAX")
                + ". Можно играть — ограничения сняты автоматически, перезаходить не нужно.";
    }

    /** Привязка через ВК дублируется в исторический VkStore для бонусов, /vk и уведомлений. */
    private void mirrorVkLink(String playerName, long vkUserId, long now) {
        try {
            var vk = plugin.vkService();
            if (vk == null) return;
            var store = vk.store();
            if (store.byPlayer(playerName).isEmpty()) {
                store.link(playerName, vkUserId, 1, now);
                store.saveNow();
            }
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.FINE, "[CHRDK_SOCIAL] Не удалось зеркалировать VK-привязку", failure);
        }
    }

    private void notifyLinked(String playerName, String provider, long externalId) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayerExact(playerName);
            if (player == null || !player.isOnline()) return;
            plugin.messages().send(player, "social.linked",
                    "provider", PROVIDER_VK.equals(provider) ? "ВКонтакте" : "MAX");
            int reward = config.rewardXpLevels();
            if (reward > 0 && !store.rewardClaimed(playerName)) {
                player.giveExpLevels(reward);
                store.markRewarded(playerName, System.currentTimeMillis());
                plugin.messages().send(player, "social.reward", "levels", reward);
                audit.write("social.reward", playerName, provider, "+" + reward + " уровней");
            }
        });
    }

    /* ------------------------------ команды/статус ------------------------------ */

    public String status(String playerName) {
        Optional<SocialLinkStore.Binding> binding = store.binding(playerName);
        String state = binding.map(value -> value.provider() + " #" + value.externalId())
                .orElse("не привязан");
        long now = System.currentTimeMillis();
        return "Привязка: " + state
                + " | заморозка: " + (isFrozen(Bukkit.getPlayerExact(playerName)) ? "да" : "нет")
                + " | grace: " + (graceActive(now) ? "активен" : "истёк")
                + " | аварийный режим до: " + store.emergencyUntil()
                + " | всего привязок: " + store.linkCount();
    }

    public String unlink(String playerName, String actor) {
        Optional<SocialLinkStore.Binding> removed = store.unlink(playerName);
        if (removed.isEmpty()) return "У игрока " + playerName + " нет привязки.";
        audit.write("social.unlink", playerName, removed.get().provider(), "actor=" + actor);
        Player player = Bukkit.getPlayerExact(playerName);
        if (player != null && player.isOnline()) {
            pending.add(SocialLinkCodeManager.canonical(playerName));
            String code = issueCode(player);
            plugin.messages().send(player, "social.unlinked", "code", code);
        }
        return "Привязка " + playerName + " снята. Игроку выдан новый код входа.";
    }

    public String emergency(int minutes, String actor) {
        long until = minutes <= 0 ? 0L
                : System.currentTimeMillis() + minutes * 60_000L;
        store.setEmergencyUntil(until);
        audit.write("social.emergency", "", "", "actor=" + actor + ", until=" + until);
        return minutes <= 0 ? "Аварийный режим выключен." : "Аварийный режим до " + until + ".";
    }

    public String setChat(String kind, long chatId, String actor) {
        store.setChatId(kind, chatId);
        store.recordChat(Long.toString(chatId), System.currentTimeMillis());
        audit.write("social.max-chat", "", PROVIDER_MAX,
                "actor=" + actor + ", kind=" + kind + ", chat=" + chatId);
        return "MAX chat_id (" + kind + ") = " + chatId;
    }

    public List<String> discoveredChats() {
        return store.discoveredChats();
    }

    /* ------------------------------- мосты ядра ------------------------------- */

    private void registerCoreBridges() {
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) return;
        api.registerProfile("social", new ChrdkRegisterApi.ProfileProvider() {
            @Override
            public int order() {
                return 95;
            }

            @Override
            public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
                Optional<SocialLinkStore.Binding> binding = store.binding(context.playerName());
                String line = binding.map(value -> "<gray>Мессенджер: <white>"
                                + (PROVIDER_VK.equals(value.provider()) ? "ВКонтакте" : "MAX")
                                + "</white> · id <white>" + value.externalId() + "</white></gray>")
                        .orElse("<yellow>Мессенджер не привязан</yellow>");
                return new ChrdkRegisterApi.ProfileSection(
                        "<aqua>Вход через мессенджер</aqua>", Material.NAME_TAG,
                        List.of(Component.text(line.replaceAll("<[^>]+>", ""))));
            }
        });
        api.registerSecurityListener("social-max", new ChrdkRegisterApi.SecurityListener() {
            @Override
            public void onLoginSuccess(String playerName, String maskedIp, String method) {
                sendMaxAlert(playerName, "✅ Вход в аккаунт " + playerName + " (" + method + ")");
            }

            @Override
            public void onBruteForceAlert(String playerName, String maskedIp, int failures, boolean blocked) {
                sendMaxAlert(playerName, "⚠️ Подбор пароля для " + playerName
                        + ": " + failures + " неудач" + (blocked ? ", вход заблокирован" : ""));
            }
        });
        api.registerGlobalChatListener("social-max", (playerName, plainMessage) -> {
            MaxService service = max;
            if (service == null || !config.bridgeEnabled() || !config.bridgeMcToMax()) return;
            String line = config.bridgeFormat()
                    .replace("{player}", playerName)
                    .replace("{message}", plainMessage);
            service.sendToBridge(line);
        });
    }

    public void sendMaxAlert(String playerName, String text) {
        MaxService service = max;
        if (service == null) return;
        store.binding(playerName).ifPresent(binding -> {
            if (PROVIDER_MAX.equals(binding.provider())) {
                service.sendToUser(binding.externalId(), text);
            }
        });
    }

    /* --------------------------- новости релиза в плагине --------------------------- */

    private void scheduleNewsCheck() {
        if (!config.newsEnabled()) return;
        newsExecutor.execute(this::checkRelease);
    }

    /** Сетевая проверка GitHub Releases вне игрового потока; идемпотентна по lastNewsTag. */
    public void checkRelease() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.releaseApiUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "CHRDK_SOCIAL/1.0")
                    .GET().build();
            HttpResponse<String> response = newsClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) return;
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            ReleaseAnnouncement.Release release = ReleaseAnnouncement.parseLatest(json);
            if (release == null) return;
            synchronized (this) {
                if (release.tag().equals(store.lastNewsTag())) return;
                store.setLastNewsTag(release.tag());
            }
            String text = ReleaseAnnouncement.format(config.newsTemplate(), release);
            audit.write("social.news", "", "", release.tag());
            MaxService service = max;
            if (service != null) {
                long chat = store.chatId("news");
                if (chat > 0L) service.sendToChat(chat, text);
            }
            var vk = plugin.vkService();
            if (vk != null) vk.sendToGroupChat(text);
            if (config.newsInGame()) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> Bukkit.broadcastMessage("§b[CHERDAK] §f" + text));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            plugin.getLogger().fine("[CHRDK_SOCIAL] Проверка новостей отложена: "
                    + failure.getClass().getSimpleName());
        }
    }

    /** Текст для отчётов и тестов: какие команды разрешены в замороженном лобби. */
    public static List<String> allowedCommands() {
        return List.of("link", "social", "socialadmin", "max", "vk", "vkadmin", "maxadmin",
                "register", "reg", "login", "l", "pass", "fregister", "prohodka",
                "logout", "session", "captcha", "2fa", "totp", "twofactor", "help", "?");
    }

    public static String providerLabel(String provider) {
        return PROVIDER_VK.equals(provider) ? "ВКонтакте"
                : PROVIDER_MAX.equals(provider) ? "MAX" : provider == null ? "" : provider;
    }

    public static String normalizeProvider(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
