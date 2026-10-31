package ru.cherdakmd.chrdkvk;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Центральная служба {@code CHRDK_VK}: связывает хранилище привязок, одноразовые коды,
 * клиента VK API, фоновый Long Poll и мосты {@link ChrdkRegisterApi}.
 */
public final class VkService implements VkBotRouter.Backend, ChrdkRegisterApi.SecurityListener {

    public static final String PROVIDER_ID = "vk";
    private static final DateTimeFormatter MSK_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Moscow"));

    public record ServerPulse(int online, int maxPlayers, double tps) {
    }

    public record SyncReport(int checkedLinks, int activeDonuts) {
    }

    private final VkPlugin plugin;
    private final VkStore store;
    private final VkLinkCodeManager codeManager = new VkLinkCodeManager();
    private final VkBotRouter router;
    private final ExecutorService outboundExecutor;

    private volatile VkApiClient apiClient;
    private volatile VkLongPollWorker longPollWorker;
    private volatile ServerPulse serverPulse = new ServerPulse(0, 0, 20.0);
    private BukkitTask pulseTask;
    private BukkitTask syncTask;
    private BukkitTask wallTask;

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
        this.pulseTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshServerPulse, 1L, 100L);
    }

    /** Пересобирает сетевых клиентов и задачи опроса после старта или {@code /vkadmin reload}. */
    public synchronized void applyRuntimeConfig() {
        stopNetworkWorkers();
        VkConfig cfg = plugin.config();
        if (!cfg.apiConfigured()) {
            this.apiClient = null;
            return;
        }
        VkApiClient client = new VkApiClient(cfg);
        this.apiClient = client;

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

        if (cfg.announceWallPosts() && cfg.wallPollMinutes() > 0) {
            long wallTicks = cfg.wallPollMinutes() * 60L * 20L;
            this.wallTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin, this::pollWallSafely, 160L, wallTicks);
        }
    }

    public synchronized void shutdown() {
        if (pulseTask != null) {
            pulseTask.cancel();
            pulseTask = null;
        }
        stopNetworkWorkers();
        unregisterCoreBridges();
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
        if (wallTask != null) {
            wallTask.cancel();
            wallTask = null;
        }
        VkLongPollWorker worker = this.longPollWorker;
        this.longPollWorker = null;
        if (worker != null) {
            worker.close();
        }
    }

    private void refreshServerPulse() {
        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();
        double[] tpsArr = Bukkit.getTPS();
        double tps = tpsArr != null && tpsArr.length > 0 ? Math.min(20.0, tpsArr[0]) : 20.0;
        this.serverPulse = new ServerPulse(online, max, tps);
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
            api.registerNotificationListener(PROVIDER_ID, this::forwardAdminNotification);
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
            api.unregisterNotificationListener(PROVIDER_ID);
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
        Messages msg = plugin.messages();
        Optional<VkStore.LinkSnapshot> opt = store.byPlayer(context.playerName());
        if (opt.isEmpty()) {
            if (!context.self()) {
                return null;
            }
            return new ChrdkRegisterApi.ProfileSection(
                    msg.raw("vk.profile-title"),
                    Material.LAPIS_LAZULI,
                    List.of(msg.get("vk.profile-unlinked"))
            );
        }
        VkStore.LinkSnapshot link = opt.get();
        boolean donutActive = store.donutPerkActive(
                context.playerName(), System.currentTimeMillis(), plugin.config().donutGraceHours());
        List<Component> lines = new ArrayList<>(2);
        if (context.self()) {
            lines.add(msg.get("vk.profile-linked",
                    "vk_id", Long.toString(link.vkUserId()),
                    "member", link.member() ? msg.mini("vk.state-yes") : msg.mini("vk.state-no")));
        }
        lines.add(msg.get("vk.profile-donut",
                "donut", donutActive ? msg.mini("vk.state-yes") : msg.mini("vk.state-no")));
        return new ChrdkRegisterApi.ProfileSection(
                msg.raw("vk.profile-title"),
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
            String text = "✅ Вход на сервер CHRDK\n"
                    + "Игрок: " + link.playerName() + "\n"
                    + "Способ: " + mode + "\n"
                    + "IP: " + (maskedIp == null || maskedIp.isBlank() ? "скрыт" : maskedIp) + "\n"
                    + "Если это были не вы — немедленно отправьте /заморозить.";
            sendAsync(link.vkUserId(), text);
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
            String text = "⚠ Попытка подбора пароля к аккаунту " + link.playerName() + "!\n"
                    + "Неудачных попыток: " + failures + "\n"
                    + "IP: " + (maskedIp == null || maskedIp.isBlank() ? "скрыт" : maskedIp) + "\n"
                    + status + "\n"
                    + "Вы можете временно заблокировать вход командой /заморозить.";
            sendAsync(link.vkUserId(), text);
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
            String text = "⏳ Срок ранга на аккаунте " + link.playerName()
                    + " истекает примерно через " + hours + " ч. (до " + until + ").";
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
                        + "• Уведомления о входах и переборе пароля активны.\n"
                        + "• Экстренные команды: /выход, /заморозить, /разморозить, /профиль.";
            }
        };
    }

    @Override
    public String formatServerOnline() {
        ServerPulse pulse = this.serverPulse;
        return String.format(Locale.ROOT,
                "🌐 Сервер CHRDK в сети\nОнлайн: %d / %d\nTPS: %.1f",
                pulse.online(), pulse.maxPlayers(), pulse.tps());
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
        sendAsync(peerId, text);
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
            Bukkit.getServer().broadcast(line);
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
        pollWallSafely();
        int activeDonuts = store.activeDonutCount(now, cfg.donutGraceHours());
        deliverSyncCallback(callback, new SyncReport(checked, activeDonuts));
    }

    private void deliverSyncCallback(Consumer<SyncReport> callback, SyncReport report) {
        if (callback == null || !plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> callback.accept(report));
    }

    private void pollWallSafely() {
        VkApiClient client = this.apiClient;
        VkConfig cfg = plugin.config();
        if (client == null || !cfg.apiConfigured() || !cfg.announceWallPosts()) {
            return;
        }
        try {
            List<VkApiClient.WallPost> posts = client.fetchWallPosts(cfg.groupId(), 5);
            if (posts.isEmpty()) {
                return;
            }
            // При самом первом запуске фиксируем текущую верхушку стены без массовой рассылки старых записей.
            if (store.lastAnnouncedPostId() == 0L) {
                long newest = posts.stream().mapToLong(VkApiClient.WallPost::id).max().orElse(0L);
                if (newest > 0L) {
                    store.advanceLastAnnouncedPostId(newest);
                    store.saveIfDirty();
                }
                return;
            }
            posts.stream()
                    .filter(p -> p.id() > store.lastAnnouncedPostId())
                    .sorted(Comparator.comparingLong(VkApiClient.WallPost::id))
                    .forEach(this::onWallPost);
        } catch (Exception exception) {
            plugin.getLogger().fine("[CHRDK_VK] Резервный опрос wall.get не выполнен: " + exception.getMessage());
        }
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

    private void sendAsync(long peerId, String text) {
        VkApiClient client = this.apiClient;
        if (client == null || peerId <= 0L || text == null || text.isBlank()) {
            return;
        }
        outboundExecutor.execute(() -> {
            try {
                client.sendMessage(peerId, text);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                plugin.getLogger().fine("[CHRDK_VK] Не удалось доставить сообщение peer="
                        + peerId + ": " + exception.getMessage());
            }
        });
    }
}
