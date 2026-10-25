package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.event.Event;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.api.events.PassExpiredEvent;
import ru.cherdakmd.chrdkregister.api.events.PassGrantedEvent;
import ru.cherdakmd.chrdkregister.api.events.PassRevokedEvent;
import ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Coordinates DonatePay pass verification, manual admin pass grants, password registration/login, and TOTP 2FA state.
 */
public final class AccessManager {
    public static final Pattern MINECRAFT_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'МСК'").withZone(ZoneId.of("Europe/Moscow"));
    private static final long CHAT_REMINDER_INTERVAL_MILLIS = 15_000L;

    public enum PlayerStage {
        NO_PASS,
        NEEDS_REGISTER,
        NEEDS_LOGIN,
        NEEDS_2FA,
        /** Пароль верный, но обязательная 2FA ещё не привязана: в игру не пускаем. */
        NEEDS_2FA_SETUP,
        AUTHENTICATED
    }

    private final RegisterPlugin plugin;
    private final RegistrationStore store;
    private final DonatePayClient donatePay;
    private final Logger logger;

    private volatile PluginConfig config;
    private final Set<UUID> authenticatedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> passwordVerifiedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> pendingTotpSecrets = new ConcurrentHashMap<>();
    private final Map<UUID, Location> freezeLocations = new ConcurrentHashMap<>();
    private final Map<UUID, CaptchaChallenge> pendingCaptchas = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> captchaClearedFailures = new ConcurrentHashMap<>();
    private final Random captchaRandom = new Random();
    private final Map<UUID, Long> lastChatReminderAt = new ConcurrentHashMap<>();
    private final Map<UUID, String> authSource = new ConcurrentHashMap<>();
    private final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> expiryBars = new ConcurrentHashMap<>();
    /** Состояние опроса одного донатного провайдера (1.4.35; видно тестам). */
    /** Максимум страниц по 100 транзакций за один цикл опроса (предохранитель от потока донатов). */
    static final int MAX_PAGES_PER_POLL = 10;

    static final class ProviderPulse {
        final DonationProvider provider;
        final AtomicInteger failStreak = new AtomicInteger(0);
        final AtomicLong attempts = new AtomicLong();
        final AtomicLong successes = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        final AtomicLong processed = new AtomicLong();
        /** Сумма обработанных за сессию донатов, копейки (для /pass stats). */
        final AtomicLong processedKopecks = new AtomicLong();
        final LatencySeries latency = new LatencySeries(20);
        volatile long lastSuccessAtMillis;
        volatile String lastError;
        volatile boolean outageAlerted;
        volatile long lastAlertAtMillis;

        ProviderPulse(DonationProvider provider) {
            this.provider = provider;
        }
    }
    private final AtomicBoolean donationPollRunning = new AtomicBoolean(false);
    /** Провайдеры донатов, в фиксированном порядке (donatepay, easydonate). */
    private final java.util.Map<String, ProviderPulse> donationPulses = new java.util.LinkedHashMap<>();
    private volatile long donateLastStalePendingNotifyAtMillis;

    private final AuditLog audit;
    private final NotificationService notifications;
    private final AuthThrottle throttle;
    private final DonationService donations;
    private final LogMasker logMasker;
    private final AuthDialogs authDialogs;
    private final TotpQrMapService totpQrMaps;
    private volatile Messages messages;

    private BukkitTask donatePollTask;
    private BukkitTask reminderTask;
    private BukkitTask flushTask;
    private BukkitTask housekeepingTask;
    private BukkitTask donateHealthTask;
    private BukkitTask boostyRecheckTask;
    private final BoostyManager boostyManager;
    private final LuckPermsBootstrap luckPermsBootstrap;
    private volatile long lastBackupAtMillis;
    private volatile long lastPurgeAtMillis;
    private volatile long serverStartedAtMillis;
    private volatile long lastDonatePayWarningMillis;

    public AccessManager(
            RegisterPlugin plugin,
            PluginConfig config,
            RegistrationStore store,
            DonatePayClient donatePay,
            Messages messages,
            Logger logger
    ) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.store = store;
        this.donatePay = donatePay;
        this.logger = logger;
        donationPulses.put(donatePay.key(), new ProviderPulse(donatePay));
        donationPulses.put(EasyDonateClient.SOURCE, new ProviderPulse(new EasyDonateClient(config)));
        this.audit = new AuditLog(plugin.getDataFolder().toPath(), logger, config.timeZone());
        this.notifications = new NotificationService(this::config, logger);
        this.throttle = new AuthThrottle(store, this::config);
        this.donations = new DonationService(store, this::config, audit, notifications, logger,
                messages, this::onPassGranted);
        this.donations.setPendingNotifier(this::onDonationPending);
        this.luckPermsBootstrap = new LuckPermsBootstrap(plugin.externalHooks(), this::config);
        this.logMasker = new LogMasker(logger);
        this.authDialogs = createAuthDialogs(plugin);
        this.totpQrMaps = new TotpQrMapService(this,
                new NamespacedKey(plugin, "totp_qr_card"), logger);
        this.boostyManager = new BoostyManager(plugin, store, config);
    }

    private AuthDialogs createAuthDialogs(JavaPlugin javaPlugin) {
        try {
            return new AuthDialogs(javaPlugin, this, () -> this.config, messages, logger);
        } catch (Throwable throwable) {
            logger.warning("Нативный ввод пароля диалогами Paper недоступен: " + throwable.getMessage());
            return null;
        }
    }

    /**
     * Shows the Paper dialog matching the current authentication stage, when dialogs are enabled.
     * Silently does nothing on server builds without the dialog API.
     */
    private void showAuthDialog(Player player, PlayerStage stage) {
        AuthDialogs dialogs = this.authDialogs;
        if (dialogs == null || player == null || !player.isOnline() || !config.authDialogs()) {
            return;
        }
        switch (stage) {
            case NEEDS_REGISTER -> dialogs.showRegister(player);
            case NEEDS_2FA -> dialogs.showTwoFactor(player);
            case NEEDS_LOGIN -> dialogs.showLogin(player);
            default -> {
                // no password input is required on the other stages
            }
        }
    }

    /** Opens the dialog of the current stage; used when {@code /login} or {@code /2fa} is run without arguments. */
    public void showAuthDialogNow(Player player) {
        long now = System.currentTimeMillis();
        showAuthDialog(player, getStage(player.getUniqueId(), player.getName(), now));
    }

    /** Тексты плагина из {@code messages.yml}. */
    public Messages messages() {
        return messages;
    }

    /** Заменяет тексты (используется при {@code /pass reload}). */
    public void setMessages(Messages messages) {
        this.messages = messages;
        if (donations != null) this.donations.setMessages(messages);
        if (authDialogs != null) this.authDialogs.setMessages(messages);
    }

    /** Собирает подсказки по надёжности пароля из messages.yml в одну строку. */
    public String describeHints(String password) {
        List<String> parts = new ArrayList<>();
        for (String key : PasswordPolicy.hintKeys(password)) {
            String text = messages.plain(key);
            if (!text.isBlank()) parts.add(text);
        }
        return String.join("; ", parts);
    }

    /** Access to the DonatePay journal and the pending-payment workflow. */
    public DonationService donations() {
        return donations;
    }

    /** Access to the audit trail (used by chat moderation and admin commands). */
    public AuditLog audit() {
        return audit;
    }

    /** Access to Discord notifications. */
    public NotificationService notifications() {
        return notifications;
    }

    /** Brute-force protection helper. */
    public AuthThrottle throttle() {
        return throttle;
    }

    /** Enables log masking for auth commands when configured. */
    public void applyLogMasking() {
        if (config.maskCommandsInLogs()) {
            logMasker.install();
        } else {
            logMasker.uninstall();
        }
    }

    public PluginConfig config() {
        return config;
    }

    public RegistrationStore store() {
        return store;
    }

    /** Number of players currently online (PlaceholderAPI: {@code %chrdk_online_total%}). */
    public int countOnlinePlayers() {
        return plugin.getServer().getOnlinePlayers().size();
    }

    /** Number of online players that completed authentication (PlaceholderAPI: {@code %chrdk_online_authed%}). */
    public int countAuthenticatedOnline() {
        int count = 0;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (authenticatedPlayers.contains(player.getUniqueId())) count++;
        }
        return count;
    }

    public synchronized void start() {
        stopTasks();
        this.serverStartedAtMillis = System.currentTimeMillis();
        long now = System.currentTimeMillis();
        int migrated = store.migrateRanks(now);
        if (migrated > 0) {
            logger.info("Ранги: миграция с проходки затронула " + migrated
                    + " аккаунт(ов) — платившим выдан постоянный ранг, триалу — первая ступень");
        }

        long pollTicks = Math.max(20L * 15L, config.donatePollSeconds() * 20L);
        this.donatePollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, () -> pollDonationsSafely(), 20L * 5L, pollTicks);

        this.donateHealthTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::donateHeartbeat, 20L * config.donateHealthSeconds(),
                20L * config.donateHealthSeconds());

        long reminderTicks = Math.max(40L, config.reminderSeconds() * 20L);
        this.reminderTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::tickOnlinePlayers, 40L, reminderTicks);

        this.flushTask = Bukkit.getScheduler().runTaskTimer(
                plugin, store::flush, 20L * 5L, 20L * config.storageSaveDebounceSeconds());
        this.housekeepingTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::runHousekeeping, 20L * 30L, 20L * 60L * 30L);

        if (!config.trademc().enabled()) {
            long boostyTicks = Math.max(20L * 600L, config.boosty().recheckHours() * 20L * 3600L);
            this.boostyRecheckTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin, this::maybeRecheckBoosty, 20L * 600L, boostyTicks);
        }

        if (config.storageBackupOnStart()) {
            store.createBackup("start", config.storageBackupCount());
        }
        runHousekeeping();
        applyLogMasking();

        for (Player online : Bukkit.getOnlinePlayers()) {
            onPlayerJoin(online);
        }

        if (config.trademc().enabled()) {
            logger.info("TradeMC-режим: донат ТОЛЬКО через магазин TradeMC (RCON-команда "
                    + "'/pass shoppay <ник> <сумма_руб>' от панели). Опросы DonatePay/EasyDonate "
                    + "и Boosty-подписки выключены единым переключателем trademc.enabled.");
        } else if (!config.donatePayReady()) {
            logger.warning("DonatePay API token is not set in plugins/CHRDK_Core/config.yml. "
                    + "Automatic pass activation via DonatePay is paused until configured.");
        } else {
            logger.info("CHRDK_Core active: minimum donation " + config.minimumDonationRubles()
                    + " RUB, pass duration " + config.grantDays() + " days.");
            verifyDonatePayToken("сервер запущен");
        }
        if (!config.passEnabled()) {
            logger.warning("pass.enabled: false — донат-стена проходки ВЫКЛЮЧЕНА. "
                    + "Вход контролирует только очередь (CHRDK_Queue) и лимиты сервера.");
        }
        // Одноразовая подсказка: LuckPerms есть, а группа проходки (integrations.pass-group) не создана
        if (config.passGroup() != null && !config.passGroup().isBlank()) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () ->
                    luckPermsBootstrap.passGroupMissing().thenAccept(missing -> {
                        if (Boolean.TRUE.equals(missing)) {
                            logger.warning("LuckPerms: группа проходки '" + config.passGroup()
                                    + "' не создана. Авто-настройка схемы групп: "
                                    + "/pass luckperms setup");
                        }
                    }), 20L * 60L);
        }
    }

    /** Авто-настройка групп LuckPerms по схеме CHRDK (default → pass → admin). */
    public LuckPermsBootstrap luckPermsBootstrap() {
        return luckPermsBootstrap;
    }

    /**
     * Разовая проверка DonatePay-токена через официальный GET /api/v1/user:
     * пишем в консоль имя кошелька и баланс либо сразу сигнализируем о битом
     * токене — иначе невалидный конфиг проявляется только тишиной автоначислений.
     */
    private void verifyDonatePayToken(String reason) {
        if (!config.donatePayReady() || config.trademc().enabled()) {
            return;
        }
        PluginConfig snapshot = config;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                DonatePayClient.UserInfo user = donatePay.verifyToken();
                if (snapshot.donatePayShowBalance()) {
                    logger.info("DonatePay подключён (" + reason + "): кошелёк '" + user.name()
                            + "', баланс " + user.balanceRubles() + " ₽; минимальный донат "
                            + snapshot.minimumDonationRubles() + " ₽.");
                } else {
                    logger.info("DonatePay подключён (" + reason + "): токен валиден"
                            + " (имя и баланс скрыты — donatepay.show-balance: false);"
                            + " минимальный донат " + snapshot.minimumDonationRubles() + " ₽.");
                }
            } catch (IOException exception) {
                logger.warning("DonatePay: проверка токена НЕ прошла (" + reason + "): "
                        + exception.getMessage()
                        + ". Автономная выдача проходов может молчать — проверьте donatepay.api-token "
                        + "в plugins/CHRDK_Core/config.yml.");
            }
        });
    }

    /**
     * donation-actions: при успешной оплате ≥ порога выполняем команды от консоли.
     * Вызывается ТОЛЬКО на боевом потоке новых платежей и из /pass testpay —
     * не в backfill курсора и не при retry старых pending (историю не флудим).
     */
    private void runDonateActions(DonatePayClient.Transaction transaction,
                                  DonationService.Outcome outcome) {
        try {
            if (transaction == null || !transaction.isSuccessfulDonation()) {
                return;
            }
            List<DonateAction> actions = config.donateActions();
            if (actions.isEmpty()) {
                return;
            }
            long rubles = DonateAction.rublesOf(transaction.amount());
            List<DonateAction> matched = actions.stream()
                    .filter(action -> rubles >= action.minSumRubles())
                    .toList();
            if (matched.isEmpty()) {
                return;
            }
            String username = DonateAction.sanitize(transaction.donorName());
            String player = outcome != null && outcome.playerName() != null
                    && !outcome.playerName().isBlank()
                    ? DonateAction.sanitize(outcome.playerName()) : username;
            String sum = Long.toString(rubles);
            String message = DonateAction.sanitize(transaction.comment());
            long delayTicks = 0;
            for (DonateAction action : matched) {
                String command = action.render(username, player, sum, message);
                if (command.isEmpty()) {
                    continue;
                }
                logger.info("donation-action (" + username + ", " + sum + " ₽): /" + command);
                // между командами 2 секунды (аналог cmdCooldown) — консоль не захлёстываем
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command),
                        delayTicks);
                delayTicks += 40L;
            }
        } catch (Exception exception) {
            logger.warning("donation-actions: команда не выполнена — " + exception.getMessage());
        }
    }

    public synchronized void reload(PluginConfig newConfig) {
        this.config = newConfig;
        for (ProviderPulse pulse : donationPulses.values()) {
            pulse.provider.updateConfig(newConfig);
        }
        boostyManager.updateConfig(newConfig);
        stopTasks();
        verifyDonatePayToken("конфиг перезагружен");

        long pollTicks = Math.max(20L * 15L, newConfig.donatePollSeconds() * 20L);
        this.donatePollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, () -> pollDonationsSafely(), 20L * 2L, pollTicks);

        this.donateHealthTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::donateHeartbeat, 20L * newConfig.donateHealthSeconds(),
                20L * newConfig.donateHealthSeconds());

        long reminderTicks = Math.max(40L, newConfig.reminderSeconds() * 20L);
        this.reminderTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::tickOnlinePlayers, 40L, reminderTicks);

        this.flushTask = Bukkit.getScheduler().runTaskTimer(
                plugin, store::flush, 20L * 5L, 20L * newConfig.storageSaveDebounceSeconds());
        this.housekeepingTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::runHousekeeping, 20L * 30L, 20L * 60L * 30L);

        if (!newConfig.trademc().enabled()) {
            long boostyTicksReload = Math.max(20L * 600L, newConfig.boosty().recheckHours() * 20L * 3600L);
            this.boostyRecheckTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin, this::maybeRecheckBoosty, 20L * 600L, boostyTicksReload);
        }

        applyLogMasking();
        audit.log(AuditLog.CONFIG_RELOAD, "server", "", "",
                AuditLog.details("minimumRubles", newConfig.minimumDonationRubles(),
                        "grantDays", newConfig.grantDays(), "sessionMinutes", newConfig.sessionMinutes()));
    }

    public synchronized void shutdown() {
        stopTasks();
        long now = System.currentTimeMillis();
        for (UUID uuid : authenticatedPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                store.touchSession(player.getName(), uuid, getPlayerIp(player), now);
            }
        }
        authenticatedPlayers.clear();
        passwordVerifiedPlayers.clear();
        pendingTotpSecrets.clear();
        freezeLocations.clear();
        lastChatReminderAt.clear();
        authSource.clear();
        joinedAt.clear();
        for (Map.Entry<UUID, BossBar> entry : expiryBars.entrySet()) {
            Player online = Bukkit.getPlayer(entry.getKey());
            if (online != null) {
                online.hideBossBar(entry.getValue());
            }
        }
        expiryBars.clear();
        store.flushBlocking();
        notifications.shutdown();
        logger.info("CHRDK_Core storage flushed and notifications stopped.");
    }

    private void stopTasks() {
        if (donatePollTask != null) {
            donatePollTask.cancel();
            donatePollTask = null;
        }
        if (reminderTask != null) {
            reminderTask.cancel();
            reminderTask = null;
        }
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        if (housekeepingTask != null) {
            housekeepingTask.cancel();
            housekeepingTask = null;
        }
        if (boostyRecheckTask != null) {
            boostyRecheckTask.cancel();
            boostyRecheckTask = null;
        }
        if (donateHealthTask != null) {
            donateHealthTask.cancel();
            donateHealthTask = null;
        }

    }

    /** Periodic maintenance: storage flush, backups, audit rotation and counter pruning. */
    private void runHousekeeping() {
        long now = System.currentTimeMillis();
        store.flush();
        try {
            store.pruneAttempts(now - TimeUnit.DAYS.toMillis(7));
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Could not prune auth counters", exception);
        }
        startOfRunBackup(now);
    }

    private void startOfRunBackup(long now) {
        long interval = TimeUnit.HOURS.toMillis(6);
        if (lastBackupAtMillis == 0 || now - lastBackupAtMillis >= interval) {
            lastBackupAtMillis = now;
            store.createBackup("auto", config.storageBackupCount());
            audit.log(AuditLog.STORAGE_BACKUP, "server", "", "",
                    AuditLog.details("reason", "auto", "keep", config.storageBackupCount()));
        }
        long purgeInterval = TimeUnit.DAYS.toMillis(1);
        if (lastPurgeAtMillis == 0 || now - lastPurgeAtMillis >= purgeInterval) {
            lastPurgeAtMillis = now;
            audit.purge(config.auditKeepDays(), config.chat().chatLogKeepDays());
        }
    }

    public PlayerStage getStage(Player player) {
        return getStage(player.getUniqueId(), player.getName(), System.currentTimeMillis());
    }

    public PlayerStage getStage(UUID uuid, String playerName, long nowMillis) {
        // 1.4.167: вход свободный. Ворота проходки работают только при
        // pass.enabled: true — тогда стадия NO_PASS и подсказки остаются как были.
        if (config.passEnabled() && !store.hasActivePass(playerName, nowMillis)) {
            authenticatedPlayers.remove(uuid);
            passwordVerifiedPlayers.remove(uuid);
            if (config.trialEnabled() && store.isTrialEligible(playerName, uuid)) return PlayerStage.NEEDS_REGISTER;
            return PlayerStage.NO_PASS;
        }
        if (!store.isRegistered(playerName)) {
            authenticatedPlayers.remove(uuid);
            passwordVerifiedPlayers.remove(uuid);
            return PlayerStage.NEEDS_REGISTER;
        }
        if (authenticatedPlayers.contains(uuid)) {
            return PlayerStage.AUTHENTICATED;
        }
        if (config.totpEnabled() && store.isTotpEnabled(playerName) && passwordVerifiedPlayers.contains(uuid)) {
            return PlayerStage.NEEDS_2FA;
        }
        if (requiresTotpEnrollment(playerName) && passwordVerifiedPlayers.contains(uuid)) {
            return PlayerStage.NEEDS_2FA_SETUP;
        }
        return PlayerStage.NEEDS_LOGIN;
    }

    /**
     * Нужна ли игроку привязка TOTP, чтобы попасть в игру: 2FA включена и обязательна
     * ({@code totp.required: true}), а аутентификатор у аккаунта ещё не настроен.
     */
    public boolean requiresTotpEnrollment(String playerName) {
        return config.totpEnabled() && config.totpRequired() && !store.isTotpEnabled(playerName);
    }

    public boolean isBlocked(Player player) {
        return getStage(player) != PlayerStage.AUTHENTICATED;
    }

    public boolean hasPendingTotpSetup(UUID uuid) {
        return pendingTotpSecrets.containsKey(uuid);
    }

    public Optional<String> getPendingTotpSecret(UUID uuid) {
        return Optional.ofNullable(pendingTotpSecrets.get(uuid));
    }

    public Location getFreezeLocation(Player player) {
        return freezeLocations.computeIfAbsent(player.getUniqueId(), ignored -> player.getLocation().clone());
    }

    public void updateFreezeLocation(Player player, Location location) {
        if (location != null) {
            freezeLocations.put(player.getUniqueId(), location.clone());
        }
    }

    public void onPlayerJoin(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        long now = System.currentTimeMillis();

        store.recordPlayerSeen(uuid, name);
        authenticatedPlayers.remove(uuid);
        passwordVerifiedPlayers.remove(uuid);
        pendingTotpSecrets.remove(uuid);
        freezeLocations.put(uuid, player.getLocation().clone());
        lastChatReminderAt.put(uuid, now);
        authSource.remove(uuid);
        joinedAt.put(uuid, now);
        hideExpiryBar(uuid);

        if (config.passEnabled() && !store.hasActivePass(name, now)
                && !(config.trialEnabled() && store.isTrialEligible(name, uuid))) {
            player.closeInventory();
            sendNoPassPrompt(player, true);
            return;
        }

        if (!store.isRegistered(name)) {
            player.closeInventory();
            sendRegisterPrompt(player, true);
            return;
        }

        long sessionDurationMillis = TimeUnit.MINUTES.toMillis(config.sessionMinutes());
        String ip = getPlayerIp(player);
        if (store.hasValidIpSession(name, uuid, ip, now, sessionDurationMillis, config.sessionRequireSameUuid())) {
            store.touchSession(name, uuid, ip, now);
            player.clearTitle();
            // Security first: a stored IP session restores the password step only, 2FA is still
            // required. Обязательная 2FA (totp.required) не пропускается сессией никогда, а если
            // аутентификатор ещё не привязан — игрок проходит привязку до входа в игру.
            boolean totpEnrolled = config.totpEnabled() && store.isTotpEnabled(name);
            boolean secondStepRequired = totpEnrolled
                    ? (!config.sessionSkips2fa() || config.totpRequired())
                    : requiresTotpEnrollment(name);
            if (secondStepRequired) {
                passwordVerifiedPlayers.add(uuid);
                if (totpEnrolled) {
                    authSource.put(uuid, "session+2fa");
                    audit.log(AuditLog.SESSION_RESTORE, name, name, ip,
                            AuditLog.details("twoFactorRequired", true));
                    sendTwoFactorPrompt(player, true);
                } else {
                    authSource.put(uuid, "session+enroll");
                    audit.log(AuditLog.SESSION_RESTORE, name, name, ip,
                            AuditLog.details("twoFactorEnrollment", true));
                    beginForcedEnrollment(player, "two-factor.required.session");
                }
                return;
            }
            String multialtBlock = enforceAntiMultiAlt(player, name, ip, now);
            if (multialtBlock != null) {
                player.kick(Component.text(multialtBlock, NamedTextColor.RED));
                return;
            }
            authenticatedPlayers.add(uuid);
            freezeLocations.remove(uuid);
            lastChatReminderAt.remove(uuid);
            authSource.put(uuid, "session");
            audit.log(AuditLog.SESSION_RESTORE, name, name, ip, AuditLog.details("twoFactorRequired", false));
            messages.send(player, "session.restored");
            player.sendActionBar(messages.get("session.restored-actionbar"));
            showSessionNotice(player);
            announceRankJoin(player);
            return;
        }

        player.closeInventory();
        sendLoginPrompt(player, true);
    }

    /**
     * Blocks or reports players who share one IP with too many accounts holding active passes.
     *
     * @return a kick message when {@code anti-multialt.mode: deny} applies, otherwise {@code null}
     */
    private String enforceAntiMultiAlt(Player player, String name, String ip, long now) {
        if (!config.antiMultiAltEnabled() || ip == null || ip.isBlank()) return null;
        long window = TimeUnit.MINUTES.toMillis(config.antiMultiAltWindowMinutes());
        List<String> sharing = store.accountsSharingIp(ip, now, window);
        long withPass = sharing.stream().filter(entry -> store.hasActivePass(entry, now)).count();
        if (withPass < config.maxAccountsPerIp()) return null;
        audit.log(AuditLog.MULTIALT_ALERT, name, "", ip,
                AuditLog.details("accounts", String.join(", ", sharing), "limit", config.maxAccountsPerIp()));
        if (config.bruteForceNotifyAdmins()) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("chrdkregister.admin")) {
                    messages.send(online, "bruteforce.multi-alt",
                            "ip", AuditLog.maskIp(ip),
                            "sharing", String.join(", ", sharing));
                }
            }
        }
        notifications.notify("multialt.alert",
                messages.plain("bruteforce.multi-alt-title"),
                messages.raw("bruteforce.multi-alt-body",
                        "ip", AuditLog.maskIp(ip),
                        "accounts", String.join(", ", sharing),
                        "limit", config.maxAccountsPerIp()),
                "warn");
        return config.antiMultiAltDeny()
                ? messages.plain("restriction.too-many-accounts")
                : null;
    }


    public void onPlayerQuit(Player player) {
        UUID uuid = player.getUniqueId();
        if (authenticatedPlayers.remove(uuid)) {
            store.touchSession(player.getName(), uuid, getPlayerIp(player), System.currentTimeMillis());
        }
        String source = authSource.remove(uuid);
        if (source != null && config.auditLogLogins()) {
            audit.log(AuditLog.SESSION_END, player.getName(), player.getName(), getPlayerIp(player),
                    AuditLog.details("source", source,
                            "online", AccessManager.formatDuration(System.currentTimeMillis()
                                    - joinedAt.getOrDefault(uuid, System.currentTimeMillis()))));
        }
        passwordVerifiedPlayers.remove(uuid);
        pendingTotpSecrets.remove(uuid);
        clearTotpQrMaps(player); // карта с QR не должна занимать слот следующей сессии
        freezeLocations.remove(uuid);
        lastChatReminderAt.remove(uuid);
        joinedAt.remove(uuid);
        pendingCaptchas.remove(uuid);
        captchaClearedFailures.remove(uuid);
        hideExpiryBar(uuid);
    }

    public boolean handleRegisterCommand(Player player, String password, String confirmPassword) {
        long now = System.currentTimeMillis();
        PlayerStage stage = getStage(player.getUniqueId(), player.getName(), now);

        if (stage == PlayerStage.NO_PASS) {
            sendNoPassPrompt(player, false);
            return true;
        }
        if (stage == PlayerStage.NEEDS_LOGIN || stage == PlayerStage.NEEDS_2FA
                || stage == PlayerStage.NEEDS_2FA_SETUP) {
            messages.send(player, "register.already-registered");
            return true;
        }
        if (stage == PlayerStage.AUTHENTICATED) {
            messages.send(player, "register.already-authenticated");
            return true;
        }

        if (!password.equals(confirmPassword)) {
            messages.send(player, "register.password-mismatch");
            return true;
        }
        PasswordPolicy.Result policy = PasswordPolicy.check(player.getName(), password, config);
        if (!policy.accepted()) {
            player.sendMessage(policy.renderMessage(messages));
            if (config.passwordStrengthHint()) {
                messages.send(player, "password.strength-hint",
                        "strength", policy.strengthText(messages),
                        "hints", describeHints(password));
            }
            return true;
        }

        String ip = getPlayerIp(player);
        Optional<String> shared = findSharedPassword(player.getName(), password, ip, now);
        if (shared.isPresent()) {
            messages.send(player, "register.password-shared");
            return true;
        }
        int welcomeDays = config.ranks().welcomeEnabled() ? config.ranks().welcomeDays() : 0;
        boolean registered = store.registerPassword(player.getName(), player.getUniqueId(), password, ip, now,
                welcomeDays);
        if (!registered) {
            messages.send(player, "register.failed");
            return true;
        }

        RegistrationStore.PlayerAccount account = store.getAccount(player.getName()).orElseThrow();
        if (account.trialGrantedAt == now && "trial".equals(account.lastGrantSource)) {
            // Приветственный ранг: дни берём из ranks.welcome (3), а не из старого
            // pass.trial.days (14) — иначе игрок читал бы «14 дней» про 3-дневный ранг.
            int grantedDays = account.lastGrantDays > 0 ? account.lastGrantDays : welcomeDays;
            messages.send(player, "trial.granted", "days", grantedDays, "until", formatTimestamp(account.passUntil));
            // Приветственный ранг — не покупка: группа проходки выдаётся только после оплаты.
            plugin.luckPermsPassSync().onPassGranted(player.getName(), false);
            fireEventSync(new PassGrantedEvent(player.getName(), account.passUntil, grantedDays, "trial"));
            audit.log(AuditLog.ADMIN_COMMAND, player.getName(), player.getName(), ip,
                    AuditLog.details("action", "trial.granted", "days", grantedDays, "passUntil", account.passUntil));
            notifications.notify("pass.granted", messages.plain("trial.notify-title"),
                    messages.plain("trial.notify-desc", "player", player.getName(), "days", grantedDays,
                            "until", formatTimestamp(account.passUntil)), "good");
        }
        if (requiresTotpEnrollment(player.getName())) {
            beginForcedEnrollment(player, "register.success-two-factor");
        } else {
            completeAuthentication(player, "register.success");
        }
        if (config.passwordStrengthHint()) {
            messages.send(player, "password.strength-ok", "strength", policy.strengthText(messages));
        }
        logger.info("Player " + player.getName() + " registered an account password.");
        audit.log(AuditLog.ADMIN_COMMAND, player.getName(), player.getName(), ip,
                AuditLog.details("action", "register", "strength", policy.strengthText(messages)));
        return true;
    }

    /**
     * Checks whether the candidate password is already used by another account that recently logged in
     * from the same IP. Only the accounts sharing the IP are probed, and the conflicting nickname is
     * never disclosed to the player.
     */
    private Optional<String> findSharedPassword(String selfName, String candidatePassword, String ip, long now) {
        if (ip == null || ip.isBlank()) return Optional.empty();
        long window = TimeUnit.MINUTES.toMillis(Math.max(60, config.antiMultiAltWindowMinutes()));
        List<String> sharing = store.accountsSharingIp(ip, now, window);
        if (sharing.size() <= 1) return Optional.empty();
        return PasswordPolicy.findSharedAccount(store, selfName, candidatePassword, sharing);
    }

    public boolean handleLoginCommand(Player player, String password) {
        return handleLoginCommand(player, password, null);
    }

    public boolean handleLoginCommand(Player player, String password, String optionalTotpCode) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        PlayerStage stage = getStage(uuid, player.getName(), now);

        if (stage == PlayerStage.NO_PASS) {
            sendNoPassPrompt(player, false);
            return true;
        }
        if (stage == PlayerStage.NEEDS_REGISTER) {
            messages.send(player, "login.register-first");
            return true;
        }
        if (stage == PlayerStage.AUTHENTICATED) {
            messages.send(player, "login.already-authenticated");
            return true;
        }
        if (stage == PlayerStage.NEEDS_2FA) {
            if (optionalTotpCode == null && TotpService.isValidCodeFormat(password)) {
                return handleTwoFactorLoginCode(player, password);
            }
            messages.send(player, "login.two-factor-pending");
            return true;
        }
        if (stage == PlayerStage.NEEDS_2FA_SETUP) {
            if (optionalTotpCode != null && !optionalTotpCode.isBlank()) {
                return handleTwoFactorConfirmSetup(player, optionalTotpCode);
            }
            messages.send(player, "login.two-factor-setup-pending");
            sendTotpEnrollmentPrompt(player, true);
            return true;
        }

        AuthThrottle.Result throttleResult = throttle.check(player.getName(), getPlayerIp(player), now);
        if (throttleResult.blocked()) {
            messages.send(player, "login.throttled-blocked",
                    "reason", throttleResult.reason(),
                    "blocked_until", AccessManager.formatTimestamp(throttleResult.blockedUntil()));
            audit.log(AuditLog.BRUTEFORCE_ALERT, player.getName(), player.getName(), getPlayerIp(player),
                    AuditLog.details("decision", "blocked", "reason", throttleResult.reason()));
            return true;
        }
        if (throttleResult.decision() == AuthThrottle.Decision.DELAY) {
            messages.send(player, "login.throttled-delay", "delay_seconds", throttleResult.delaySeconds());
            return true;
        }
        if (isCaptchaRequired(uuid, player.getName())) {
            presentCaptchaChallenge(player);
            return true;
        }

        if (store.verifyPassword(player.getName(), password)) {
            throttle.registerSuccess(player.getName(), getPlayerIp(player));
            if (config.totpEnabled() && store.isTotpEnabled(player.getName())) {
                passwordVerifiedPlayers.add(uuid);
                lastChatReminderAt.put(uuid, now);
                if (optionalTotpCode != null && !optionalTotpCode.isBlank()) {
                    return handleTwoFactorLoginCode(player, optionalTotpCode);
                }
                sendTwoFactorPrompt(player, true);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f);
                return true;
            }
            if (requiresTotpEnrollment(player.getName())) {
                passwordVerifiedPlayers.add(uuid);
                if (optionalTotpCode != null && TotpService.isValidCodeFormat(optionalTotpCode)) {
                    return handleTwoFactorConfirmSetup(player, optionalTotpCode);
                }
                beginForcedEnrollment(player, "login.password-ok-two-factor");
                return true;
            }

            String multialtBlock = enforceAntiMultiAlt(player, player.getName(), getPlayerIp(player), now);
            if (multialtBlock != null) {
                player.kick(Component.text(multialtBlock, NamedTextColor.RED));
                return true;
            }
            store.touchSession(player.getName(), uuid, getPlayerIp(player), now);
            authSource.put(uuid, "password");
            completeAuthentication(player, "login.success");
            logger.info("Player " + player.getName() + " logged in.");
            if (config.auditLogLogins()) {
                audit.log(AuditLog.LOGIN_SUCCESS, player.getName(), player.getName(), getPlayerIp(player),
                        AuditLog.details("method", "password"));
            }
            return true;
        }

        AuthThrottle.Result failure = throttle.registerFailure(player.getName(), getPlayerIp(player), now);
        int totalFailures = throttle.totalFailures(player.getName());
        audit.log(AuditLog.LOGIN_FAIL, player.getName(), player.getName(), getPlayerIp(player),
                AuditLog.details("attempts", totalFailures, "decision", failure.decision().name()));
        if (failure.blocked()) {
            if (failure.ipBan() && config.tempIpBanMinutes() > 0) {
                banIpTemporarily(player, failure);
            }
            player.kick(messages.get("login.temp-ban", "temp_ip_ban_minutes", config.tempIpBanMinutes()));
            notifyBruteForce(player, totalFailures);
            return true;
        }
        int remaining = Math.max(0, config.accountMaxAttempts() - totalFailures);
        messages.send(player, "login.wrong-password", "remaining", remaining);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
        showAuthDialog(player, PlayerStage.NEEDS_LOGIN);
        if (totalFailures >= 3 && totalFailures % 3 == 0) {
            notifyBruteForce(player, totalFailures);
        }
        return true;
    }

    private void banIpTemporarily(Player player, AuthThrottle.Result failure) {
        try {
            long minutes = Math.max(1, (failure.blockedUntil() - System.currentTimeMillis()) / 60_000L);
            Bukkit.getBanList(org.bukkit.BanList.Type.IP)
                    .addBan(AccessManager.getPlayerIp(player), "CHRDK: перебор пароля ("
                            + AuditLog.describeDuration(minutes * 60_000L) + ")", null, "CHRDK_Core");
            logger.warning("Temporary IP ban applied to " + AuditLog.maskIp(AccessManager.getPlayerIp(player))
                    + " for " + minutes + " min.");
        } catch (RuntimeException exception) {
            logger.log(Level.FINE, "Could not apply a temporary IP ban", exception);
        }
    }

    private void notifyBruteForce(Player player, int failures) {
        String message = "Перебор пароля: игрок " + player.getName() + " (" + AuditLog.maskIp(getPlayerIp(player))
                + "), неудачных попыток: " + failures;
        if (config.bruteForceNotifyAdmins()) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("chrdkregister.admin")) {
                    messages.send(online, "bruteforce.notify", "message", message);
                }
            }
        }
        audit.log(AuditLog.BRUTEFORCE_ALERT, player.getName(), player.getName(), getPlayerIp(player),
                AuditLog.details("failures", failures));
        notifications.notify("bruteforce.alert", "Перебор паролей",
                "Игрок: " + player.getName() + "\nIP: " + AuditLog.maskIp(getPlayerIp(player))
                        + "\nНеудачных попыток: " + failures, "warn");
    }

    /**
     * Verifies the 6-digit TOTP code during the NEEDS_2FA login stage.
     */
    public boolean handleTwoFactorLoginCode(Player player, String rawCode) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        PlayerStage stage = getStage(uuid, player.getName(), now);

        if (stage == PlayerStage.NO_PASS) {
            sendNoPassPrompt(player, false);
            return true;
        }
        if (stage == PlayerStage.NEEDS_REGISTER) {
            messages.send(player, "two-factor.login.register-first");
            return true;
        }
        if (stage == PlayerStage.NEEDS_LOGIN) {
            messages.send(player, "two-factor.login.password-first");
            return true;
        }
        if (stage == PlayerStage.AUTHENTICATED) {
            messages.send(player, "two-factor.login.already-authenticated");
            return true;
        }
        if (stage == PlayerStage.NEEDS_2FA_SETUP) {
            // Обязательная привязка: тот же код подтверждает секрет и завершает вход.
            return handleTwoFactorConfirmSetup(player, rawCode);
        }

        if (!TotpService.isValidCodeFormat(rawCode)) {
            messages.send(player, "two-factor.login.bad-format");
            return true;
        }

        AuthThrottle.Result throttleResult = throttle.check(player.getName(), getPlayerIp(player), now);
        if (throttleResult.blocked()) {
            messages.send(player, "two-factor.login.throttled", "reason", throttleResult.reason());
            return true;
        }
        if (throttleResult.decision() == AuthThrottle.Decision.DELAY) {
            messages.send(player, "two-factor.login.throttled", "reason", throttleResult.reason());
            return true;
        }
        if (isCaptchaRequired(uuid, player.getName())) {
            presentCaptchaChallenge(player);
            return true;
        }

        if (store.verifyTotp(player.getName(), rawCode, now, config.totpWindowSteps())) {
            throttle.registerSuccess(player.getName(), getPlayerIp(player));
            String multialtBlock = enforceAntiMultiAlt(player, player.getName(), getPlayerIp(player), now);
            if (multialtBlock != null) {
                player.kick(Component.text(multialtBlock, NamedTextColor.RED));
                return true;
            }
            store.touchSession(player.getName(), uuid, getPlayerIp(player), now);
            authSource.put(uuid, "2fa");
            completeAuthentication(player, "two-factor.login.success");
            logger.info("Player " + player.getName() + " logged in with TOTP 2FA.");
            if (config.auditLogLogins()) {
                audit.log(AuditLog.LOGIN_SUCCESS, player.getName(), player.getName(), getPlayerIp(player),
                        AuditLog.details("method", "totp"));
            }
            return true;
        }

        AuthThrottle.Result failure = throttle.registerFailure(player.getName(), getPlayerIp(player), now);
        if (failure.blocked()) {
            passwordVerifiedPlayers.remove(uuid);
            player.kick(messages.get("two-factor.login.kicked", "totp_max_attempts", config.totpMaxAttempts()));
            notifyBruteForce(player, throttle.totalFailures(player.getName()));
            return true;
        }

        messages.send(player, "two-factor.login.wrong-code");
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
        showAuthDialog(player, PlayerStage.NEEDS_2FA);
        return true;
    }

    /**
     * Starts (or re-shows) TOTP 2FA enrollment: works for an authenticated player and for
     * the mandatory-enrollment stage, where the same secret is reused instead of regenerated.
     */
    public boolean handleTwoFactorSetup(Player player) {
        PlayerStage stage = getStage(player);
        if (stage != PlayerStage.AUTHENTICATED && stage != PlayerStage.NEEDS_2FA_SETUP) {
            messages.send(player, "two-factor.setup.not-authenticated");
            return true;
        }
        if (!config.totpEnabled()) {
            messages.send(player, "two-factor.setup.disabled-server");
            return true;
        }
        if (store.isTotpEnabled(player.getName())) {
            messages.send(player, "two-factor.setup.already-enabled");
            return true;
        }

        // Повторный /2fa setup показывает тот же ключ: уже отсканированный секрет не сгорает.
        // Новый ключ — только после /2fa cancel (или если ожидающего секрета нет).
        String secret = pendingTotpSecrets.get(player.getUniqueId());
        if (secret == null || secret.isBlank()) {
            secret = TotpService.generateSecret();
            pendingTotpSecrets.put(player.getUniqueId(), secret);
        }
        sendTotpSetupInstructions(player, secret);
        return true;
    }

    /** Инструкция по привязке TOTP: ключ, otpauth-ссылка и (если включено) карта с QR-кодом. */
    public void sendTotpSetupInstructions(Player player, String secret) {
        String formattedSecret = TotpService.formatSecretForDisplay(secret);
        String otpAuthUri = TotpService.buildOtpAuthUri(config.totpIssuer(), player.getName(), secret);

        Component copyKeyButton = messages.get("two-factor.setup-instructions.copy-key")
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.copyToClipboard(secret))
                .hoverEvent(HoverEvent.showText(messages.get("two-factor.setup-instructions.copy-key-hover")));

        Component copyUriButton = messages.get("two-factor.setup-instructions.copy-uri")
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.copyToClipboard(otpAuthUri))
                .hoverEvent(HoverEvent.showText(messages.get("two-factor.setup-instructions.copy-uri-hover")));

        Component confirmCommandHint = messages.get("two-factor.setup-instructions.confirm-command")
                .clickEvent(ClickEvent.suggestCommand("/2fa confirm "))
                .hoverEvent(HoverEvent.showText(messages.get("two-factor.setup-instructions.confirm-hover")));

        player.sendMessage(Component.empty());
        messages.send(player, "two-factor.setup-instructions.header-line");
        messages.send(player, "two-factor.setup-instructions.header");
        player.sendMessage(messages.get("two-factor.setup-instructions.step1")
                .append(messages.get("two-factor.setup-instructions.step1-apps")));
        messages.send(player, "two-factor.setup-instructions.step2");
        player.sendMessage(messages.get("two-factor.setup-instructions.step2-key")
                .append(Component.text(formattedSecret, NamedTextColor.YELLOW, TextDecoration.BOLD)));
        player.sendMessage(Component.text("   ", NamedTextColor.GRAY)
                .append(copyKeyButton)
                .append(Component.text("  ", NamedTextColor.GRAY))
                .append(copyUriButton));
        messages.send(player, "two-factor.setup-instructions.step3");
        player.sendMessage(messages.get("two-factor.setup-instructions.step3-input").append(confirmCommandHint));
        messages.send(player, "two-factor.setup-instructions.footer-line");
        if (config.totpQrMap()) {
            if (giveTotpQrMap(player)) {
                messages.send(player, "two-factor.setup-instructions.qr-map");
            } else {
                messages.send(player, "two-factor.required.qr-unavailable");
            }
        }
    }

    /**
     * Выдаёт карту с QR-кодом привязки ({@code /2fa qr}) — и до входа, и после него.
     * Карта кладётся в свободный слот хотбара и сразу берётся в руку; если хотбар занят,
     * её можно переложить: до привязки разрешены клики только с этой картой.
     */
    public boolean handleTwoFactorQrMap(Player player) {
        PlayerStage stage = getStage(player);
        if (stage != PlayerStage.AUTHENTICATED && stage != PlayerStage.NEEDS_2FA_SETUP) {
            messages.send(player, "two-factor.qr.not-authenticated");
            return true;
        }
        if (!config.totpEnabled() || !config.totpQrMap()) {
            messages.send(player, "two-factor.qr.disabled");
            return true;
        }
        if (store.isTotpEnabled(player.getName())) {
            messages.send(player, "two-factor.setup.already-enabled");
            return true;
        }
        if (getPendingTotpSecret(player.getUniqueId()).isEmpty()) {
            messages.send(player, "two-factor.qr.no-pending");
            return true;
        }
        if (giveTotpQrMap(player)) {
            messages.send(player, "two-factor.qr.sent");
        } else {
            messages.send(player, "two-factor.required.qr-unavailable");
        }
        return true;
    }

    /* ------------------------------------------------------------------ *
     *  Escalation captcha after repeated failed logins (/captcha <число>) *
     * ------------------------------------------------------------------ */

    /**
     * Требуется ли эскалационная капча: счётчик неудач достиг {@code auth.captcha-after-attempts},
     * а последняя пройденная проверка относилась к более ранней серии неудач.
     */
    public boolean isCaptchaRequired(UUID uuid, String playerName) {
        int failures = throttle.totalFailures(playerName);
        return CaptchaChallenge.required(
                failures, captchaClearedFailures.getOrDefault(uuid, -1), config.captchaAfterAttempts());
    }

    /** Показывает текущий или генерирует новый вызов: диалог Paper либо чат-подсказка. */
    public void presentCaptchaChallenge(Player player) {
        CaptchaChallenge challenge = pendingCaptchas.computeIfAbsent(
                player.getUniqueId(), ignored -> CaptchaChallenge.generate(captchaRandom));
        messages.send(player, "captcha.required", "failures", throttle.totalFailures(player.getName()));
        AuthDialogs dialogs = this.authDialogs;
        if (dialogs != null && config.authDialogs()) {
            dialogs.showCaptcha(player, challenge);
        } else {
            messages.send(player, "captcha.chat-fallback",
                    "options", String.join(" ", challenge.options()));
        }
    }

    /** Обрабатывает ответ {@code /captcha <число>} (и клики кнопок диалога). */
    public boolean handleCaptchaAnswer(Player player, String answer) {
        UUID uuid = player.getUniqueId();
        CaptchaChallenge challenge = pendingCaptchas.get(uuid);
        PlayerStage stage = getStage(uuid, player.getName(), System.currentTimeMillis());
        if (challenge == null || stage == PlayerStage.AUTHENTICATED) {
            messages.send(player, "captcha.not-required");
            return true;
        }
        if (!challenge.matches(answer)) {
            pendingCaptchas.remove(uuid);
            messages.send(player, "captcha.wrong");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            presentCaptchaChallenge(player);
            return true;
        }
        pendingCaptchas.remove(uuid);
        captchaClearedFailures.put(uuid, throttle.totalFailures(player.getName()));
        messages.send(player, "captcha.solved");
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f);
        showAuthDialog(player, stage == PlayerStage.NEEDS_2FA ? PlayerStage.NEEDS_2FA : PlayerStage.NEEDS_LOGIN);
        return true;
    }

    /**
     * Выдаёт карту с QR-кодом и, если получилось, сразу берёт её в руку: карта видна
     * только в главной руке, а до входа игрок не может свободно двигать инвентарь.
     *
     * @return {@code true}, если карта оказалась в инвентаре игрока
     */
    private boolean giveTotpQrMap(Player player) {
        try {
            totpQrMaps.removeMaps(player);
            ItemStack mapItem = totpQrMaps.createMapItem(player.getWorld(),
                    messages.get("two-factor.qr.item-name"),
                    messages.getList("two-factor.qr.item-lore"));
            PlayerInventory inventory = player.getInventory();
            int slot = firstEmptyHotbarSlot(inventory);
            if (slot >= 0) {
                inventory.setItem(slot, mapItem);
                if (getStage(player) != PlayerStage.AUTHENTICATED) {
                    // До входа карту надо сразу взять в руку: открыть инвентарь и выбрать слот
                    // игрок не может (кроме карты). После входа слот игрока не трогаем.
                    inventory.setHeldItemSlot(slot);
                }
                return true;
            }
            Map<Integer, ItemStack> leftover = inventory.addItem(mapItem);
            leftover.values().forEach(rest ->
                    player.getWorld().dropItemNaturally(player.getLocation(), rest));
            return leftover.isEmpty();
        } catch (Throwable throwable) {
            logger.log(Level.WARNING, "Не удалось выдать карту с QR-кодом 2FA игроку "
                    + player.getName(), throwable);
            return false;
        }
    }

    /** Первый свободный слот хотбара (0–8) или {@code -1}. */
    private static int firstEmptyHotbarSlot(PlayerInventory inventory) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                return slot;
            }
        }
        return -1;
    }

    /** Карта с QR-кодом 2FA? Используется ограничениями инвентаря до входа. */
    public boolean isTotpQrMap(ItemStack item) {
        return totpQrMaps.isQrMap(item);
    }

    /** Убирает выданные карты QR-кода (после привязки или отмены настройки). */
    public void clearTotpQrMaps(Player player) {
        totpQrMaps.removeMaps(player);
    }

    /**
     * Confirms pending TOTP 2FA setup using a 6-digit code from the player's authenticator app.
     */
    public boolean handleTwoFactorConfirmSetup(Player player, String rawCode) {
        PlayerStage stage = getStage(player);
        if (stage != PlayerStage.AUTHENTICATED && stage != PlayerStage.NEEDS_2FA_SETUP) {
            return handleTwoFactorLoginCode(player, rawCode);
        }
        if (store.isTotpEnabled(player.getName())) {
            messages.send(player, "two-factor.confirm.already-enabled");
            return true;
        }
        UUID uuid = player.getUniqueId();
        String pendingSecret = pendingTotpSecrets.get(uuid);
        if (pendingSecret == null || pendingSecret.isBlank()) {
            messages.send(player, "two-factor.confirm.no-pending");
            return true;
        }
        if (!TotpService.isValidCodeFormat(rawCode)) {
            messages.send(player, "two-factor.confirm.bad-format");
            return true;
        }

        long now = System.currentTimeMillis();
        OptionalLong matchedStep = TotpService.verifyAndGetStep(
                pendingSecret, rawCode, now, config.totpWindowSteps(), -1L);
        if (matchedStep.isEmpty()) {
            messages.send(player, "two-factor.confirm.wrong-code");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return true;
        }

        if (!store.enableTotp(player.getName(), pendingSecret, matchedStep.getAsLong(), now)) {
            messages.send(player, "two-factor.confirm.failed");
            return true;
        }

        pendingTotpSecrets.remove(uuid);
        store.touchSession(player.getName(), uuid, getPlayerIp(player), now);
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        if (stage == PlayerStage.NEEDS_2FA_SETUP) {
            // Обязательная привязка выполнена — только теперь пускаем в игру.
            authSource.put(uuid, "2fa-enrolled");
            completeAuthentication(player, "two-factor.required.success");
        } else {
            messages.send(player, "two-factor.confirm.success");
        }
        logger.info("Player " + player.getName() + " enabled TOTP 2FA.");
        audit.log(AuditLog.TOTP_ENABLE, player.getName(), player.getName(), getPlayerIp(player));
        notifications.notify("totp.enabled", "Включена 2FA",
                "Игрок: " + player.getName() + "\nIP: " + AuditLog.maskIp(getPlayerIp(player)), "info");
        return true;
    }

    /**
     * Disables TOTP 2FA for an authenticated player after verifying their 6-digit code or account password.
     */
    public boolean handleTwoFactorDisable(Player player, String verificationInput) {
        if (getStage(player) != PlayerStage.AUTHENTICATED) {
            messages.send(player, "two-factor.disable.not-authenticated");
            return true;
        }
        if (!store.isTotpEnabled(player.getName())) {
            messages.send(player, "two-factor.disable.not-enabled");
            return true;
        }
        if (config.totpRequired()) {
            // Обязательную 2FA игрок не отключает сам: только /pass reset2fa от администратора.
            messages.send(player, "two-factor.disable.required");
            return true;
        }
        if (verificationInput == null || verificationInput.isBlank()) {
            messages.send(player, "two-factor.disable.usage");
            return true;
        }

        long now = System.currentTimeMillis();
        boolean verified = store.verifyTotp(player.getName(), verificationInput, now, config.totpWindowSteps())
                || store.verifyPassword(player.getName(), verificationInput);
        if (!verified) {
            messages.send(player, "two-factor.disable.wrong-code");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return true;
        }

        store.disableTotp(player.getName());
        pendingTotpSecrets.remove(player.getUniqueId());
        messages.send(player, "two-factor.disable.success");
        logger.info("Player " + player.getName() + " disabled TOTP 2FA.");
        audit.log(AuditLog.TOTP_DISABLE, player.getName(), player.getName(), getPlayerIp(player));
        notifications.notify("totp.disabled", "2FA отключена игроком",
                "Игрок: " + player.getName() + "\nIP: " + AuditLog.maskIp(getPlayerIp(player)), "warn");
        return true;
    }

    public boolean handleTwoFactorCancel(Player player) {
        PlayerStage stage = getStage(player);
        if (stage != PlayerStage.AUTHENTICATED && stage != PlayerStage.NEEDS_2FA_SETUP) {
            messages.send(player, "two-factor.cancel.not-authenticated");
            return true;
        }
        String removed = pendingTotpSecrets.remove(player.getUniqueId());
        clearTotpQrMaps(player); // старый QR-код больше не действителен
        if (removed == null) {
            messages.send(player, "two-factor.cancel.nothing-pending");
        } else {
            messages.send(player, "two-factor.cancel.success");
        }
        if (stage == PlayerStage.NEEDS_2FA_SETUP) {
            // Отмена не отменяет обязательную 2FA — просто даёт новый ключ через /2fa setup.
            sendTotpEnrollmentPrompt(player, false);
        }
        return true;
    }

    public boolean handleChangePasswordCommand(Player player, String oldPassword, String newPassword) {
        if (getStage(player) != PlayerStage.AUTHENTICATED) {
            messages.send(player, "password.change.not-authenticated");
            return true;
        }
        PasswordPolicy.Result policy = PasswordPolicy.check(player.getName(), newPassword, config);
        if (!policy.accepted()) {
            player.sendMessage(policy.renderMessage(messages));
            if (config.passwordStrengthHint()) {
                messages.send(player, "password.strength-hint",
                        "strength", policy.strengthText(messages),
                        "hints", describeHints(newPassword));
            }
            return true;
        }
        if (!store.changePassword(player.getName(), oldPassword, newPassword)) {
            messages.send(player, "password.change.wrong-old");
            return true;
        }
        long changedAt = System.currentTimeMillis();
        store.clearSessions(player.getName());
        store.touchSession(player.getName(), player.getUniqueId(), getPlayerIp(player), changedAt);
        messages.send(player, "password.change.success");
        logger.info("Player " + player.getName() + " changed their password.");
        audit.log(AuditLog.PASSWORD_CHANGE, player.getName(), player.getName(), getPlayerIp(player),
                AuditLog.details("strength", policy.strengthText(messages)));
        notifications.notify("password.change", "Смена пароля",
                "Игрок: " + player.getName() + "\nIP: " + AuditLog.maskIp(getPlayerIp(player)), "info");
        return true;
    }

    /**
     * Grants a pass manually by an administrator with the default duration and source.
     */
    /* ------------------------------------------------------------------ *
     *  Подписочный Boosty (модель «проходка, пока живая подписка»)
     * ------------------------------------------------------------------ */

    /** Менеджер привязок/перепроверок Boosty (для /pass boost и doctor). */
    public BoostyManager boostyManager() {
        return boostyManager;
    }

    /** Жив ли API Boosty? SYNCHRONOUS network call — только для консольных команд (doctor). */
    public boolean probeBoosty() {
        PluginConfig.BoostySettings bs = config().boosty();
        return bs.ready() && boostyManager.client().probe();
    }

    /** /pass boost <ник>: сеть делается асинхронно, сообщения — в главном потоке. */
    public void bindBoostyAsync(Player player, String boostyNameRaw) {
        PluginConfig.BoostySettings bs = config().boosty();
        if (!bs.enabled()) {
            messages.send(player, "boost.disabled");
            return;
        }
        if (!bs.ready()) {
            messages.send(player, "boost.not-configured");
            return;
        }
        String boostyName = boostyNameRaw == null ? "" : boostyNameRaw.trim();
        messages.send(player, "pass.boost-checking", "boosty", boostyName);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            BoostyManager.BindOutcome outcome;
            try {
                outcome = boostyManager.bindFor(player.getName(), boostyName, config());
            } catch (java.io.IOException exception) {
                logger.warning("[Boosty] Ошибка привязки " + player.getName() + ": " + exception.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> messages.send(player, "pass.boost-error"));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> sendBoostyOutcome(player, boostyName, outcome, bs));
        });
    }

    private void sendBoostyOutcome(Player player, String typedName,
                                   BoostyManager.BindOutcome outcome, PluginConfig.BoostySettings bs) {
        switch (outcome.kind()) {
            case LINKED, CHANGED -> {
                RegistrationStore.PlayerAccount account =
                        store.getAccount(player.getName()).orElse(null);
                long until = account != null ? account.passUntil : 0L;
                onPassGranted(player.getName(), until,
                        BoostyClient.SOURCE + " (" + outcome.subscriber().name() + ")");
                messages.send(player,
                        outcome.kind() == BoostyManager.BindOutcome.Kind.LINKED
                                ? "pass.boost-linked" : "pass.boost-changed",
                        "boosty", outcome.subscriber().name(),
                        "level", outcome.subscriber().levelName(),
                        "until", formatTimestamp(until));
                ShopDiscount discount = config().shopDiscount();
                if (discount.usable()) {
                    messages.send(player, "shop.boosty-perk", "percent", discount.percent());
                }
            }
            case NOT_FOUND -> messages.send(player, "pass.boost-not-found", "boosty", typedName);
            case INACTIVE -> messages.send(player, "pass.boost-inactive",
                    "boosty", outcome.subscriber().name());
            case LOW_LEVEL -> messages.send(player, "pass.boost-low-level",
                    "boosty", outcome.subscriber().name(),
                    "level", outcome.subscriber().levelName(),
                    "min", String.valueOf(bs.minLevelPrice()));
            case TAKEN -> messages.send(player, "pass.boost-taken",
                    "boosty", outcome.subscriber().name(),
                    "player", outcome.holder());
        }
    }

    /** Асинхронная перепроверка привязок: активным догоняет горизонт проходки. */
    private void maybeRecheckBoosty() {
        if (config().trademc().enabled()) {
            return; // TradeMC-режим: Boosty-подписки выключены единым переключателем
        }
        PluginConfig.BoostySettings bs = config().boosty();
        if (!bs.ready() || boostyManager.bindings().isEmpty()) {
            return;
        }
        try {
            BoostyManager.RecheckStats stats = boostyManager.recheckAll(config());
            logger.info(java.lang.String.format(java.util.Locale.ROOT,
                    "Boosty heartbeat: OK (привязок: %d; продлено: %d; не подтверждено: %d).",
                    stats.total(), stats.extended(), stats.lapsed()));
            long now = System.currentTimeMillis();
            for (String revived : stats.revived()) {
                store.getAccount(revived).ifPresent(account ->
                        onPassGranted(revived, account.passUntil,
                                BoostyClient.SOURCE + " (возрождение подписки)"));
            }
        } catch (Exception exception) {
            logger.warning("Boosty heartbeat: FAIL — " + exception.getMessage());
        }
    }

    public RegistrationStore.PlayerAccount grantPassManually(String targetPlayerName) {
        return grantPassManually(targetPlayerName, config.grantDays(), "manual");
    }

    /**
     * Grants or extends a pass manually.
     *
     * @param days pass length in days; values {@code <= 0} fall back to {@code pass.grant-days}
     * @param source tag recorded in the account (manual, gift:Nick, donatepay:#42, ...)
     */
    public RegistrationStore.PlayerAccount grantPassManually(String targetPlayerName, int days, String source) {
        long now = System.currentTimeMillis();
        int lengthDays = days > 0 ? days : config.grantDays();
        long durationMillis = Duration.ofDays(lengthDays).toMillis();
        RegistrationStore.PlayerAccount account =
                store.grantPassManual(targetPlayerName, now, durationMillis, source);

        // Источник виден игроку в поздравлении «Проходка активирована (%source%)».
        // Награды аддонов не должны представляться выдачей администратора.
        String description = describeGrantSource(source);
        Player onlinePlayer = Bukkit.getPlayerExact(targetPlayerName);
        if (onlinePlayer != null) {
            notifyOnlinePassActivated(onlinePlayer, account.passUntil, description);
        }
        logger.info("Manual pass granted to " + targetPlayerName + " for " + lengthDays
                + " days (until " + formatTimestamp(account.passUntil) + ", source: " + source + ").");
        audit.log(AuditLog.PASS_GRANT_MANUAL, "admin", targetPlayerName, "",
                AuditLog.details("days", lengthDays, "source", source, "passUntil", account.passUntil));
        notifications.notify("pass.granted", notificationTitle(source),
                "Игрок: " + targetPlayerName + "\nДней: " + lengthDays + "\nИсточник: " + source, "good");
        plugin.luckPermsPassSync().onPassGranted(targetPlayerName);
        fireEventSync(new PassGrantedEvent(targetPlayerName, account.passUntil, lengthDays, source));
        return account;
    }

    /**
     * Человеческое описание источника проходки для поздравления игроку.
     * Аддоны передают метку вида {@code streams:...}, {@code battlepass:...} и т.п.
     */
    static String describeGrantSource(String source) {
        if (source == null || source.isBlank()) {
            return "администратором";
        }
        if (source.startsWith("gift")) {
            return "подарком";
        }
        if (source.startsWith("battlepass")) {
            return "наградой батлпасса";
        }
        if (source.startsWith("shop")) {
            return "покупкой в магазине";
        }
        if (source.startsWith("streams")) {
            return "наградой стримерской программы";
        }
        if (source.startsWith("daily-rewards")) {
            return "наградой ежедневного входа";
        }
        if (source.startsWith("referral")) {
            return "бонусом за приглашённого друга";
        }
        return "администратором";
    }

    /** Заголовок уведомления в Discord: ручная выдача или награда аддона. */
    static String notificationTitle(String source) {
        if (source != null && source.startsWith("streams")) {
            return "Проходка продлена по стримерской программе";
        }
        if (source != null && source.startsWith("battlepass")) {
            return "Проходка выдана за батлпасс";
        }
        return "Проходка выдана вручную";
    }

    /**
     * Callback used by {@link DonationService} when a payment extends someone's pass.
     */
    public void onPassGranted(String playerName, long passUntil, String sourceDescription) {
        onPassGranted(playerName, passUntil, sourceDescription, true);
    }

    /**
     * Callback used by {@link DonationService} when a payment extends someone's pass.
     *
     * @param paid {@code false} for a test payment ({@code /pass testpay}) — the pass is extended,
     *             but the LuckPerms pass group is not granted because nothing was bought
     */
    public void onPassGranted(String playerName, long passUntil, String sourceDescription, boolean paid) {
        plugin.luckPermsPassSync().onPassGranted(playerName, paid);
        int days = Math.max(0, (int) ((passUntil - System.currentTimeMillis()) / 86_400_000L));
        fireEventSync(new PassGrantedEvent(playerName, passUntil, days, sourceDescription));
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) {
            notifyOnlinePassActivated(online, passUntil, sourceDescription);
        }
    }

    /**
     * Callback used by {@link DonationService} when a payment lands below the auto-grant minimum:
     * chats the paying player, if online, so the donation doesn't feel lost.
     */
    public void onDonationPending(String donorName, String amountRubles, int minRubles) {
        if (donorName == null || donorName.isBlank()) return;
        Player online = Bukkit.getPlayerExact(donorName);
        if (online != null) {
            messages.send(online, "donation.pending-notice",
                    "amount", amountRubles, "price", minRubles);
        }
    }

    /**
     * Revokes a player's pass and immediately locks them if they are online.
     */
    public boolean revokePass(String targetPlayerName) {
        boolean revoked = store.revokePass(targetPlayerName);
        if (!revoked) return false;

        Player onlinePlayer = Bukkit.getPlayerExact(targetPlayerName);
        if (onlinePlayer != null) {
            UUID uuid = onlinePlayer.getUniqueId();
            authenticatedPlayers.remove(uuid);
            passwordVerifiedPlayers.remove(uuid);
            pendingTotpSecrets.remove(uuid);
            authSource.remove(uuid);
            store.clearSessions(onlinePlayer.getName());
            freezeLocations.put(uuid, onlinePlayer.getLocation().clone());
            onlinePlayer.closeInventory();
            messages.send(onlinePlayer, "pass.revoked");
            sendNoPassPrompt(onlinePlayer, true);
        }
        logger.info("Pass revoked for player " + targetPlayerName + ".");
        audit.log(AuditLog.PASS_REVOKE, "admin", targetPlayerName, "");
        notifications.notify("pass.revoked", "Проходка отозвана",
                "Игрок: " + targetPlayerName, "warn");
        plugin.luckPermsPassSync().onPassRevoked(targetPlayerName);
        fireEventSync(new PassRevokedEvent(targetPlayerName));
        return true;
    }

    /**
     * Resets a player's password (and 2FA) so they must register again via /register.
     */
    public boolean resetPassword(String targetPlayerName) {
        boolean reset = store.resetPassword(targetPlayerName);
        if (!reset) return false;

        Player onlinePlayer = Bukkit.getPlayerExact(targetPlayerName);
        if (onlinePlayer != null) {
            UUID uuid = onlinePlayer.getUniqueId();
            authenticatedPlayers.remove(uuid);
            passwordVerifiedPlayers.remove(uuid);
            pendingTotpSecrets.remove(uuid);
            authSource.remove(uuid);
            store.clearSessions(onlinePlayer.getName());
            freezeLocations.put(uuid, onlinePlayer.getLocation().clone());
            onlinePlayer.closeInventory();
            messages.send(onlinePlayer, "pass.password-reset");
            if (store.hasActivePass(onlinePlayer.getName(), System.currentTimeMillis())) {
                sendRegisterPrompt(onlinePlayer, true);
            }
        }
        logger.info("Password reset for player " + targetPlayerName + ".");
        audit.log(AuditLog.PASSWORD_RESET, "admin", targetPlayerName, "");
        notifications.notify("password.reset", "Пароль сброшен администратором",
                "Игрок: " + targetPlayerName, "warn");
        return true;
    }

    /**
     * Resets only TOTP 2FA for a player while keeping their pass and password intact.
     *
     * <p>IP-сессии при этом завершаются: сброс — подтверждение личности через администратора,
     * а не бесплатный вход тому, кто окажется на том же IP. При обязательной 2FA игрок
     * (в том числе уже находящийся в игре) сразу возвращается к привязке.</p>
     */
    public boolean resetTwoFactor(String targetPlayerName) {
        boolean disabled = store.disableTotp(targetPlayerName);
        if (!disabled) return false;
        store.clearSessions(targetPlayerName);

        Player onlinePlayer = Bukkit.getPlayerExact(targetPlayerName);
        if (onlinePlayer != null) {
            UUID uuid = onlinePlayer.getUniqueId();
            pendingTotpSecrets.remove(uuid);
            messages.send(onlinePlayer, "pass.two-factor-reset");
            boolean wasWaitingForCode = passwordVerifiedPlayers.remove(uuid);
            if (requiresTotpEnrollment(onlinePlayer.getName())) {
                // Обязательная 2FA: после сброса привязка нужна заново и сразу, а не «когда-нибудь».
                beginForcedEnrollment(onlinePlayer, "two-factor.reset-enrollment");
            } else if (wasWaitingForCode && !authenticatedPlayers.contains(uuid)) {
                store.touchSession(onlinePlayer.getName(), uuid, getPlayerIp(onlinePlayer), System.currentTimeMillis());
                completeAuthentication(onlinePlayer, "two-factor.reset-login-complete");
            }
        }
        logger.info("TOTP 2FA reset for player " + targetPlayerName + " by administrator.");
        audit.log(AuditLog.TOTP_RESET, "admin", targetPlayerName, "");
        notifications.notify("totp.reset", "2FA сброшена администратором",
                "Игрок: " + targetPlayerName, "warn");
        return true;
    }

    private void completeAuthentication(Player player, String successKey) {
        UUID uuid = player.getUniqueId();
        // Карта с QR больше не нужна: секрет привязан, картинка на ней уже «закрыта».
        clearTotpQrMaps(player);
        authenticatedPlayers.add(uuid);
        passwordVerifiedPlayers.remove(uuid);
        freezeLocations.remove(uuid);
        lastChatReminderAt.remove(uuid);
        pendingCaptchas.remove(uuid);
        captchaClearedFailures.remove(uuid);
        hideExpiryBar(uuid);

        player.clearTitle();
        player.sendMessage(messages.get(successKey));
        player.sendActionBar(messages.get("auth.success-actionbar"));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        announceRankJoin(player);
        fireEventSync(new PlayerAuthCompletedEvent(player));
    }

    /** Строка входа ранга III+: «Хранитель Имя вернулся в гильдию» (1.4.167). */
    private void announceRankJoin(Player player) {
        RankSettings ranks = config.ranks();
        if (ranks == null || !ranks.enabled() || !ranks.announceJoin()) {
            return;
        }
        int level = store.rankLevel(player.getName(), System.currentTimeMillis());
        String line = ranks.joinLine(level, player.getName());
        if (line.isBlank()) {
            return;
        }
        Bukkit.getServer().broadcast(Messages.format(line));
    }

    /**
     * Вызывает Bukkit-событие для аддонов строго на главном потоке: если место вызова оказалось
     * в асинхронном потоке (например, опрос DonatePay), перебрасывает в tick.
     */
    private void fireEventSync(Event event) {
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(event);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(event));
        }
    }

    private void notifyOnlinePassActivated(Player player, long passUntil, String sourceDescription) {
        UUID uuid = player.getUniqueId();
        lastChatReminderAt.put(uuid, System.currentTimeMillis());

        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        player.sendMessage(Component.empty());
        messages.sendList(player, "pass.activated",
                "source", sourceDescription, "until", formatTimestamp(passUntil));

        if (!store.isRegistered(player.getName())) {
            sendRegisterPrompt(player, true);
        } else if (!authenticatedPlayers.contains(uuid)) {
            boolean passwordConfirmed = passwordVerifiedPlayers.contains(uuid);
            if (passwordConfirmed && requiresTotpEnrollment(player.getName())) {
                beginForcedEnrollment(player, null);
            } else if (config.totpEnabled() && store.isTotpEnabled(player.getName()) && passwordConfirmed) {
                sendTwoFactorPrompt(player, true);
            } else {
                sendLoginPrompt(player, true);
            }
        }
    }

    private void tickOnlinePlayers() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            boolean hasPass = !config.passEnabled() || store.hasActivePass(player.getName(), now);

            if (!hasPass && config.trialEnabled() && store.isTrialEligible(player.getName(), uuid)) {
                authenticatedPlayers.remove(uuid);
                passwordVerifiedPlayers.remove(uuid);
                sendRegisterReminder(player, now);
                continue;
            }
            if (!hasPass) {
                passwordVerifiedPlayers.remove(uuid);
                if (authenticatedPlayers.remove(uuid)) {
                    freezeLocations.put(uuid, player.getLocation().clone());
                    player.closeInventory();
                    messages.send(player, "pass.expired");
                    audit.log(AuditLog.PASS_EXPIRE, player.getName(), player.getName(), getPlayerIp(player));
                    plugin.luckPermsPassSync().onPassExpired(player.getName());
                    fireEventSync(new PassExpiredEvent(player.getName()));
                    sendNoPassPrompt(player, true);
                    continue;
                }
                sendNoPassReminder(player, now);
                kickIfLoginTimedOut(player, now);
                continue;
            }

            long passUntil = store.getAccount(player.getName())
                    .map(account -> account.passUntil)
                    .orElse(0L);
            if (!checkExpiryWarnings(player, passUntil, now)) {
                continue;
            }

            if (!store.isRegistered(player.getName())) {
                authenticatedPlayers.remove(uuid);
                passwordVerifiedPlayers.remove(uuid);
                sendRegisterReminder(player, now);
                continue;
            }

            if (authenticatedPlayers.contains(uuid)) {
                // Обязательную 2FA включили на живом сервере (/pass reload): игрока в игре
                // без аутентификатора отправляем на привязку, а не ждём его следующего входа.
                if (requiresTotpEnrollment(player.getName())) {
                    beginForcedEnrollment(player, null);
                }
                continue;
            }

            if (requiresTotpEnrollment(player.getName()) && passwordVerifiedPlayers.contains(uuid)) {
                sendTotpEnrollmentReminder(player, now);
            } else if (config.totpEnabled() && store.isTotpEnabled(player.getName())
                    && passwordVerifiedPlayers.contains(uuid)) {
                sendTwoFactorReminder(player, now);
            } else {
                sendLoginReminder(player, now);
            }
        }
    }

    /**
     * Shows pass expiry warnings (7 days / 3 days / 1 day / 1 hour by default) and the final countdown bar.
     *
     * @return {@code true} when the regular reminder flow may continue for this player
     */
    private boolean checkExpiryWarnings(Player player, long passUntil, long now) {
        if (passUntil <= 0) return true;
        long remainingMinutes = Math.max(0, (passUntil - now) / 60_000L);
        if (config.expiryBossBar()) {
            long lastHourMinutes = config.expiryWarningMinutes().isEmpty()
                    ? 60
                    : config.expiryWarningMinutes().get(config.expiryWarningMinutes().size() - 1);
            if (remainingMinutes <= lastHourMinutes) {
                showExpiryBar(player, passUntil, now);
            } else {
                hideExpiryBar(player.getUniqueId());
            }
        }
        List<Long> warnings = config.expiryWarningMinutes();
        for (Long warnMinutes : warnings) {
            if (remainingMinutes > warnMinutes) continue;
            if (store.wasExpiryWarningSent(player.getName(), warnMinutes)) continue;
            store.markExpiryWarningSent(player.getName(), warnMinutes);
            messages.send(player, "pass.expiring",
                    "remaining", AccessManager.describeRemaining(warnMinutes * 60_000L),
                    "until", formatTime(passUntil),
                    "renew", config.hasEffectiveDonateUrl() ? config.effectiveDonateUrl() : "/pass");
            if (config.expiryWarningSound()) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 0.8f);
            }
            break;
        }
        return true;
    }

    private void showExpiryBar(Player player, long passUntil, long now) {
        long total = Math.max(1L, passUntil - now);
        long lastHour = Math.min(total, TimeUnit.HOURS.toMillis(1));
        float progress = Math.clamp((float) total / (float) Math.max(1L, lastHour), 0.0f, 1.0f);
        BossBar bar = expiryBars.computeIfAbsent(player.getUniqueId(), ignored -> BossBar.bossBar(
                Component.empty(), 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS));
        bar.name(messages.get("pass.expiry-bar", "remaining", AccessManager.describeRemaining(total)));
        bar.progress(progress);
        bar.color(progress < 0.2f ? BossBar.Color.RED : BossBar.Color.YELLOW);
        player.showBossBar(bar);
    }

    /** Hides the countdown bar of a player. */
    public void hideExpiryBar(UUID uuid) {
        BossBar bar = expiryBars.remove(uuid);
        if (bar != null) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.hideBossBar(bar);
            }
        }
    }

    /** Kicks players that never authenticated within {@code pass.kick-after-seconds}. */
    private void kickIfLoginTimedOut(Player player, long now) {
        int limitSeconds = config.kickAfterSeconds();
        if (limitSeconds <= 0) return;
        long joined = joinedAt.getOrDefault(player.getUniqueId(), now);
        if (now - joined < limitSeconds * 1000L) {
            return;
        }
        player.kick(messages.get("pass.kick-timeout", "seconds", limitSeconds));
    }

    private void sendNoPassReminder(Player player, long now) {
        player.sendActionBar(messages.get("no-pass.reminder",
                "price", config.minimumDonationRubles(), "player", player.getName()));
        long lastChat = lastChatReminderAt.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastChat >= CHAT_REMINDER_INTERVAL_MILLIS) {
            lastChatReminderAt.put(player.getUniqueId(), now);
            sendNoPassPrompt(player, false);
        }
    }

    private void sendRegisterReminder(Player player, long now) {
        player.sendActionBar(messages.get("register.reminder"));
        long lastChat = lastChatReminderAt.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastChat >= CHAT_REMINDER_INTERVAL_MILLIS) {
            lastChatReminderAt.put(player.getUniqueId(), now);
            sendRegisterPrompt(player, false);
        }
    }

    private void sendLoginReminder(Player player, long now) {
        player.sendActionBar(messages.get("login.reminder"));
        long lastChat = lastChatReminderAt.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastChat >= CHAT_REMINDER_INTERVAL_MILLIS) {
            lastChatReminderAt.put(player.getUniqueId(), now);
            sendLoginPrompt(player, false);
        }
    }

    private void sendTwoFactorReminder(Player player, long now) {
        player.sendActionBar(messages.get("two-factor.reminder"));
        long lastChat = lastChatReminderAt.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastChat >= CHAT_REMINDER_INTERVAL_MILLIS) {
            lastChatReminderAt.put(player.getUniqueId(), now);
            sendTwoFactorPrompt(player, false);
        }
    }

    /** Напоминание тому, кто подтвердил пароль, но ещё не привязал обязательную 2FA. */
    private void sendTotpEnrollmentReminder(Player player, long now) {
        player.sendActionBar(messages.get("two-factor.required.reminder"));
        long lastChat = lastChatReminderAt.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastChat >= CHAT_REMINDER_INTERVAL_MILLIS) {
            lastChatReminderAt.put(player.getUniqueId(), now);
            sendTotpEnrollmentPrompt(player, false);
        }
    }

    public void sendNoPassPrompt(Player player, boolean showTitle) {
        if (!config.passEnabled()) {
            return; // стена проходки выключена (pass.enabled: false) — подсказок не шлём
        }

        player.sendMessage(Component.empty());
        messages.sendList(player, "no-pass.prompt",
                "price", config.minimumDonationRubles(),
                "days", config.grantDays(),
                "player", player.getName());
        // ссылок на оплату в шаблоне нет: кликабельные теги через %placeholder% не проходят
        // парсер. С 1.4.39 показ ссылки НЕ зависит от enabled: флаг выключает только
        // API-опрос, а ссылка живёт по наличию URL (режим «только магазин, без API»)
        if (config.trademc().enabled()) {
            if (config.hasEffectiveDonateUrl()) {
                sendPaymentLinkLine(player, "TradeMC", config.effectiveDonateUrl());
            }
        } else {
            if (config.hasPaymentUrl()) {
                sendPaymentLinkLine(player, "DonatePay", config.donatePayPaymentUrl());
            }
            if (!config.easyDonatePaymentUrl().isBlank()) {
                sendPaymentLinkLine(player, "EasyDonate", config.easyDonatePaymentUrl());
            }
            if (!config.boosty().linkForPlayers().isBlank()) {
                sendPaymentLinkLine(player, "Boosty", config.boosty().linkForPlayers());
            }
        }

        if (showTitle) {
            Title title = Title.title(
                    messages.get("no-pass.title"),
                    messages.get("no-pass.title-subtitle",
                            "price", config.minimumDonationRubles(),
                            "player", player.getName()),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(6), Duration.ofMillis(500))
            );
            player.showTitle(title);
        }
    }

    /** Кликабельная строка «Открыть страницу оплаты <сервис>» — собирается вне messages.yml. */
    private void sendPaymentLinkLine(Player player, String service, String url) {
        player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                "<click:open_url:'" + url + "'>"
                + "<hover:show_text:'<green>Нажмите, чтобы перейти к оплате проходки</green>'>"
                + "<aqua><bold><underlined>[ Открыть страницу оплаты " + service + " ]</underlined></bold></aqua>"
                + "</hover></click>"));
    }

    public void sendRegisterPrompt(Player player, boolean showTitle) {
        boolean trial = config.trialEnabled() && store.isTrialEligible(player.getName(), player.getUniqueId());
        if (trial) messages.send(player, "trial.welcome", "days", config.trialDays());
        messages.sendList(player, trial ? "trial.register-prompt" : "register.prompt");
        if (showTitle) {
            Title title = Title.title(
                    messages.get("register.title"),
                    messages.get("register.title-subtitle"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofMillis(500))
            );
            player.showTitle(title);
        }
        if (showTitle) {
            showAuthDialog(player, PlayerStage.NEEDS_REGISTER);
        }
    }

    public void sendLoginPrompt(Player player, boolean showTitle) {
        messages.sendList(player, "login.prompt");
        if (showTitle) {
            Title title = Title.title(
                    messages.get("login.title"),
                    messages.get("login.title-subtitle"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofMillis(500))
            );
            player.showTitle(title);
        }
        if (showTitle) {
            showAuthDialog(player, PlayerStage.NEEDS_LOGIN);
        }
    }

    public void sendTwoFactorPrompt(Player player, boolean showTitle) {
        messages.sendList(player, "two-factor.prompt");
        if (showTitle) {
            Title title = Title.title(
                    messages.get("two-factor.title"),
                    messages.get("two-factor.title-subtitle"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofMillis(500))
            );
            player.showTitle(title);
        }
        if (showTitle) {
            showAuthDialog(player, PlayerStage.NEEDS_2FA);
        }
    }

    /** Короткая подсказка «привяжите 2FA» для стадии {@link PlayerStage#NEEDS_2FA_SETUP}. */
    public void sendTotpEnrollmentPrompt(Player player, boolean showTitle) {
        messages.sendList(player, "two-factor.required.prompt");
        if (showTitle) {
            Title title = Title.title(
                    messages.get("two-factor.title"),
                    messages.get("two-factor.required.title-subtitle"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofMillis(500))
            );
            player.showTitle(title);
        }
    }

    /**
     * Переводит игрока, уже подтвердившего пароль, в стадию обязательной привязки TOTP:
     * снимает полный доступ, готовит секрет и выдаёт инструкцию. Используется при регистрации,
     * входе, восстановлении IP-сессии и при включении {@code totp.required} на живом сервере.
     *
     * @param introKey сообщение-переход («пароль верный», «сессия восстановлена») либо {@code null}
     */
    public void beginForcedEnrollment(Player player, String introKey) {
        if (player == null || !player.isOnline()) return;
        UUID uuid = player.getUniqueId();
        authenticatedPlayers.remove(uuid);
        passwordVerifiedPlayers.add(uuid);
        freezeLocations.putIfAbsent(uuid, player.getLocation().clone());
        lastChatReminderAt.put(uuid, System.currentTimeMillis());
        authSource.put(uuid, "2fa-enrollment");

        player.clearTitle();
        if (introKey != null) {
            messages.send(player, introKey);
        }
        messages.send(player, "two-factor.required.header");
        messages.sendList(player, "two-factor.required.intro");
        String secret = pendingTotpSecrets.computeIfAbsent(uuid, ignored -> TotpService.generateSecret());
        sendTotpSetupInstructions(player, secret);
        player.sendActionBar(messages.get("two-factor.required.reminder"));
        player.showTitle(Title.title(
                messages.get("two-factor.title"),
                messages.get("two-factor.required.title-subtitle"),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(6), Duration.ofMillis(500))));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f);
        logger.info("Player " + player.getName() + " must enroll TOTP 2FA before entering the game.");
    }

    /** Итог опроса DonatePay: сколько новых транзакций обработали (или текст ошибки). */
    public record DonatePollReport(int processed, String error) {
        public static DonatePollReport skipped() {
            return new DonatePollReport(0, null);
        }
    }

    /**
     * Опрос всех настроенных донатных провайдеров (DonatePay, EasyDonate, …). Каждый
     * провайдер живёт независимо: своя серия отказов, тревога и курсор — падение одного
     * сервиса не блокирует выдачу через другой.
     */
    private DonatePollReport pollDonationsSafely() {
        if (config.trademc().enabled()) {
            // Режим TradeMC: все API-опросы донат-провайдеров выключены единым переключателем
            return DonatePollReport.skipped();
        }
        if (!donationPollRunning.compareAndSet(false, true)) {
            return DonatePollReport.skipped();
        }
        try {
            int processed = 0;
            StringBuilder errors = new StringBuilder();
            for (ProviderPulse pulse : donationPulses.values()) {
                if (!pulse.provider.ready()) {
                    continue;
                }
                DonatePollReport report = pollProviderOnce(pulse);
                processed += report.processed();
                if (report.error() != null && !report.error().isBlank()) {
                    if (errors.length() > 0) {
                        errors.append("; ");
                    }
                    errors.append(pulse.provider.displayName()).append(": ").append(report.error());
                }
            }
            return processed == 0 && errors.length() == 0
                    ? DonatePollReport.skipped()
                    : new DonatePollReport(processed, errors.length() == 0 ? null : errors.toString());
        } finally {
            donationPollRunning.set(false);
        }
    }

    /** Один цикл опроса конкретного провайдера; сторожевые счётчики — при нём. */
    private DonatePollReport pollProviderOnce(ProviderPulse pulse) {
        String source = pulse.provider.key();
        pulse.attempts.incrementAndGet();
        long apiCallStartedNanos = System.nanoTime();
        int processed = 0;
        try {
            if (!store.isDonationCursorInitialized(source)) {
                List<DonatePayClient.Transaction> recent = pulse.provider.fetchRecentSuccessful(100);
                long latest = 0;
                long backfillSince = System.currentTimeMillis() - 24L * 60 * 60 * 1000L;
                for (DonatePayClient.Transaction transaction : recent) {
                    latest = Math.max(latest, transaction.id());
                    // DonatePay — исторически игнорируем прошлое; EasyDonate — backfill за сутки
                    boolean tooOld = RegistrationStore.DONATION_SOURCE_DONATEPAY.equals(source)
                            ? transaction.createdAt() < serverStartedAtMillis
                            : (transaction.createdAt() > 0 && transaction.createdAt() < backfillSince);
                    if (tooOld || store.isDonationProcessed(source, transaction.id())) {
                        continue;
                    }
                    donations.process(transaction, System.currentTimeMillis(), true, source);
                    store.markDonationProcessed(source, transaction.id());
                    processed++;
                    pulse.processedKopecks.addAndGet(transactionKopecks(transaction));
                }
                store.initializeDonationCursor(source, latest);
                logger.info(pulse.provider.displayName() + " transaction cursor initialized at ID " + latest
                        + (RegistrationStore.DONATION_SOURCE_DONATEPAY.equals(source)
                        ? " (payments prior to initial setup are ignored)."
                        : " (payments older than 24h before first enable are ignored — backfill)."));
                pulse.latency.add((System.nanoTime() - apiCallStartedNanos) / 1_000_000L);
                markDonatePollSuccess(pulse);
                pulse.processed.addAndGet(processed);
                return new DonatePollReport(processed, null);
            }

            long cursor = store.getDonationCursor(source);
            // API отдаёт максимум 100 транзакций за вызов. Флуд донатов (за цикл ≥100)
            // раньше беззвучно «обрезался» до первой сотни. Теперь страницы дочитываются
            // — каждая следующая честно ждёт 20-секундное окно API (throttle внутри клиента).
            int pages = 1;
            while (true) {
                long cursorBeforePage = cursor;
                List<DonatePayClient.Transaction> transactions = pulse.provider.fetchAfter(cursor, 100);
                for (DonatePayClient.Transaction transaction : transactions) {
                    if (transaction.id() <= cursor || store.isDonationProcessed(source, transaction.id())) {
                        continue;
                    }
                    DonationService.Outcome outcome = donations.process(
                            transaction, System.currentTimeMillis(), true, source);
                    store.markDonationProcessed(source, transaction.id());
                    runDonateActions(transaction, outcome);
                    cursor = Math.max(cursor, transaction.id());
                    processed++;
                    pulse.processedKopecks.addAndGet(transactionKopecks(transaction));
                }
                if (transactions.size() < 100) {
                    break; // неполная страница — хвоста больше нет
                }
                if (cursor == cursorBeforePage) {
                    break; // 100 записей, и все старые — не крутимся по кругу
                }
                if (pages >= MAX_PAGES_PER_POLL) {
                    logger.warning(pulse.provider.displayName()
                            + ": донаты льются потоком — прочитано " + pages
                            + " страниц по 100 за цикл, остаток дочитает следующий опрос (курсор #"
                            + cursor + ")");
                    break;
                }
                pages++;
                logger.info(pulse.provider.displayName()
                        + ": страница из 100 транзакций заполнена целиком — догружаю следующую"
                        + " через окно API (курсор #" + cursor + ")");
            }
            pulse.latency.add((System.nanoTime() - apiCallStartedNanos) / 1_000_000L);
            markDonatePollSuccess(pulse);
            pulse.processed.addAndGet(processed);
            return new DonatePollReport(processed, null);
        } catch (Exception exception) {
            String message = exception.getMessage();
            // исключения клиента уже начинаются с имени сервиса («DonatePay returned HTTP …») —
            // убираем дубль, иначе в логах читается «DonatePay DonatePay returned …»
            String servicePrefix = pulse.provider.displayName() + " ";
            if (message != null && message.length() > servicePrefix.length()
                    && message.startsWith(servicePrefix)) {
                message = message.substring(servicePrefix.length());
            }
            markDonatePollFailure(pulse, message);
            warnDonatePayThrottled(pulse.provider.displayName() + " " + message);
            return new DonatePollReport(processed, message == null || message.isBlank()
                    ? "unknown error" : message);
        }
    }

    /** Успешный опрос: связь есть; если была тревога — шлём «%service% снова на связи». */
    private void markDonatePollSuccess(ProviderPulse pulse) {
        String service = pulse.provider.displayName();
        pulse.failStreak.set(0);
        pulse.lastSuccessAtMillis = System.currentTimeMillis();
        pulse.lastError = null;
        pulse.successes.incrementAndGet();
        if (DonatePulse.shouldAlertRecovery(pulse.outageAlerted)) {
            pulse.outageAlerted = false;
            pulse.lastAlertAtMillis = 0;
            logger.info(service + " is reachable again — donation auto-check recovered.");
            audit.log(AuditLog.DONATEPAY_UP, "watcher", "", "",
                    AuditLog.details("provider", service, "lastErrorCleared", true));
            notifications.notify("donatepay.up",
                    (messages == null ? "%service% UP" : messages.raw("donation.notify.up-title"))
                            .replace("%service%", service),
                    (messages == null ? "" : messages.raw("donation.notify.up-desc"))
                            .replace("%service%", service),
                    "good");
            if (config.donateAlertAdmins()) {
                notifyAdminsOnline("donation.alert-online", "service", service);
            }
        }
    }

    /** Сбой опроса: серия нарастает; на переходе через порог — разовая тревога админам. */
    private void markDonatePollFailure(ProviderPulse pulse, String error) {
        String service = pulse.provider.displayName();
        pulse.lastError = error;
        pulse.failures.incrementAndGet();
        int streak = pulse.failStreak.incrementAndGet();
        if (DonatePulse.shouldAlertOutage(streak, config.donateAlertFailStreak(), pulse.outageAlerted)) {
            pulse.outageAlerted = true;
            pulse.lastAlertAtMillis = System.currentTimeMillis();
            logger.warning(service + " appears DOWN (" + streak + " failed polls in a row): "
                    + (error == null ? "unknown error" : error));
            audit.log(AuditLog.DONATEPAY_DOWN, "watcher", "", "",
                    AuditLog.details("provider", service, "fails", streak,
                            "error", error == null ? "unknown" : error));
            Messages live = this.messages;
            notifications.notify("donatepay.down",
                    (live == null ? "%service% DOWN" : live.raw("donation.notify.down-title"))
                            .replace("%service%", service),
                    (live == null ? "Серия отказов: " + streak : live.raw("donation.notify.down-desc")
                            .replace("%fails%", String.valueOf(streak))
                            .replace("%error%", error == null ? "unknown" : error)
                            .replace("%service%", service)),
                    "bad");
            if (config.donateAlertAdmins()) {
                notifyAdminsOnline("donation.alert-offline",
                        "service", service, "fails", streak,
                        "error", error == null ? "unknown" : error);
            }
            // эпизод объявлен — один verifyToken через окно API разберёт точную причину
            if (RegistrationStore.DONATION_SOURCE_DONATEPAY.equals(pulse.provider.key())) {
                diagnoseDonatePayOutage(error);
            }
        }
    }

    /**
     * Разбор причины отказа DonatePay одной строкой для алертов, чата и логов —
     * вместо голого «returned HTTP 429» админ видит «душит лимит / токен отклонён / сеть».
     */
    public String describeDonatePayFailure(Throwable error) {
        DonatePayClient.DonatePayException.Kind kind =
                error instanceof DonatePayClient.DonatePayException donatePayException
                        ? donatePayException.kind() : DonatePayClient.DonatePayException.Kind.UNKNOWN;
        String detail = error == null ? "" : error.getMessage();
        if (detail == null) detail = "";
        detail = detail.replace('\n', ' ').replace('\r', ' ').trim();
        if (detail.length() > 80) detail = detail.substring(0, 79) + "…";
        String key = switch (kind) {
            case RATE_LIMITED -> "donation.diagnosis-rate-limited";
            case UNAUTHORIZED -> "donation.diagnosis-unauthorized";
            case UNREACHABLE -> "donation.diagnosis-unreachable";
            case BAD_RESPONSE -> "donation.diagnosis-bad-response";
            case UNKNOWN -> "donation.diagnosis-unknown";
        };
        Messages live = this.messages;
        return live == null ? kind.name() + (detail.isEmpty() ? "" : " — " + detail)
                : Messages.stripFormatting(live.raw(key, "detail", detail));
    }

    /**
     * Разовая диагностика в момент объявления эпизода отказа: один verifyToken
     * (через то же 20-секундное окно API) — и админам падает точный вердикт
     * («токен мёртв» / «сеть» / «лимит» / «живо, сбоит только опрос»).
     */
    private void diagnoseDonatePayOutage(String lastPollError) {
        if (donatePay == null) {
            return;
        }
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                String diagnosis;
                try {
                    DonatePayClient.UserInfo user = donatePay.verifyToken();
                    String wallet = config.donatePayShowBalance()
                            ? (user.name().isBlank() ? "?" : user.name()) : "(hidden)";
                    Messages live = this.messages;
                    diagnosis = live == null
                            ? "API OK, token valid (wallet '" + wallet + "') — /transactions polling is what fails"
                            : Messages.stripFormatting(live.raw("donation.diagnosis-endpoint-ok",
                                    "wallet", wallet,
                                    "detail", lastPollError == null || lastPollError.isBlank()
                                            ? "?" : lastPollError));
                } catch (Exception exception) {
                    diagnosis = describeDonatePayFailure(exception);
                }
                logger.warning("DonatePay outage diagnosis: " + diagnosis);
                audit.log(AuditLog.DONATEPAY_DOWN, "watcher", "", "",
                        AuditLog.details("provider", "DonatePay", "diagnosis", diagnosis));
                if (config.donateAlertAdmins()) {
                    notifyAdminsOnline("donation.alert-diagnosis",
                            "service", "DonatePay", "diagnosis", diagnosis);
                }
            });
        } catch (IllegalStateException ignored) {
            // плагин выключился, пока собирали диагностику — она уже некому нужна
        }
    }

    /** Heartbeat-строки по провайдерам + напоминания об очереди — видно, что сторож жив. */
    private void donateHeartbeat() {
        PluginConfig currentConfig = this.config;
        if (currentConfig.trademc().enabled()) {
            return; // TradeMC-режим: провайдерские пульсы не работают — стучать не о чем
        }
        for (ProviderPulse pulse : donationPulses.values()) {
            if (!pulse.provider.ready()) {
                continue;
            }
            String line = DonatePulse.heartbeatLine(pulse.provider.displayName(),
                    pulse.lastSuccessAtMillis, System.currentTimeMillis(),
                    pulse.failStreak.get(), pulse.lastError);
            if (line.endsWith(": OK") || line.contains(" heartbeat: OK")) {
                logger.info(line);
            } else {
                logger.warning(line);
            }
            // повторные тревоги, если отказ затянулся (раз в alert-repeat-minutes)
            if (pulse.outageAlerted && DonatePulse.repeatDue(pulse.lastAlertAtMillis,
                    System.currentTimeMillis(), currentConfig.donateAlertRepeatMinutes())) {
                pulse.lastAlertAtMillis = System.currentTimeMillis();
                int streak = pulse.failStreak.get();
                logger.warning(pulse.provider.displayName() + " STILL DOWN — "
                        + streak + " failed polls in a row.");
                if (currentConfig.donateAlertAdmins()) {
                    notifyAdminsOnline("donation.alert-offline",
                            "service", pulse.provider.displayName(), "fails", streak,
                            "error", pulse.lastError == null ? "unknown" : pulse.lastError);
                }
            }
        }

        // залежавшиеся в очереди донаты (общая очередь всех провайдеров)
        int staleHours = currentConfig.donatePendingNotifyHours();
        if (staleHours > 0) {
            List<RegistrationStore.DonationRecord> pendingNow = donations.pending(200);
            long[] stamps = new long[pendingNow.size()];
            for (int i = 0; i < pendingNow.size(); i++) {
                stamps[i] = pendingNow.get(i).createdAt;
            }
            int stale = DonatePulse.countStalePending(stamps, System.currentTimeMillis(), staleHours);
            if (stale > 0 && DonatePulse.repeatDue(donateLastStalePendingNotifyAtMillis,
                    System.currentTimeMillis(), staleHours * 60)) {
                donateLastStalePendingNotifyAtMillis = System.currentTimeMillis();
                logger.warning("Donations pending queue: " + stale
                        + " donation(s) wait for a decision longer than " + staleHours + " h — /pass pending.");
                if (currentConfig.donateAlertAdmins()) {
                    notifyAdminsOnline("donation.alert-pending-stale",
                            "count", stale, "hours", staleHours);
                }
            }
        }
    }

    /** Рассылка всем онлайн-админам (chrdkregister.admin) из асинхронного контекста. */
    private void notifyAdminsOnline(String key, Object... params) {
        Messages liveMessages = this.messages;
        if (liveMessages == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (org.bukkit.entity.Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.hasPermission("chrdkregister.admin")) {
                    liveMessages.send(player, key, params);
                }
            }
        });
    }

    /** Карточка сторожа одного провайдера для {@code /pass health}. */
    public record DonateHealthCard(String serviceKey, String serviceName, boolean ready,
                                   DonatePulse.State state, long lastSuccessAtMillis, int failStreak,
                                   String lastError, long attempts, long successes, long failures,
                                   long processedTransactions, int pollSeconds, long cursorId,
                                   int uptimePercent, long avgLatencyMs, long maxLatencyMs,
                                   long apiWaitMillis, long sessionSumKopecks) {
    }

    /** Карточки всех провайдеров в фиксированном порядке (donatepay, easydonate). */
    public java.util.List<DonateHealthCard> donateHealthCards() {
        java.util.List<DonateHealthCard> cards = new java.util.ArrayList<>();
        if (config.trademc().enabled()) {
            return cards; // TradeMC-режим: провайдеры выключены — карточек нет
        }
        for (ProviderPulse pulse : donationPulses.values()) {
            long attempts = pulse.attempts.get();
            String source = pulse.provider.key();
            cards.add(new DonateHealthCard(source, pulse.provider.displayName(),
                    pulse.provider.ready(),
                    DonatePulse.classify(pulse.lastSuccessAtMillis, pulse.failStreak.get()),
                    pulse.lastSuccessAtMillis, pulse.failStreak.get(), pulse.lastError,
                    attempts, pulse.successes.get(), pulse.failures.get(), pulse.processed.get(),
                    config.donatePollSeconds(), store.getDonationCursor(source),
                    DonatePulse.uptimePercent(attempts, pulse.successes.get()),
                    pulse.latency.avg(), pulse.latency.max(),
                    RegistrationStore.DONATION_SOURCE_DONATEPAY.equals(source)
                            ? donatePayApiWaitMillis() : 0,
                    pulse.processedKopecks.get()));
        }
        return cards;
    }

    /** Reprocesses payments that are still waiting for a decision (used by {@code /pass pending retry}). */
    public int reprocessPendingDonations() {
        int processed = 0;
        for (RegistrationStore.DonationRecord record : donations.pending(200)) {
            if (record.donorName == null || record.donorName.isBlank()) continue;
            DonatePayClient.Transaction transaction = new DonatePayClient.Transaction(
                    record.id,
                    record.donorName,
                    new BigDecimal(record.amountKopecks).movePointLeft(2),
                    "success",
                    "donation",
                    record.createdAt);
            DonationService.Outcome outcome = donations.process(transaction,
                    System.currentTimeMillis(), false, record.source);
            if (outcome.granted()) {
                processed++;
            }
        }
        return processed;
    }

    /* ------------------------------------------------------------------ *
     *  Sessions and logout
     * ------------------------------------------------------------------ */

    /** Shows a short notice about how the player got in and how long the session lasts. */
    private void showSessionNotice(Player player) {
        String source = authSource.getOrDefault(player.getUniqueId(), "session");
        if (!"session".equals(source)) return;
        messages.send(player, "session.notice", "session_minutes", config.sessionMinutes());
    }

    /** Handles {@code /logout}: clears the IP session of the sender and locks them again. */
    public boolean handleLogoutCommand(Player player) {
        long now = System.currentTimeMillis();
        String ip = getPlayerIp(player);
        boolean cleared = store.clearSession(player.getName(), ip);
        if (!cleared) {
            store.clearSessions(player.getName());
        }
        authenticatedPlayers.remove(player.getUniqueId());
        passwordVerifiedPlayers.remove(player.getUniqueId());
        authSource.remove(player.getUniqueId());
        audit.log(AuditLog.SESSION_END, player.getName(), player.getName(), ip,
                AuditLog.details("reason", "logout"));
        if (!store.hasActivePass(player.getName(), now)) {
            sendNoPassPrompt(player, false);
            return true;
        }
        if (!store.isRegistered(player.getName())) {
            sendRegisterPrompt(player, false);
            return true;
        }
        freezeLocations.put(player.getUniqueId(), player.getLocation().clone());
        player.closeInventory();
        messages.send(player, "logout.success");
        sendLoginPrompt(player, true);
        return true;
    }

    /** Lists the known sessions of a player for {@code /session} and {@code /pass sessions}. */
    public List<RegistrationStore.SessionEntry> sessionsOf(String playerName) {
        return store.listSessions(playerName);
    }

    /** Clears every session of a player (admin and self-service paths). */
    public boolean clearAllSessions(String playerName) {
        boolean cleared = store.clearSessions(playerName);
        if (cleared) {
            audit.log(AuditLog.SESSION_END, "admin", playerName, "",
                    AuditLog.details("reason", "sessions cleared"));
        }
        return cleared;
    }

    /** Reapplies the locked state of an online player (used after an admin session reset). */
    public void lockOnlinePlayer(String playerName, String message) {
        Player online = Bukkit.getPlayerExact(playerName);
        if (online == null) return;
        UUID uuid = online.getUniqueId();
        authenticatedPlayers.remove(uuid);
        passwordVerifiedPlayers.remove(uuid);
        authSource.remove(uuid);
        freezeLocations.put(uuid, online.getLocation().clone());
        online.closeInventory();
        if (message != null && !message.isBlank()) {
            online.sendMessage(Component.text(message, NamedTextColor.YELLOW));
        }
        sendLoginPrompt(online, true);
    }

    /**
     * Внеочередной опрос DonatePay (для {@code /pass recheck}); сам опрос асинхронный
     * и защищён от дублей, результат придёт в onDone уже на главном потоке.
     */
    public void forceDonationPoll(Consumer<DonatePollReport> onDone) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            DonatePollReport report = pollDonationsSafely();
            try {
                Bukkit.getScheduler().runTask(plugin, () -> onDone.accept(report));
            } catch (IllegalStateException ignored) {
                // плагин выключился, пока бежал опрос — результат просто некому отдать
            }
        });
    }

    /**
     * Сколько миллисекунд осталось до ближайшего разрешённого окна DonatePay API
     * (0 — можно звонить). DonatePay разрешает одну операцию в 20 секунд; GUI
     * и {@code /pass recheck} показывают это ожидание пользователю вместо тишины.
     */
    public long donatePayApiWaitMillis() {
        return donatePay == null ? 0 : donatePay.apiRateLimitWaitMillis();
    }

    /**
     * Округление миллисекунд ожидания до целых секунд «с запасом» для показа
     * пользователю: «через ~N с» (0 мс → 0, 1 мс → 1, 20_500 мс → 21).
     */
    public static int waitSeconds(long millis) {
        return millis <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (millis + 999L) / 1000L);
    }

    /** Сумма транзакции в копейках для счётчиков (null/отрицательные — как 0). */
    static long transactionKopecks(DonatePayClient.Transaction transaction) {
        if (transaction == null || transaction.amount() == null
                || transaction.amount().signum() <= 0) {
            return 0;
        }
        return transaction.amount().movePointRight(2).longValue();
    }

    /**
     * Асинхронно читает кошелёк DonatePay (GET /user) для GUI/диагностики.
     * Коллбэки — в главном потоке; {@code onFail} получает текст ошибки.
     */
    public void fetchDonatePayWallet(Consumer<DonatePayClient.UserInfo> onOk,
                                     Consumer<String> onFail) {
        fetchDonatePayWallet(onOk, (message, error) -> onFail.accept(message));
    }

    /**
     * Та же выборка кошелька, но коллбэк ошибки получает и само исключение —
     * кнопке «проверить токен» нужна разобранная {@link DonatePayClient.DonatePayException.Kind}
     * для точного вердикта ({@link #describeDonatePayFailure(Throwable)}).
     */
    public void fetchDonatePayWallet(Consumer<DonatePayClient.UserInfo> onOk,
                                     java.util.function.BiConsumer<String, Throwable> onFail) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                DonatePayClient.UserInfo user = donatePay.verifyToken();
                Bukkit.getScheduler().runTask(plugin, () -> onOk.accept(user));
            } catch (Exception exception) {
                String message = exception.getMessage();
                String text = message == null || message.isBlank() ? "unknown error" : message;
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> onFail.accept(text, exception));
                } catch (IllegalStateException ignored) {
                    // плагин выключился — отчёт некому отдать
                }
            }
        });
    }

    /**
     * {@code /pass testpay-live}: отправляет тестовый донат В КОШЕЛЁК DonatePay через
     * официальный set_notification (POST /api/v1/notification, notification=-1 —
     * без публичного алерта). Запись навсегда остаётся в истории кошелька —
     * команда только для админов, об этом честно пишем в ответе.
     */
    public void postTestDonateNotification(String donorName, long sumRubles, String comment,
                                           Consumer<String> onOk, Consumer<String> onFail,
                                           java.util.function.BiConsumer<Boolean, DonatePayClient.Transaction> onVerdict) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String status = donatePay.postNotification(donorName, sumRubles, comment);
                Bukkit.getScheduler().runTask(plugin, () -> onOk.accept(status));
                probeLiveTestVisible(donorName, sumRubles,
                        System.currentTimeMillis() + 70_000L, onVerdict);
            } catch (Exception exception) {
                String message = exception.getMessage();
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> onFail.accept(
                            message == null || message.isBlank() ? "unknown error" : message));
                } catch (IllegalStateException ignored) {
                    // плагин выключился — отчёт некому отдать
                }
            }
        });
    }

    /**
     * {@code /pass testpay-live}: после set_notification ~1 минуту проверяет, вернул ли
     * DonatePay запись в /transactions (тогда её подхватит обычный опрос) — и отдаёт вердикт.
     */
    private void probeLiveTestVisible(String donorName, long sumRubles, long deadline,
                                      java.util.function.BiConsumer<Boolean, DonatePayClient.Transaction> onVerdict) {
        try {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                DonatePayClient.Transaction found = findRecentLiveTest(donorName, sumRubles);
                try {
                    if (found != null) {
                        Bukkit.getScheduler().runTask(plugin, () -> onVerdict.accept(true, found));
                    } else if (System.currentTimeMillis() < deadline) {
                        probeLiveTestVisible(donorName, sumRubles, deadline, onVerdict);
                    } else {
                        Bukkit.getScheduler().runTask(plugin, () -> onVerdict.accept(false, null));
                    }
                } catch (IllegalStateException ignored) {
                    // плагин выключился — вердикт доносить некому
                }
            }, 160L); // повторная проба каждые ~8 с до дедлайна (~8 попыток)
        } catch (IllegalStateException ignored) {
            // плагин выключился во время ожидания
        }
    }

    /** Ищет в /transactions запись живого теста по нику, сумме и свежести (±20 минут). */
    private DonatePayClient.Transaction findRecentLiveTest(String donorName, long sumRubles) {
        try {
            long now = System.currentTimeMillis();
            for (DonatePayClient.Transaction transaction : donatePay.fetchRecentSuccessful(15)) {
                if (donorName.equalsIgnoreCase(transaction.donorName())
                        && transaction.amount() != null
                        && transaction.amount().longValue() == sumRubles
                        && Math.abs(now - transaction.createdAt()) < 20 * 60_000L) {
                    return transaction;
                }
            }
        } catch (Exception exception) {
            logger.fine("DonatePay live-test probe failed: " + exception.getMessage());
        }
        return null;
    }

    /**
     * Синтетическая транзакция DonatePay для {@code /pass testpay}: полный цикл
     * (матчинг, журнал, аудит, уведомления, выдача/продление) без обращения к API.
     * id отрицательный — тестовый диапазон, курсор боевых платежей не трогает.
     */
    public DonationService.Outcome simulateDonation(String playerName, int amountRubles) {
        return simulateDonation(playerName, amountRubles, RegistrationStore.DONATION_SOURCE_DONATEPAY);
    }

    /** {{@code /pass testpay <ник> <сумма> [сервис]}}: источник тоже идёт в журнал. */
    public DonationService.Outcome simulateDonation(String playerName, int amountRubles, String source) {
        long now = System.currentTimeMillis();
        DonatePayClient.Transaction transaction = new DonatePayClient.Transaction(
                -now, playerName, BigDecimal.valueOf(Math.max(0, amountRubles)), "success", "donation",
                now, true);
        DonationService.Outcome outcome = donations.process(transaction, now, false, source);
        runDonateActions(transaction, outcome); // прогоняем и donation-actions для обкатки шаблонов
        return outcome;
    }

    /** Performs a lightweight DonatePay API probe for {@code /pass doctor}. */
    public boolean probeDonatePay() {
        return probeDonationProvider(RegistrationStore.DONATION_SOURCE_DONATEPAY);
    }

    /** Живой прогон одного HTTP-запроса к донатному сервису (для {@code /pass doctor}). */
    public boolean probeDonationProvider(String source) {
        ProviderPulse pulse = donationPulses.get(source);
        if (pulse == null || !pulse.provider.ready()) {
            return false;
        }
        try {
            pulse.provider.fetchRecentSuccessful(1);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private void warnDonatePayThrottled(String detail) {
        long now = System.currentTimeMillis();
        String message = "DonatePay check failed: "
                + (detail == null || detail.isBlank() ? "unknown error" : detail);
        if (now - lastDonatePayWarningMillis < TimeUnit.MINUTES.toMillis(5)) {
            return;
        }
        lastDonatePayWarningMillis = now;
        logger.log(Level.WARNING, message);
        audit.log(AuditLog.DONATEPAY_ERROR, "server", "", null, AuditLog.details("error", message));
        notifications.notify("donatepay.error", "Ошибка DonatePay", message, "bad");
    }

    public static boolean isValidMinecraftName(String name) {
        return name != null && MINECRAFT_NAME.matcher(name.trim()).matches();
    }

    public static String getPlayerIp(Player player) {
        InetSocketAddress socketAddress = player.getAddress();
        if (socketAddress == null) return "";
        InetAddress address = socketAddress.getAddress();
        return address == null ? "" : address.getHostAddress();
    }

    public static String formatTimestamp(long epochMillis) {
        if (epochMillis <= 0) return "нет";
        return DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    /** Formats a timestamp in the timezone configured by {@code general.timezone}. */
    public String formatTime(long epochMillis) {
        if (epochMillis <= 0) return "нет";
        return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm z")
                .withZone(config.timeZone())
                .format(Instant.ofEpochMilli(epochMillis));
    }

    /** Formats a duration in the "2 дн. 3 ч." style used across messages. */
    public static String formatDuration(long millis) {
        return describeRemaining(millis);
    }

    /** Formats a remaining duration, e.g. {@code "2 дн. 3 ч."} or {@code "45 мин."}. */
    public static String describeRemaining(long millis) {
        long diff = Math.max(0, millis);
        long days = TimeUnit.MILLISECONDS.toDays(diff);
        long hours = TimeUnit.MILLISECONDS.toHours(diff) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(diff) % 60;
        if (days > 0) return days + " дн. " + hours + " ч.";
        if (hours > 0) return hours + " ч. " + minutes + " мин.";
        return Math.max(1, minutes) + " мин.";
    }

    public static String formatRemainingTime(long passUntilMillis, long nowMillis) {
        long diff = passUntilMillis - nowMillis;
        if (diff <= 0) return "истекла";
        long days = TimeUnit.MILLISECONDS.toDays(diff);
        long hours = TimeUnit.MILLISECONDS.toHours(diff) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(diff) % 60;
        if (days > 0) {
            return days + " дн. " + hours + " ч.";
        }
        if (hours > 0) {
            return hours + " ч. " + minutes + " мин.";
        }
        return Math.max(1, minutes) + " мин.";
    }
}
