package ru.cherdakmd.chrdksocial;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkvk.VkPlugin;
import ru.cherdakmd.chrdkvk.VkStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
    private static final long DONOR_CACHE_MILLIS = 60_000L;
    private static final DateTimeFormatter DEADLINE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    private record DonorCheck(boolean allowed, long checkedAt) { }

    private final VkPlugin plugin;
    private volatile SocialConfig config;
    private final SocialLinkStore store;
    private final SocialLinkCodeManager codes = new SocialLinkCodeManager();
    private final SocialAudit audit;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    /** Проверка покупки дёргает ядро; isFrozen вызывается на каждый PlayerMoveEvent. */
    private final ConcurrentHashMap<String, DonorCheck> donorCache = new ConcurrentHashMap<>();
    private final ExecutorService newsExecutor;
    private final HttpClient newsClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private volatile MaxService max;
    private BukkitTask reminderTask;
    private BukkitTask actionBarTask;
    private BukkitTask newsTask;
    private volatile boolean running;
    /**
     * Общий кулдаун ЛЮБОГО напоминания в чат. Раньше периодическое сообщение и
     * пинг при действиях жили каждый со своим таймером и приходили друг за другом
     * («НАПОМИНАНИЕ» + «Не забудь выбрать» подряд). Теперь оба источника делят
     * одну паузу, поэтому игрок видит не чаще одного напоминания за интервал.
     * Запись НЕ удаляется при выходе: перезаход в течение часа не обнуляет паузу.
     */
    private final ConcurrentHashMap<String, Long> lastChatReminder = new ConcurrentHashMap<>();
    /**
     * Отдельная пауза для короткого «действие доступно после привязки»: оно
     * отвечает на отменённый клик/команду замороженного игрока, поэтому не должно
     * приходить на каждое движение, но и не обязано съедать общий кулдаун чата.
     */
    private final ConcurrentHashMap<String, Long> lastFrozenNotice = new ConcurrentHashMap<>();

    public SocialService(VkPlugin plugin, SocialConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.store = new SocialLinkStore(ru.cherdakmd.chrdkregister.storage.ChrdkStoragePaths.dataFolder(plugin), plugin.getLogger());
        this.audit = new SocialAudit(ru.cherdakmd.chrdkregister.storage.ChrdkStoragePaths.dataFolder(plugin), plugin.getLogger(),
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
        String key = SocialLinkCodeManager.canonical(player.getName());
        long now = System.currentTimeMillis();
        long pause = config.reminderSeconds() * 1000L;
        Long last = lastFrozenNotice.get(key);
        if (last != null && now - last < pause) return;
        lastFrozenNotice.put(key, now);
        if (lastFrozenNotice.size() > 4096) {
            lastFrozenNotice.entrySet().removeIf(entry -> now - entry.getValue() > pause * 4L);
        }
        plugin.messages().send(player, "social.command-blocked",
                "code", code, "url", config.donateUrl(), "vk", config.vkCommunityUrl());
    }

    public SocialLinkStore store() {
        return store;
    }

    /**
     * Один раз на старте переносит исторические связи из vk-links.json в
     * players/social.yml. Сам vk-links.json остаётся источником бонусов /vk.
     */
    public int importLegacyVkLinks() {
        var vk = plugin.vkService();
        if (vk == null || store.loadFailed() || store.playersFrozen()) return 0;
        List<SocialLinkStore.LegacyVkLink> links = legacyVkLinks(vk.store());
        int imported = store.importLegacyVkLinks(links, config.maxAccountsPerProfile(),
                System.currentTimeMillis());
        if (imported > 0) {
            audit.write("social.legacy-vk-import", "", PROVIDER_VK,
                    "импортировано=" + imported + ", исходных=" + links.size());
        }
        return imported;
    }

    /** Канонический снимок всех legacy-связей: порядок vk-links.json сохраняется. */
    private static List<SocialLinkStore.LegacyVkLink> legacyVkLinks(VkStore vkStore) {
        if (vkStore == null) return List.of();
        List<SocialLinkStore.LegacyVkLink> links = new ArrayList<>();
        for (var snapshot : vkStore.allLinks()) {
            if (snapshot == null || snapshot.vkUserId() <= 0L) continue;
            String name = snapshot.canonicalName();
            if (name == null || name.isBlank()) name = snapshot.playerName();
            links.add(new SocialLinkStore.LegacyVkLink(name, snapshot.vkUserId(), snapshot.linkedAt()));
        }
        return links;
    }

    /** Связь как она есть в vk-links.json, без проверок лимитов и коллизий YAML. */
    private Optional<VkStore.LinkSnapshot> rawLegacyVkBinding(String playerName) {
        if (playerName == null || playerName.isBlank()) return Optional.empty();
        try {
            var vk = plugin.vkService();
            return vk == null ? Optional.empty() : vk.store().byPlayer(playerName);
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.FINE,
                    "[CHRDK_SOCIAL] Не удалось прочитать историческую VK-привязку", failure);
            return Optional.empty();
        }
    }

    /**
     * Read-only запасной источник: старые привязки видны даже до импорта,
     * но только если они не конфликтуют с YAML и укладываются в лимит профиля.
     */
    public Optional<VkStore.LinkSnapshot> legacyVkBinding(String playerName) {
        var raw = rawLegacyVkBinding(playerName);
        if (raw.isEmpty() || store.isLinked(playerName)) return Optional.empty();
        var vk = plugin.vkService();
        if (vk == null) return Optional.empty();
        try {
            if (!store.legacyVkBindingAllowed(playerName, raw.get().vkUserId(),
                    legacyVkLinks(vk.store()), config.maxAccountsPerProfile())) return Optional.empty();
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.FINE,
                    "[CHRDK_SOCIAL] Не удалось сверить историческую VK-привязку", failure);
            return Optional.empty();
        }
        return raw;
    }

    /** Один общий статус для TAB, gating и команд: YAML + legacy vk-links.json. */
    public boolean isLinked(String playerName) {
        return store.isLinked(playerName) || legacyVkBinding(playerName).isPresent();
    }

    /** Сколько привязок видно игре: импорт YAML + ещё не перенесённый legacy. */
    public int linkCount() {
        int count = store.linkCount();
        var vk = plugin.vkService();
        if (vk == null) return count;
        try {
            List<SocialLinkStore.LegacyVkLink> links = legacyVkLinks(vk.store());
            for (SocialLinkStore.LegacyVkLink link : links) {
                if (link == null || store.isLinked(link.playerName())) continue;
                if (store.legacyVkBindingAllowed(link.playerName(), link.vkUserId(),
                        links, config.maxAccountsPerProfile())) count++;
            }
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.FINE,
                    "[CHRDK_SOCIAL] Не удалось посчитать legacy VK-привязки", failure);
        }
        return count;
    }

    /** /vk unlink синхронно снимает импортированную VK-привязку из YAML. */
    public boolean forgetVkLink(String playerName, long vkUserId) {
        try {
            boolean ok = store.forgetVkBinding(playerName, vkUserId);
            if (ok) {
                audit.write("social.vk-unlink-sync", playerName, PROVIDER_VK, "id=" + vkUserId);
            } else {
                plugin.getLogger().warning("[CHRDK_SOCIAL] Не удалось снять импортированную "
                        + "VK-привязку из players/social.yml; проверьте backup и права на запись.");
            }
            return ok;
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING,
                    "[CHRDK_SOCIAL] Сбой синхронизации /vk unlink", failure);
            return false;
        }
    }

    /** /social unlink не должен оставлять read-only fallback в vk-links.json. */
    private boolean forgetLegacyVkBinding(String playerName) {
        try {
            var vk = plugin.vkService();
            if (vk == null || vk.store().unlink(playerName).isEmpty()) return false;
            boolean saved = vk.store().saveNow();
            if (!saved) {
                plugin.getLogger().warning("[CHRDK_SOCIAL] Не удалось сохранить vk-links.json "
                        + "после /social unlink; привязка может вернуться при рестарте.");
            }
            return saved;
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING,
                    "[CHRDK_SOCIAL] Сбой записи vk-links.json после /social unlink", failure);
            return false;
        }
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
        running = true;
        restartReminderTimers();
        long newsPeriod = 20L * 60L * Math.max(5, config.newsPollMinutes());
        newsTask = Bukkit.getScheduler().runTaskTimer(plugin, this::scheduleNewsCheck,
                20L * 30L, newsPeriod);
        plugin.getLogger().info("[CHRDK_SOCIAL] Обязательная привязка "
                + (config.mandatoryActive() ? "включена" : "выключена")
                + "; привязок: " + linkCount()
                + "; grace: " + config.graceDays() + " дн. общий + "
                + config.newcomerGraceDays() + " дн. личный"
                + "; доступ по донату: " + (config.donateActive() ? config.donateMode() : "выключен"));
    }

    public void applyConfig(SocialConfig next) {
        this.config = next;
        donorCache.clear();
        // /social reload меняет интервалы: таймеры надо перезапустить, иначе до
        // рестарта сервера продолжат работать старые периоды.
        if (running) restartReminderTimers();
    }

    /** Периоды берутся из свежего конфига; вызывается при старте и reload. */
    private void restartReminderTimers() {
        if (reminderTask != null) reminderTask.cancel();
        if (actionBarTask != null) actionBarTask.cancel();
        long period = 20L * Math.max(10, config.reminderSeconds());
        reminderTask = Bukkit.getScheduler().runTaskTimer(plugin, this::remindPending, period, period);
        // Строка над хотбаром обновляется с тем же часовым интервалом: она
        // появляется при входе и больше не висит перед глазами постоянно.
        actionBarTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateActionBars,
                20L, 20L * Math.max(10, config.reminderActionBarSeconds()));
    }

    public void shutdown() {
        running = false;
        if (reminderTask != null) reminderTask.cancel();
        if (actionBarTask != null) actionBarTask.cancel();
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

    /** Общий дедлайн и личный срок от первого входа; действует более поздняя дата. */
    public long graceDeadline(String playerName, long now) {
        return SocialAccessPolicy.graceDeadline(store.rolloutStartedAt(),
                store.firstSeenAt(playerName), config.graceDays(), config.newcomerGraceDays(), now);
    }

    /** Глобальная часть переходного периода (для отчётов и обратной совместимости). */
    public boolean graceActive(long now) {
        return SocialAccessPolicy.graceActive(
                store.rolloutStartedAt() + config.graceMillis(), now);
    }

    public boolean graceActive(Player player, long now) {
        if (player == null) return graceActive(now);
        return SocialAccessPolicy.graceActive(graceDeadline(player.getName(), now), now);
    }

    /**
     * Заморозка действует только после grace и только для не-привязанных не-staff
     * игроков без подтверждённой оплаты на сайте.
     */
    public boolean isFrozen(Player player) {
        if (player == null || !config.mandatoryActive()) return false;
        if (staffBypass(player)) return false;
        if (store.loadFailed()) return !config.failOpenOnCorruption();
        long now = System.currentTimeMillis();
        if (emergencyActive(now)) return false;
        if (isLinked(player.getName())) return false;
        if (graceActive(player, now)) return false;
        return !donateAccess(player.getName());
    }

    public boolean needsInvitation(Player player) {
        if (player == null || !config.enabled() || isLinked(player.getName())) return false;
        long now = System.currentTimeMillis();
        return staffBypass(player) || graceActive(player, now) || emergencyActive(now)
                || isFrozen(player);
    }

    /* ------------------- доступ через подтверждённый донат на сайте ------------------- */

    /**
     * Платный доступ читает <em>уже подтверждённую</em> покупку из ядра
     * ({@code data.json}): {@code hasActivePass} или {@code hasEntitlement}.
     * Слова игрока в чате и «ручной ввод суммы» нигде не принимаются, а при
     * отсутствии Core API результат — отказ, а не обход обязательной привязки.
     */
    public boolean donateAccess(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        if (store.donorGranted(playerName)) return true;
        SocialConfig cfg = config;
        if (!cfg.donateActive()) return false;
        String key = SocialLinkCodeManager.canonical(playerName);
        long now = System.currentTimeMillis();
        DonorCheck cached = donorCache.get(key);
        if (cached != null && now - cached.checkedAt() < DONOR_CACHE_MILLIS) return cached.allowed();
        boolean allowed = checkDonate(playerName, cfg);
        donorCache.put(key, new DonorCheck(allowed, now));
        return allowed;
    }

    private boolean checkDonate(String playerName, SocialConfig cfg) {
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) {
            plugin.getLogger().fine("[CHRDK_SOCIAL] Core API недоступен — платный доступ не подтверждён.");
            return false;
        }
        try {
            boolean rank = SocialAccessPolicy.rankAllowed(cfg.donateMode())
                    && api.hasActivePass(playerName);
            boolean product = false;
            if (!rank && SocialAccessPolicy.productsAllowed(cfg.donateMode())) {
                for (String id : cfg.donateProductIds()) {
                    if (api.hasEntitlement(playerName, id)) {
                        product = true;
                        break;
                    }
                }
            }
            boolean allowed = rank || product;
            if (allowed && cfg.donatePermanent() && !store.donorGranted(playerName)) {
                String source = rank ? "rank" : "product";
                store.grantDonor(playerName, source, System.currentTimeMillis());
                audit.write("social.donate-access", playerName, "shop",
                        "source=" + source + ", permanent=true");
            }
            return allowed;
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.FINE,
                    "[CHRDK_SOCIAL] Не удалось проверить покупку для " + playerName, failure);
            return false;
        }
    }

    /** Ручная выдача доступа администратором: только с причиной и записью в аудит. */
    public String donorGrant(String playerName, String reason, String actor) {
        if (playerName == null || playerName.isBlank() || reason == null || reason.isBlank()) {
            return "Нужны ник и причина.";
        }
        store.grantDonor(playerName, "manual:" + reason.trim(), System.currentTimeMillis());
        donorCache.remove(SocialLinkCodeManager.canonical(playerName));
        audit.write("social.donate-grant", playerName, "shop", "actor=" + actor + ", reason=" + reason.trim());
        return "Доступ без привязки выдан " + playerName + ".";
    }

    public String donorRevoke(String playerName, String actor) {
        if (!store.revokeDonor(playerName)) return "У " + playerName + " нет доната-доступа.";
        donorCache.put(SocialLinkCodeManager.canonical(playerName),
                new DonorCheck(false, System.currentTimeMillis()));
        audit.write("social.donate-revoke", playerName, "shop", "actor=" + actor);
        return "Доступ по донату снят у " + playerName + ".";
    }

    public String donorStatus(String playerName) {
        long now = System.currentTimeMillis();
        return playerName + ": донат-доступ=" + (donateAccess(playerName) ? "да" : "нет")
                + ", запись=" + (store.donorGranted(playerName)
                        ? store.donorSource(playerName) : "нет")
                + ", режим=" + config.donateMode()
                + ", grace до " + formatDeadline(graceDeadline(playerName, now));
    }

    public List<String> donors() {
        return store.donors();
    }

    /**
     * Максимальный срок активного товара из {@code donate-access.product-ids}.
     * Read-only запрос к {@code data.json} ядра; 0 — покупки нет или ядро недоступно.
     */
    public long donateUntil(String playerName) {
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null || playerName == null || playerName.isBlank()) return 0L;
        long best = 0L;
        try {
            for (String id : config.donateProductIds()) {
                long until = api.entitlementUntil(playerName, id);
                if (until > best) best = until;
            }
        } catch (RuntimeException failure) {
            plugin.getLogger().fine("[CHRDK_SOCIAL] Не удалось прочитать срок товара для TAB");
            return 0L;
        }
        return best;
    }

    public String linkProvider(String playerName) {
        return store.binding(playerName).map(SocialLinkStore.Binding::provider)
                .orElseGet(() -> legacyVkBinding(playerName).isPresent() ? PROVIDER_VK : "");
    }

    public boolean permanentDonorAccess(String playerName) {
        return config.donatePermanent() && store.donorGranted(playerName);
    }

    /** Совместимый высокоуровневый статус для старых внешних шаблонов PAPI. */
    public String tabStatus(String playerName, long now) {
        String provider = linkProvider(playerName);
        return SocialAccessPolicy.tabStatus(isLinked(playerName), provider,
                donateUntil(playerName), graceDeadline(playerName, now), now,
                config.mandatoryActive(), emergencyActive(now), permanentDonorAccess(playerName));
    }

    /** Одна понятная строка TAB; после привязки счётчик и дедлайн не показываются. */
    public String tabLine(String playerName, long now) {
        String provider = linkProvider(playerName);
        return SocialAccessPolicy.tabLine(isLinked(playerName), provider,
                donateUntil(playerName), graceDeadline(playerName, now), now,
                config.mandatoryActive(), emergencyActive(now), permanentDonorAccess(playerName));
    }

    private static String formatDeadline(long millis) {
        if (millis <= 0L) return "—";
        return Instant.ofEpochMilli(millis).atZone(MOSCOW).format(DEADLINE_FORMAT);
    }

    public void onJoin(Player player) {
        if (!config.enabled()) return;
        String key = SocialLinkCodeManager.canonical(player.getName());
        long now = System.currentTimeMillis();
        // Один раз фиксируем первый вход: от него считается личный переходный срок.
        store.recordFirstSeen(player.getName(), now);
        if (isLinked(player.getName())) {
            settle(player, key);
            return;
        }
        if (staffBypass(player)) {
            // Администрация не замораживается, но должна знать, что видят игроки:
            // раньше op вообще выходил отсюда без единой строки.
            settle(player, key);
            plugin.messages().send(player, "social.gate-staff");
            return;
        }
        if (donateAccess(player.getName())) {
            settle(player, key);
            plugin.messages().send(player, "social.donate-welcome",
                    "url", config.donateUrl());
            audit.write("social.join-donor", player.getName(), "shop",
                    "доступ по подтверждённой оплате, привязка добровольна");
            return;
        }
        pending.add(key);
        String code = issueCode(player);
        long deadline = graceDeadline(player.getName(), now);
        int days = SocialAccessPolicy.remainingDays(deadline, now);
        String url = config.donateUrl();

        // Один общий кулдаун на ВСЕ показы: полный блок при входе, периодическое
        // напоминание и пинг при действиях. Запись переживает выход, поэтому
        // перезаход в течение часа не обнуляет паузу и не повторяет пять строк
        // инструкции. При nagging: false сохраняется прежнее поведение — только
        // блок при входе, и он показывается при каждом входе.
        boolean showGate = !config.reminderNagging() || allowChatReminder(key, now);
        if (showGate) {
            // Крупный заголовок и звук: блок в чате легко потерять среди системных сообщений.
            if (config.reminderTitleOnJoin()) showEntryTitle(player);
            if (config.reminderSound()) playNagSound(player);

            plugin.messages().send(player, "social.gate-choice");
            plugin.messages().send(player, "social.gate-code",
                    "code", code, "minutes", Math.max(1, config.codeTtlSeconds() / 60));
            // Подробная инструкция именно по ВК: MAX в разработке и игрокам не предлагается.
            plugin.messages().send(player, "social.gate-howto",
                    "code", code, "vk", config.vkCommunityUrl());
            if (config.donateActive()) {
                plugin.messages().send(player, "social.gate-donate", "url", url);
            }
            if (isFrozen(player)) {
                plugin.messages().send(player, "social.gate-closed", "url", url);
            } else {
                plugin.messages().send(player, "social.gate-grace",
                        "days", days, "date", formatDeadline(deadline));
            }
            player.sendActionBar(actionBarComponent(player, code, days));
        }
        audit.write("social.join-pending", player.getName(), "", "выдан код входа, требуется выбор способа входа");
    }

    public void onQuit(Player player) {
        String key = SocialLinkCodeManager.canonical(player.getName());
        pending.remove(key);
        // lastChatReminder НЕ удаляем: общий часовой кулдаун должен пережить
        // перезаход, иначе релог снова включит назойливые напоминания.
    }

    /* ------------------------- назойливое информирование ------------------------- */

    /** Снимает все напоминания, как только игрок привязался или купил проход. */
    private void settle(Player player, String key) {
        pending.remove(key);
        lastChatReminder.remove(key);
        lastFrozenNotice.remove(key);
        clearActionBar(player);
    }

    /** @return {@code true}, если игрок уже выбрал способ входа и его не нужно беспокоить. */
    private boolean settled(Player player, String key) {
        if (isLinked(player.getName()) || staffBypass(player) || donateAccess(player.getName())) {
            settle(player, key);
            return true;
        }
        return false;
    }

    private void showEntryTitle(Player player) {
        try {
            Component title = plugin.messages().get("social.title");
            Component subtitle = plugin.messages().get("social.subtitle");
            player.showTitle(Title.title(title, subtitle,
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(5), Duration.ofSeconds(1))));
        } catch (Throwable ignored) {
            // Без поддержки showTitle(Title) остаётся блок сообщений в чате.
        }
    }

    private void playNagSound(Player player) {
        try {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
        } catch (Throwable ignored) {
            // Звук — украшение, не условие доставки сообщения.
        }
    }

    private void clearActionBar(Player player) {
        try {
            player.sendActionBar(Component.empty());
        } catch (Throwable ignored) {
            // Старые сборки Paper без sendActionBar.
        }
    }

    private Component actionBarComponent(Player player, String code, int days) {
        String key = isFrozen(player) ? "social.action-bar-closed" : "social.action-bar";
        return plugin.messages().get(key, "code", code, "url", config.donateUrl(), "days", days);
    }

    /** Пинг при движении/команде/чате/действии. Игру в grace не блокирует. */
    public void nagOnAction(Player player) {
        if (player == null || !config.enabled() || !config.reminderNagging()) return;
        String key = SocialLinkCodeManager.canonical(player.getName());
        if (!pending.contains(key)) return;
        long now = System.currentTimeMillis();
        if (settled(player, key)) return;
        if (!allowChatReminder(key, now)) return;
        long deadline = graceDeadline(player.getName(), now);
        int days = SocialAccessPolicy.remainingDays(deadline, now);
        plugin.messages().send(player, "social.action-nag",
                "url", config.donateUrl(), "vk", config.vkCommunityUrl(), "days", days);
    }

    /** Один общий кулдаун для remindPending и nagOnAction (чатовый спам исключён). */
    private boolean allowChatReminder(String key, long now) {
        long pause = Math.max(config.reminderSeconds(), config.reminderActionNagSeconds()) * 1000L;
        Long last = lastChatReminder.get(key);
        if (last != null && now - last < pause) return false;
        lastChatReminder.put(key, now);
        if (lastChatReminder.size() > 4096) {
            lastChatReminder.entrySet().removeIf(entry -> now - entry.getValue() > pause * 4L);
        }
        return true;
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

    /** Периодическое напоминание в чате — ВСЕМ непривязанным, а не только замороженным. */
    private void remindPending() {
        if (!config.enabled() || !config.reminderNagging()) return;
        long now = System.currentTimeMillis();
        for (String key : pending) {
            Player player = Bukkit.getPlayerExact(key);
            if (player == null || !player.isOnline()) continue;
            if (settled(player, key)) continue;
            if (!allowChatReminder(key, now)) continue;
            Optional<String> code = codes.peek(player.getName(), now);
            if (code.isEmpty()) code = Optional.of(issueCode(player));
            int days = SocialAccessPolicy.remainingDays(graceDeadline(player.getName(), now), now);
            plugin.messages().send(player, "social.reminder-choice",
                    "code", code.get(), "url", config.donateUrl(),
                    "vk", config.vkCommunityUrl(), "days", days);
            if (config.reminderSound()) playNagSound(player);
        }
    }

    /** Постоянная строка над хотбаром, пока игрок не привязался или не купил проход. */
    private void updateActionBars() {
        if (!config.enabled() || !config.reminderNagging()) {
            for (String key : pending) {
                Player player = Bukkit.getPlayerExact(key);
                if (player != null) clearActionBar(player);
            }
            return;
        }
        long now = System.currentTimeMillis();
        for (String key : pending) {
            Player player = Bukkit.getPlayerExact(key);
            if (player == null || !player.isOnline()) continue;
            if (settled(player, key)) continue;
            String code = codes.peek(player.getName(), now).orElse("?");
            int days = SocialAccessPolicy.remainingDays(graceDeadline(player.getName(), now), now);
            try {
                player.sendActionBar(actionBarComponent(player, code, days));
            } catch (Throwable ignored) {
                // Action bar необязателен; чат и TAB продолжают работать.
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
        if (isLinked(playerName)) {
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
            settle(player, SocialLinkCodeManager.canonical(playerName));
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
                .orElseGet(() -> legacyVkBinding(playerName)
                        .map(value -> "vk #" + value.vkUserId() + " (legacy)")
                        .orElse("не привязан"));
        long now = System.currentTimeMillis();
        return "Привязка: " + state
                + " | заморозка: " + (isFrozen(Bukkit.getPlayerExact(playerName)) ? "да" : "нет")
                + " | донат-доступ: " + (donateAccess(playerName) ? "да" : "нет")
                + " | grace до: " + formatDeadline(graceDeadline(playerName, now))
                + " | аварийный режим: " + (emergencyActive(now)
                        ? "до " + formatDeadline(store.emergencyUntil()) : "выключен")
                + " | всего привязок: " + linkCount()
                + " | донатных доступов: " + store.donorCount();
    }

    public String unlink(String playerName, String actor) {
        Optional<SocialLinkStore.Binding> removed = store.unlink(playerName);
        Optional<VkStore.LinkSnapshot> legacy = rawLegacyVkBinding(playerName);
        if (removed.isEmpty() && legacy.isEmpty()) return "У игрока " + playerName + " нет привязки.";
        boolean legacyRemoved = legacy.isPresent() && forgetLegacyVkBinding(playerName);
        String provider = removed.map(SocialLinkStore.Binding::provider)
                .orElseGet(() -> legacy.isPresent() ? PROVIDER_VK : "");
        audit.write("social.unlink", playerName, provider,
                "actor=" + actor + ", legacy=" + (legacyRemoved ? "removed" : "absent-or-failed"));
        Player player = Bukkit.getPlayerExact(playerName);
        if (player != null && player.isOnline()) {
            pending.add(SocialLinkCodeManager.canonical(playerName));
            String code = issueCode(player);
            plugin.messages().send(player, "social.unlinked", "code", code);
        }
        return "Привязка " + playerName + " снята. Игроку выдан новый код входа."
                + (legacy.isPresent() && !legacyRemoved
                        ? " Внимание: vk-links.json не сохранён — проверьте лог и повторите." : "");
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
