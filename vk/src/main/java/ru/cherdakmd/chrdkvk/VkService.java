package ru.cherdakmd.chrdkvk;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Центральная служба {@code CHRDK_VK}: связывает хранилище привязок, одноразовые коды,
 * клиента VK API, фоновый Long Poll, двусторонний мост с беседой сообщества ВКонтакте
 * и мосты {@link ChrdkRegisterApi}.
 */
public final class VkService implements VkBotRouter.Backend, ChrdkRegisterApi.SecurityListener, ChrdkRegisterApi.VkCodeDelivery, Listener {

    public static final String PROVIDER_ID = "vk";
    private static final DateTimeFormatter MSK_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Moscow"));
    private static final long DISPLAY_NAME_CACHE_TTL_MS = TimeUnit.HOURS.toMillis(6);

    public record ServerPulse(int online, int maxPlayers, double tps, List<String> playerNames) {
    }

    public record SyncReport(int checkedLinks, int activeDonuts) {
    }

    private record CachedDisplayName(String name, long expiresAt) {
    }

    private final VkPlugin plugin;
    private final VkStore store;
    private final VkLinkCodeManager codeManager = new VkLinkCodeManager();
    private final VkBotRouter router;
    private final ExecutorService securityExecutor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "CHRDK-VK-Security"); thread.setDaemon(true); return thread;
            });
    private final ExecutorService outboundExecutor;
    private final Map<Long, Long> vkChatCooldowns = new ConcurrentHashMap<>();
    private final Map<Long, CachedDisplayName> vkDisplayNames = new ConcurrentHashMap<>();
    private final Set<UUID> announcedOnlineSessions = ConcurrentHashMap.newKeySet();

    private volatile VkApiClient apiClient;
    private volatile VkLongPollWorker longPollWorker;
    private volatile VkChatBatcher chatBatcher;
    private volatile ServerPulse serverPulse = new ServerPulse(0, 0, 20.0, List.of());
    private BukkitTask onboardingTask;
    private final Map<UUID, Long> onboardingDue = new ConcurrentHashMap<>();
    private final Set<UUID> onboardingCompleted = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean onboardingBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private BukkitTask pulseTask;
    private BukkitTask syncTask;

    public VkService(VkPlugin plugin, VkStore store) {
        this.plugin = plugin;
        this.store = store;
        this.router = new VkBotRouter(this);
        this.outboundExecutor = new ThreadPoolExecutor(
                1, 2, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256),
                runnable -> {
                    Thread t = new Thread(runnable, "CHRDK-VK-Outbound");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );
    }

    public VkStore store() {
        return store;
    }

    public VkLinkCodeManager codeManager() {
        return codeManager;
    }

    public VkBotRouter router() {
        return router;
    }

    public boolean longPollConnected() {
        VkLongPollWorker worker = this.longPollWorker;
        return worker != null && worker.isConnected();
    }

    public void start() {
        registerCoreBridges();
        applyRuntimeConfig();
        this.onboardingTask = Bukkit.getScheduler().runTaskTimer(plugin, this::remindOnboarding, 200L, 400L);
        this.pulseTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshServerPulse, 1L, 100L);
    }

    /** Пересобирает сетевых клиентов и задачи опроса после старта или {@code /vkadmin reload}. */
    public synchronized void applyRuntimeConfig() {
        stopNetworkWorkers();
        onboardingCompleted.clear(); onboardingDue.clear();
        VkConfig cfg = plugin.config();
        if (!cfg.apiConfigured()) {
            this.apiClient = null;
            return;
        }
        VkApiClient client = new VkApiClient(cfg);
        this.apiClient = client;
        this.chatBatcher = new VkChatBatcher(
                cfg.groupChatBatchWindowMs(), 5, 1_500, (peer, batchText) -> sendAsync(peer, batchText, null));

        if (cfg.longPollEnabled()) {
            VkLongPollWorker worker = new VkLongPollWorker(
                    client,
                    cfg.groupId(),
                    cfg.longPollWaitSeconds(),
                    cfg.longPollReconnectSeconds(),
                    router::handleUpdate,
                    plugin.getLogger()
            );
            this.longPollWorker = worker;
            worker.start();
        }

        long recheckTicks = Math.max(5, cfg.recheckMinutes()) * 60L * 20L;
        this.syncTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, () -> runSyncSafely(null), 100L, recheckTicks);


    }

    public synchronized void shutdown() {
        if (onboardingTask != null) { onboardingTask.cancel(); onboardingTask = null; }
        onboardingDue.clear(); onboardingCompleted.clear();
        if (pulseTask != null) {
            pulseTask.cancel();
            pulseTask = null;
        }
        stopNetworkWorkers();
        unregisterCoreBridges();
        securityExecutor.shutdownNow();
        outboundExecutor.shutdown();
        try {
            if (!outboundExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                outboundExecutor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            outboundExecutor.shutdownNow();
        }
    }

    private void stopNetworkWorkers() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
        VkChatBatcher batcher = this.chatBatcher;
        this.chatBatcher = null;
        if (batcher != null) {
            batcher.close();
        }
        VkLongPollWorker worker = this.longPollWorker;
        this.longPollWorker = null;
        if (worker != null) {
            worker.close();
        }
    }

    private void refreshServerPulse() {
        ChrdkRegisterApi api = plugin.coreApi();
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (api == null || api.isAuthenticated(p)) {
                names.add(p.getName());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();
        double[] tpsArr = Bukkit.getTPS();
        double tps = tpsArr != null && tpsArr.length > 0 ? Math.min(20.0, tpsArr[0]) : 20.0;
        this.serverPulse = new ServerPulse(online, max, tps, List.copyOf(names));
    }

    /** Возвращает действующий {@code peer_id} беседы ВК (переопределение {@code /vkadmin setchat} либо {@code config.yml}). */
    public long effectiveGroupChatPeerId() {
        long stored = store.groupChatPeerId();
        if (stored >= VkConfig.MIN_CONVERSATION_PEER_ID) {
            return stored;
        }
        return plugin.config().groupChatPeerId();
    }

    /** Активен ли сейчас мост глобального чата с беседой сообщества ВК. */
    public boolean groupChatBridgeReady() {
        VkConfig cfg = plugin.config();
        return cfg.enabled()
                && cfg.apiConfigured()
                && cfg.groupChatEnabled()
                && effectiveGroupChatPeerId() >= VkConfig.MIN_CONVERSATION_PEER_ID;
    }

    /** Отправляет произвольное сообщение в привязанную беседу ВК (например, {@code /vkadmin testchat}). */
    public boolean sendToGroupChat(String text) {
        if (!groupChatBridgeReady() || text == null || text.isBlank()) {
            return false;
        }
        sendAsync(effectiveGroupChatPeerId(), text);
        return true;
    }

    /* ---------------- мосты ChrdkRegisterApi ---------------- */

    private void registerCoreBridges() {
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) {
            return;
        }
        try {
            api.registerNameTag(PROVIDER_ID, this::decorateNameTag);
            api.registerProfile(PROVIDER_ID, new ChrdkRegisterApi.ProfileProvider() {
                @Override
                public int order() {
                    return 35;
                }

                @Override
                public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
                    return buildProfileSection(context);
                }
            });
            api.registerSecurityListener(PROVIDER_ID, this);
            api.registerVkCodeDelivery(this);
            api.registerNotificationListener(PROVIDER_ID, this::forwardAdminNotification);
            api.registerGlobalChatListener(PROVIDER_ID, this::onMinecraftGlobalChat);
            api.registerShopBonusProvider(PROVIDER_ID, new ChrdkRegisterApi.ShopBonusProvider() {
                @Override
                public boolean available() {
                    VkConfig cfg = plugin.config();
                    return cfg.enabled() && cfg.apiConfigured() && cfg.donutShopBonusPercent() > 0;
                }

                @Override
                public int bonusPercent(String playerName) {
                    VkConfig cfg = plugin.config();
                    if (!cfg.enabled() || cfg.donutShopBonusPercent() <= 0) {
                        return 0;
                    }
                    return store.donutPerkActive(playerName, System.currentTimeMillis(), cfg.donutGraceHours())
                            ? cfg.donutShopBonusPercent() : 0;
                }
            });
        } catch (RuntimeException | LinkageError error) {
            plugin.getLogger().log(Level.WARNING,
                    "[CHRDK_VK] Не удалось зарегистрировать мосты в CHRDK_Core (нужен API v32+)", error);
        }
    }

    private void unregisterCoreBridges() {
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) {
            return;
        }
        try {
            api.unregisterNameTag(PROVIDER_ID);
            api.unregisterProfile(PROVIDER_ID);
            api.unregisterSecurityListener(PROVIDER_ID);
            api.unregisterVkCodeDelivery(this);
            api.unregisterNotificationListener(PROVIDER_ID);
            api.unregisterGlobalChatListener(PROVIDER_ID);
            api.unregisterShopBonusProvider(PROVIDER_ID);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    String decorateNameTag(String playerName, String decoratedName) {
        if (playerName == null || decoratedName == null) {
            return decoratedName;
        }
        VkConfig cfg = plugin.config();
        if (!cfg.enabled() || !cfg.labelsEnabled()) {
            return decoratedName;
        }
        Optional<VkStore.LinkSnapshot> opt = store.byPlayer(playerName);
        if (opt.isEmpty()) {
            return decoratedName;
        }
        VkStore.LinkSnapshot link = opt.get();
        if (store.donutPerkActive(playerName, System.currentTimeMillis(), cfg.donutGraceHours())) {
            return cfg.donutTag() + " " + decoratedName;
        }
        if (cfg.labelsRequireMembership() && !link.member()) {
            return decoratedName;
        }
        return cfg.memberTag() + " " + decoratedName;
    }

    private ChrdkRegisterApi.ProfileSection buildProfileSection(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || !plugin.config().enabled()) {
            return null;
        }
        Messages messages = plugin.messages();
        Optional<VkStore.LinkSnapshot> opt = store.byPlayer(context.playerName());
        if (opt.isEmpty()) {
            if (!context.self()) {
                return null;
            }
            return new ChrdkRegisterApi.ProfileSection(
                    messages.raw("vk.profile-title"),
                    Material.LAPIS_LAZULI,
                    List.of(messages.get("vk.profile-unlinked"))
            );
        }
        VkStore.LinkSnapshot link = opt.get();
        boolean donutActive = store.donutPerkActive(
                context.playerName(), System.currentTimeMillis(), plugin.config().donutGraceHours());
        List<Component> lines = new ArrayList<>(2);
        if (context.self()) {
            lines.add(messages.get("vk.profile-linked",
                    "vk_id", Long.toString(link.vkUserId()),
                    "member", link.member() ? messages.mini("vk.state-yes") : messages.mini("vk.state-no")));
        }
        lines.add(messages.get("vk.profile-donut",
                "donut", donutActive ? messages.mini("vk.state-yes") : messages.mini("vk.state-no")));
        return new ChrdkRegisterApi.ProfileSection(
                messages.raw("vk.profile-title"),
                Material.LAPIS_LAZULI,
                lines
        );
    }

    /* ---------------- SecurityListener & Admin Notifications ---------------- */

    @Override
    public void onLoginSuccess(String playerName, String maskedIp, String method) {
        VkConfig cfg = plugin.config();
        if (!cfg.enabled() || !cfg.loginAlerts()) {
            return;
        }
        store.byPlayer(playerName).ifPresent(link -> {
            if (!link.notifyLogins()) {
                return;
            }
            String mode = "session".equalsIgnoreCase(method) ? "восстановление IP-сессии" : "вход по паролю";
            String text = plugin.messages().plain("bot.login-alert",
                    "player", link.playerName(), "method", mode,
                    "ip", maskedIp == null || maskedIp.isBlank() ? "скрыт" : maskedIp);
            String inlineKb = cfg.inlineSecurityButtons() ? VkKeyboards.securityAlertInlineKeyboard() : null;
            sendAsync(link.vkUserId(), text, inlineKb);
        });
    }

    @Override
    public void onBruteForceAlert(String playerName, String maskedIp, int failures, boolean blocked) {
        VkConfig cfg = plugin.config();
        if (!cfg.enabled() || !cfg.bruteforceAlerts()) {
            return;
        }
        store.byPlayer(playerName).ifPresent(link -> {
            String status = blocked ? "Вход с этого адреса временно заблокирован." : "Подбор продолжается.";
            String text = plugin.messages().plain("bot.brute-alert",
                    "player", link.playerName(), "failures", failures, "status", status,
                    "ip", maskedIp == null || maskedIp.isBlank() ? "скрыт" : maskedIp);
            String inlineKb = cfg.inlineSecurityButtons() ? VkKeyboards.securityAlertInlineKeyboard() : null;
            sendAsync(link.vkUserId(), text, inlineKb);
        });
    }

    @Override
    public void onPassExpiring(String playerName, long passUntilMillis, long remainingMinutes) {
        VkConfig cfg = plugin.config();
        if (!cfg.enabled() || !cfg.expiryAlerts()) {
            return;
        }
        store.byPlayer(playerName).ifPresent(link -> {
            String until = passUntilMillis > 0L
                    ? MSK_TIME.format(Instant.ofEpochMilli(passUntilMillis)) + " МСК"
                    : "скоро";
            long hours = Math.max(1L, remainingMinutes / 60L);
            String text = plugin.messages().plain("bot.expiry-alert",
                    "player", link.playerName(), "hours", hours, "until", until);
            sendAsync(link.vkUserId(), text);
        });
    }

    private void forwardAdminNotification(String event, String title, String description, String severity) {
        VkConfig cfg = plugin.config();
        if (!cfg.enabled() || cfg.adminPeerId() <= 0L) {
            return;
        }
        String icon = switch (severity == null ? "info" : severity.toLowerCase(Locale.ROOT)) {
            case "good" -> "🟢";
            case "warn" -> "🟠";
            case "bad" -> "🔴";
            default -> "🔵";
        };
        String header = title == null || title.isBlank() ? "CHRDK" : title;
        String body = description == null ? "" : description;
        sendAsync(cfg.adminPeerId(), icon + " [" + header + "]\n" + body);
    }

    /* ---------------- VkBotRouter.Backend ---------------- */

    @Override
    public String handleLinkCode(long vkUserId, String code, long nowMillis) {
        VkConfig cfg = plugin.config();
        if (!cfg.enabled()) {
            return "Интеграция ВКонтакте временно отключена на сервере.";
        }
        Optional<String> matched = codeManager.consume(code, nowMillis);
        if (matched.isEmpty()) {
            return "❌ Код не найден или уже истёк. Запросите новый код в игре: /vk link";
        }
        String playerName = matched.get();
        VkStore.LinkStatus status = store.link(playerName, vkUserId, cfg.maxAccountsPerVk(), nowMillis);
        return switch (status) {
            case ALREADY_LINKED_PLAYER -> "Этот игровой аккаунт уже привязан к ВКонтакте.";
            case VK_ACCOUNT_LIMIT_REACHED -> "К вашему профилю ВКонтакте уже привязан максимум игровых аккаунтов ("
                    + cfg.maxAccountsPerVk() + ").";
            case INVALID_ARGUMENT -> "Не удалось выполнить привязку.";
            case LINKED -> {
                boolean isMember = queryMembershipSafe(vkUserId);
                if (isMember) {
                    store.updateMemberStatus(vkUserId, true);
                }
                store.saveNow();
                grantEligibleBoostsOnMainThread(playerName, vkUserId, isMember);
                notifyPlayerLinkedInGame(playerName, vkUserId);
                yield "✅ Аккаунт " + playerName + " успешно привязан к ВКонтакте!\n"
                        + "• Проверьте уведомления и настройки самозащиты в меню /vk в игре.\n"
                        + "• Экстренные команды: /выход, /заморозить, /разморозить, /профиль.";
            }
        };
    }

    @Override
    public String formatServerOnline() {
        ServerPulse pulse = this.serverPulse;
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "🌐 Сервер CHRDK в сети\nОнлайн: %d / %d · TPS: %.1f",
                pulse.online(), pulse.maxPlayers(), pulse.tps()));
        List<String> names = pulse.playerNames();
        if (names != null && !names.isEmpty()) {
            int shown = Math.min(15, names.size());
            sb.append("\nИгроки: ").append(String.join(", ", names.subList(0, shown)));
            if (names.size() > shown) {
                sb.append(" (+").append(names.size() - shown).append(")");
            }
        }
        return sb.toString();
    }

    @Override
    public String formatNewsLinks() {
        return plugin.messages().plain("bot.news-links", "url", plugin.config().communityUrl());
    }

    @Override
    public String formatWikiLinks() {
        VkConfig cfg = plugin.config();
        return "📖 Полевой справочник CHRDK: " + cfg.wikiUrl()
                + "\n💬 Сообщество ВКонтакте: " + cfg.communityUrl();
    }

    @Override
    public long activeGroupChatPeerId() {
        VkConfig cfg = plugin.config();
        if (!cfg.enabled() || !cfg.groupChatEnabled()) {
            return 0L;
        }
        return effectiveGroupChatPeerId();
    }

    @Override
    public boolean groupChatInfoCommandsEnabled() {
        VkConfig cfg = plugin.config();
        return cfg.enabled() && cfg.groupChatEnabled() && cfg.groupChatAllowInfoCommands();
    }

    @Override
    public void onGroupChatMessage(long peerId, long fromUserId, JsonObject message, long nowMillis) {
        VkConfig cfg = plugin.config();
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null || !cfg.enabled() || !cfg.groupChatEnabled() || !cfg.groupChatVkToMc()) {
            return;
        }
        if (peerId != effectiveGroupChatPeerId() || store.isVkUserChatBanned(fromUserId)) {
            return;
        }

        int cooldownSec = cfg.groupChatVkCooldownSeconds();
        if (cooldownSec > 0) {
            Long prev = vkChatCooldowns.get(fromUserId);
            if (prev != null && nowMillis - prev < cooldownSec * 1_000L) {
                return;
            }
            vkChatCooldowns.put(fromUserId, nowMillis);
        }

        List<VkStore.LinkSnapshot> links = store.byVkUser(fromUserId);
        if (links.isEmpty() && cfg.groupChatRequireLinked()) {
            return;
        }

        String text = VkApiClient.extractGroupChatText(message, cfg.groupChatMaxLength(), replyVkId -> {
            List<VkStore.LinkSnapshot> targetLinks = store.byVkUser(replyVkId);
            return targetLinks.isEmpty() ? null : targetLinks.getFirst().playerName();
        });
        if (text.isEmpty()) {
            return;
        }

        String linkedPlayer = links.isEmpty() ? null : links.getFirst().playerName();
        String fallbackSender = linkedPlayer != null ? linkedPlayer : resolveVkDisplayName(fromUserId, nowMillis);
        api.deliverExternalGlobalChat(
                PROVIDER_ID, cfg.groupChatMcBadge(), linkedPlayer, fallbackSender, text, store::receivesGroupChat);
    }

    private void onMinecraftGlobalChat(String playerName, String plainMessage) {
        VkConfig cfg = plugin.config();
        if (!cfg.enabled() || !cfg.apiConfigured() || !cfg.groupChatEnabled() || !cfg.groupChatMcToVk()) {
            return;
        }
        long peerId = effectiveGroupChatPeerId();
        if (peerId < VkConfig.MIN_CONVERSATION_PEER_ID) {
            return;
        }
        String formatted = VkApiClient.formatMinecraftToVkLine(
                cfg.groupChatVkFormat(),
                playerName,
                plainMessage,
                cfg.groupChatResolveMentions()
                        ? candidate -> store.byPlayer(candidate)
                        .filter(VkStore.LinkSnapshot::allowVkChatMentions)
                        .map(l -> OptionalLong.of(l.vkUserId()))
                        .orElse(OptionalLong.empty())
                        : null
        );
        if (formatted.isEmpty()) {
            return;
        }
        VkChatBatcher batcher = this.chatBatcher;
        if (batcher != null) {
            batcher.enqueue(peerId, formatted);
        } else {
            sendAsync(peerId, formatted);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerAuthCompleted(PlayerAuthCompletedEvent event) {
        Player player = event.getPlayer();
        announcedOnlineSessions.add(player.getUniqueId());
        VkConfig cfg = plugin.config();
        if (!cfg.groupChatRelayJoinQuit() || !groupChatBridgeReady()) {
            return;
        }
        sendAsync(effectiveGroupChatPeerId(), "🟢 " + player.getName() + " зашёл на сервер.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        onboardingDue.remove(event.getPlayer().getUniqueId());
        onboardingCompleted.remove(event.getPlayer().getUniqueId());
        Player player = event.getPlayer();
        boolean wasAuthenticated = announcedOnlineSessions.remove(player.getUniqueId());
        VkConfig cfg = plugin.config();
        if (!wasAuthenticated || !cfg.groupChatRelayJoinQuit() || !groupChatBridgeReady()) {
            return;
        }
        sendAsync(effectiveGroupChatPeerId(), "🔴 " + player.getName() + " вышел с сервера.");
    }

    private String resolveVkDisplayName(long vkUserId, long nowMillis) {
        CachedDisplayName cached = vkDisplayNames.get(vkUserId);
        if (cached != null && cached.expiresAt() > nowMillis) {
            return cached.name();
        }
        VkApiClient client = this.apiClient;
        if (client == null) {
            return "id" + vkUserId;
        }
        try {
            String fetched = client.fetchUserDisplayName(vkUserId);
            vkDisplayNames.put(vkUserId, new CachedDisplayName(fetched, nowMillis + DISPLAY_NAME_CACHE_TTL_MS));
            return fetched;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "id" + vkUserId;
        } catch (Exception ignored) {
            return "id" + vkUserId;
        }
    }

    @Override
    public String formatAccountProfile(long vkUserId, long nowMillis) {
        List<VkStore.LinkSnapshot> links = store.byVkUser(vkUserId);
        if (links.isEmpty()) {
            return "У вас нет привязанного аккаунта. Введите /vk link в игре и отправьте полученный код сюда.";
        }
        ChrdkRegisterApi api = plugin.coreApi();
        StringBuilder sb = new StringBuilder();
        for (VkStore.LinkSnapshot link : links) {
            if (!sb.isEmpty()) sb.append("\n\n");
            String name = link.playerName();
            int rank = api == null ? 0 : api.rankLevel(name);
            boolean twoFactor = api != null && api.isTwoFactorEnabled(name);
            boolean frozen = api != null && api.isLoginFrozen(name);
            int sessions = api == null ? 0 : api.activeSessionCount(name);
            long boostMinutes = api == null ? 0L : api.rewardBoostRemainingMillis(name) / 60_000L;
            boolean donutActive = store.donutPerkActive(name, nowMillis, plugin.config().donutGraceHours());

            sb.append("👤 Аккаунт: ").append(name)
                    .append("\n• Ступень ранга: ").append(rank > 0 ? rank : "нет")
                    .append("\n• Защита 2FA: ").append(twoFactor ? "включена" : "не подключена")
                    .append("\n• Вход заморожен: ").append(frozen ? "ДА (/разморозить)" : "нет")
                    .append("\n• Активных IP-сессий: ").append(sessions)
                    .append("\n• Запас XP-буста: ").append(boostMinutes).append(" мин.")
                    .append("\n• Подписка на группу: ").append(link.member() ? "да" : "нет")
                    .append("\n• Бонус VK Donut: ").append(donutActive ? "активен" : "нет");
        }
        return sb.toString();
    }

    @Override
    public String handleRemoteLogout(long vkUserId) {
        if (!plugin.config().allowRemoteLogout()) {
            return "Удалённый сброс сессий отключён в настройках сервера.";
        }
        List<VkStore.LinkSnapshot> links = store.byVkUser(vkUserId);
        if (links.isEmpty()) {
            return "Сначала привяжите игровой аккаунт командой /vk link в игре.";
        }
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) {
            return "Сервис авторизации временно недоступен.";
        }
        List<String> affected = new ArrayList<>();
        for (VkStore.LinkSnapshot link : links) {
            if (api.terminateSessions(link.playerName(), PROVIDER_ID)) {
                affected.add(link.playerName());
            }
        }
        return affected.isEmpty()
                ? "Не удалось сбросить сессии аккаунта."
                : "✅ Все IP-сессии сброшены и активный сеанс завершён для: " + String.join(", ", affected);
    }

    @Override
    public String handleRemoteFreeze(long vkUserId, boolean freeze) {
        if (!plugin.config().allowRemoteFreeze()) {
            return "Удалённая заморозка входа отключена в настройках сервера.";
        }
        List<VkStore.LinkSnapshot> links = store.byVkUser(vkUserId);
        if (links.isEmpty()) {
            return "Сначала привяжите игровой аккаунт командой /vk link в игре.";
        }
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) {
            return "Сервис авторизации временно недоступен.";
        }
        List<String> updated = new ArrayList<>();
        for (VkStore.LinkSnapshot link : links) {
            if (api.setLoginFrozen(link.playerName(), freeze, PROVIDER_ID)) {
                updated.add(link.playerName());
            }
        }
        if (updated.isEmpty()) {
            return "Не удалось изменить состояние блокировки входа.";
        }
        return freeze
                ? "🔒 Вход заморожен и активные сессии завершены для: " + String.join(", ", updated)
                        + ".\nЧтобы снова войти в игру, отправьте /разморозить."
                : "🔓 Заморозка входа снята для: " + String.join(", ", updated)
                        + ". Теперь вы снова можете авторизоваться на сервере.";
    }

    @Override
    public String handleToggleNotifications(long vkUserId) {
        List<VkStore.LinkSnapshot> links = store.byVkUser(vkUserId);
        if (links.isEmpty()) {
            return "Сначала привяжите игровой аккаунт командой /vk link в игре.";
        }
        boolean enabledNow = false;
        for (VkStore.LinkSnapshot link : links) {
            enabledNow = store.toggleNotifyLogins(link.playerName()).orElse(false);
        }
        store.saveIfDirty();
        return enabledNow
                ? "🔔 Уведомления о входах в аккаунт включены."
                : "🔕 Уведомления о входах отключены (экстренные оповещения о подборе пароля остаются активными).";
    }

    @Override
    public String handleUnlinkFromVk(long vkUserId) {
        ChrdkRegisterApi securityApi = plugin.coreApi();
        if (securityApi == null || store.byVkUser(vkUserId).stream().anyMatch(link -> securityApi.vkTwoFactorEnabled(link.playerName())))
            return "Сначала отключите ВК-2FA в игре с подтверждением кода. Отвязка не может отменить защиту входа.";
        List<VkStore.LinkSnapshot> removed = store.unlinkByVkUser(vkUserId);
        if (removed.isEmpty()) {
            return "К вашему профилю ВКонтакте не привязан ни один аккаунт.";
        }
        ChrdkRegisterApi api = plugin.coreApi();
        if (api != null) {
            for (VkStore.LinkSnapshot link : removed) {
                if (api.isLoginFrozen(link.playerName())) {
                    api.setLoginFrozen(link.playerName(), false, PROVIDER_ID);
                }
            }
        }
        store.saveNow();
        return "✅ Привязка ВКонтакте снята. Если вход был заморожен, блокировка также снята.";
    }

    @Override
    public void sendDirectReply(long peerId, String text) {
        sendAsync(peerId, text, null);
    }

    @Override
    public void sendDirectReplyWithMenu(long peerId, String text) {
        String kb = plugin.config().keyboardsEnabled() && peerId < VkConfig.MIN_CONVERSATION_PEER_ID
                ? VkKeyboards.directMenuKeyboard() : null;
        sendAsync(peerId, text, kb);
    }

    @Override
    public void onGroupJoin(long vkUserId, long nowMillis) {
        store.updateMemberStatus(vkUserId, true);
        store.saveIfDirty();
        for (VkStore.LinkSnapshot link : store.byVkUser(vkUserId)) {
            grantEligibleBoostsOnMainThread(link.playerName(), vkUserId, true);
        }
    }

    @Override
    public void onGroupLeave(long vkUserId) {
        store.updateMemberStatus(vkUserId, false);
        store.saveIfDirty();
    }

    @Override
    public void onDonutActive(long vkUserId, long nowMillis) {
        store.updateDonutStatus(vkUserId, true, nowMillis);
        store.saveIfDirty();
        sendAsync(vkUserId, "💛 Спасибо за поддержку сообщества в VK Donut! "
                + "Ваш значок [VK Donut] в /labels и бонус к длительности покупок активированы.");
    }

    @Override
    public void onDonutEnded(long vkUserId, long nowMillis) {
        store.updateDonutStatus(vkUserId, false, nowMillis);
        store.saveIfDirty();
    }

    @Override
    public void onWallPost(VkApiClient.WallPost post) {
        VkConfig cfg = plugin.config();
        if (!cfg.enabled() || !cfg.announceWallPosts() || !eligibleWallPost(post, cfg)) {
            return;
        }
        if (!store.advanceLastAnnouncedPostId(post.id())) {
            return;
        }
        store.saveIfDirty();
        String preview = VkApiClient.formatWallPreview(post.text(), cfg.wallPreviewLength());
        String postUrl = cfg.wallPostUrl(post.id());
        Runnable broadcastTask = () -> {
            Component line = plugin.messages().get("vk.wall-broadcast",
                    "preview", preview,
                    "post_url", postUrl);
            ChrdkRegisterApi api = plugin.coreApi();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (api != null && !api.isAuthenticated(online)) continue;
                if (!store.receivesGroupChat(online.getName())) continue;
                online.sendMessage(line);
            }
            Bukkit.getConsoleSender().sendMessage(line);
        };
        if (Bukkit.isPrimaryThread()) {
            broadcastTask.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, broadcastTask);
        }
    }

    public static boolean eligibleWallPost(VkApiClient.WallPost post, VkConfig cfg) {
        if (post == null || post.id() <= 0L) {
            return false;
        }
        if (cfg.wallIgnorePinned() && post.pinned()) {
            return false;
        }
        if (cfg.wallIgnoreAds() && post.markedAsAds()) {
            return false;
        }
        if (cfg.wallIgnoreReposts() && post.repost()) {
            return false;
        }
        return true;
    }

    /* ---------------- фоновая сверка подписок, Donut и стены ---------------- */

    public void triggerManualSync(Consumer<SyncReport> onCompleteMainThread) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> runSyncSafely(onCompleteMainThread));
    }

    private void runSyncSafely(Consumer<SyncReport> callback) {
        VkApiClient client = this.apiClient;
        VkConfig cfg = plugin.config();
        if (client == null || !cfg.apiConfigured()) {
            deliverSyncCallback(callback, new SyncReport(0, 0));
            return;
        }
        long now = System.currentTimeMillis();
        Set<Long> donutIds = null;
        try {
            donutIds = client.fetchDonutMemberIds(cfg.groupId());
        } catch (Exception exception) {
            plugin.getLogger().fine("[CHRDK_VK] Не удалось получить список VK Donut: " + exception.getMessage());
        }

        List<VkStore.LinkSnapshot> links = store.allLinks();
        int checked = 0;
        for (VkStore.LinkSnapshot link : links) {
            checked++;
            try {
                boolean member = client.isGroupMember(cfg.groupId(), link.vkUserId());
                store.updateMemberStatus(link.vkUserId(), member);
                if (member) {
                    grantEligibleBoostsOnMainThread(link.playerName(), link.vkUserId(), true);
                }
            } catch (Exception exception) {
                plugin.getLogger().fine("[CHRDK_VK] Ошибка groups.isMember для id"
                        + link.vkUserId() + ": " + exception.getMessage());
            }
            if (donutIds != null) {
                store.updateDonutStatus(link.vkUserId(), donutIds.contains(link.vkUserId()), now);
            }
        }
        store.saveIfDirty();
        int activeDonuts = store.activeDonutCount(now, cfg.donutGraceHours());
        deliverSyncCallback(callback, new SyncReport(checked, activeDonuts));
    }

    private void deliverSyncCallback(Consumer<SyncReport> callback, SyncReport report) {
        if (callback == null || !plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> callback.accept(report));
    }

    private boolean queryMembershipSafe(long vkUserId) {
        VkApiClient client = this.apiClient;
        VkConfig cfg = plugin.config();
        if (client == null || !cfg.apiConfigured()) {
            return false;
        }
        try {
            return client.isGroupMember(cfg.groupId(), vkUserId);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void grantEligibleBoostsOnMainThread(String playerName, long vkUserId, boolean isMember) {
        if (!plugin.isEnabled()) {
            return;
        }
        Runnable rewardTask = () -> {
            ChrdkRegisterApi api = plugin.coreApi();
            VkConfig cfg = plugin.config();
            if (api == null || !cfg.enabled()) {
                return;
            }
            boolean grantedAny = false;
            int linkHours = cfg.linkBoostHours();
            if (linkHours > 0 && store.canClaimLinkBoost(playerName, vkUserId)) {
                api.grantRewardBoostHours(playerName, linkHours, "vk-link");
                if (api.checkpointGameplayRewards()) {
                    store.markLinkBoostClaimed(playerName, vkUserId);
                    grantedAny = true;
                    Player online = Bukkit.getPlayerExact(playerName);
                    if (online != null) {
                        plugin.messages().send(online, "vk.boost-link-granted",
                                "hours", Integer.toString(linkHours));
                    }
                }
            }
            int memberHours = cfg.memberBoostHours();
            if (isMember && memberHours > 0 && store.canClaimMemberBoost(playerName, vkUserId)) {
                api.grantRewardBoostHours(playerName, memberHours, "vk-member");
                if (api.checkpointGameplayRewards()) {
                    store.markMemberBoostClaimed(playerName, vkUserId);
                    grantedAny = true;
                    Player online = Bukkit.getPlayerExact(playerName);
                    if (online != null) {
                        plugin.messages().send(online, "vk.boost-member-granted",
                                "hours", Integer.toString(memberHours));
                    }
                }
            }
            if (grantedAny) {
                store.saveNow();
            }
        };
        if (Bukkit.isPrimaryThread()) {
            rewardTask.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, rewardTask);
        }
    }

    private void notifyPlayerLinkedInGame(String playerName, long vkUserId) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayerExact(playerName);
            if (online != null) {
                plugin.messages().send(online, "vk.linked-ingame",
                        "vk_id", Long.toString(vkUserId));
            }
        });
    }

    @Override
    public void sendVkCode(long vkUserId, String playerName, String code, String purpose, Consumer<Boolean> ack) {
        VkApiClient client = apiClient;
        if (client == null || !plugin.config().enabled() || vkUserId <= 0 || vkUserId >= VkConfig.MIN_CONVERSATION_PEER_ID) { ack.accept(false); return; }
        String action = switch (purpose) {
            case "LOGIN" -> "вход в игру";
            case "ENABLE" -> "включение ВК-2FA";
            case "DISABLE" -> "отключение ВК-2FA";
            default -> "";
        };
        if (action.isEmpty()) { ack.accept(false); return; }
        try {
            securityExecutor.execute(() -> {
                boolean ok = false;
                try {
                    String text = plugin.messages().plain("bot.factor-code", "code", code, "action", action, "player", playerName);
                    ok = client.sendMessage(vkUserId, text);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch (Exception ignored) { /* Код и ответы доставки никогда не логируются. */ }
                ack.accept(ok);
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) { ack.accept(false); }
    }

    private long onboardingPeer(VkConfig cfg) {
        return cfg.onboardingPeerId() > 0 ? cfg.onboardingPeerId() : effectiveGroupChatPeerId();
    }
    private record OnboardingPlayer(Player player, long vkId) { }

    /** Только напоминание: ни kick, ни ограничения игровых действий здесь нет. */
    private void remindOnboarding() {
        VkConfig cfg = plugin.config();
        ChrdkRegisterApi core = plugin.coreApi();
        VkApiClient client = apiClient;
        if (!cfg.enabled() || !cfg.onboardingEnabled() || core == null || client == null || onboardingBusy.get()) return;
        long now = System.currentTimeMillis();
        List<OnboardingPlayer> due = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!core.isAuthenticated(player) || onboardingDue.getOrDefault(player.getUniqueId(), 0L) > now) continue;
            onboardingDue.put(player.getUniqueId(), now + cfg.onboardingIntervalSeconds() * 1000L);
            due.add(new OnboardingPlayer(player, store.byPlayer(player.getName()).map(VkStore.LinkSnapshot::vkUserId).orElse(0L)));
            if (due.size() >= 20) break;
        }
        if (due.isEmpty() || !onboardingBusy.compareAndSet(false, true)) return;
        long peer = onboardingPeer(cfg);
        try {
            outboundExecutor.execute(() -> {
                try {
                    Set<Long> members = null;
                    if (peer >= VkConfig.MIN_CONVERSATION_PEER_ID) {
                        try { members = client.fetchConversationMemberIds(cfg.groupId(), peer); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                        catch (Exception ignored) { /* Не выдаём ошибку за отсутствие участника. */ }
                    }
                    for (OnboardingPlayer entry : due) {
                        Boolean group = null;
                        if (entry.vkId() > 0) {
                            try { group = client.isGroupMember(cfg.groupId(), entry.vkId()); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                            catch (Exception ignored) { }
                        }
                        Boolean inGroup = group;
                        Boolean inChat = entry.vkId() == 0 ? false : members == null ? null : members.contains(entry.vkId());
                        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> {
                            Player player = entry.player();
                            if (!player.isOnline() || Bukkit.getPlayer(player.getUniqueId()) != player
                                    || plugin.config() != cfg || apiClient != client || onboardingPeer(cfg) != peer
                                    || !core.isAuthenticated(player)) return;
                            long linkedNow = store.byPlayer(player.getName()).map(VkStore.LinkSnapshot::vkUserId).orElse(0L);
                            if (linkedNow != entry.vkId()) return;
                            boolean complete = entry.vkId() > 0 && Boolean.TRUE.equals(inGroup) && Boolean.TRUE.equals(inChat);
                            if (complete) { onboardingCompleted.add(player.getUniqueId()); return; }
                            if (!Boolean.FALSE.equals(inGroup) && !Boolean.FALSE.equals(inChat) && onboardingCompleted.contains(player.getUniqueId())) return;
                            onboardingCompleted.remove(player.getUniqueId());
                            plugin.messages().send(player, "vk.onboarding-header");
                            if (entry.vkId() == 0) plugin.messages().send(player, "vk.onboarding-link");
                            if (entry.vkId() == 0 || !Boolean.TRUE.equals(inGroup)) plugin.messages().send(player,
                                    inGroup == null && entry.vkId() > 0 ? "vk.onboarding-unknown" : "vk.onboarding-group", "url", cfg.communityUrl());
                            if (!Boolean.TRUE.equals(inChat)) {
                                if (inChat == null && entry.vkId() > 0) plugin.messages().send(player, "vk.onboarding-unknown");
                                plugin.messages().send(player, cfg.onboardingInviteUrl().isEmpty() ? "vk.onboarding-chat-no-url" : "vk.onboarding-chat",
                                        "url", cfg.onboardingInviteUrl());
                            }
                        });
                    }
                } finally { onboardingBusy.set(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) { onboardingBusy.set(false); }
    }

    private void sendAsync(long peerId, String text) {
        sendAsync(peerId, text, null);
    }

    private void sendAsync(long peerId, String text, String keyboardJson) {
        VkApiClient client = this.apiClient;
        if (client == null || peerId <= 0L || text == null || text.isBlank()) {
            return;
        }
        outboundExecutor.execute(() -> {
            try {
                client.sendMessage(peerId, text, keyboardJson);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                plugin.getLogger().fine("[CHRDK_VK] Не удалось доставить сообщение peer="
                        + peerId + ": " + exception.getMessage());
            }
        });
    }
}
