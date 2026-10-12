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
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.Event;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.api.events.PassExpiredEvent;
import ru.cherdakmd.chrdkregister.api.events.PassGrantedEvent;
import ru.cherdakmd.chrdkregister.api.events.PassRevokedEvent;
import ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent;
import org.bukkit.scheduler.BukkitTask;

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
    private final AtomicBoolean donationPollRunning = new AtomicBoolean(false);
    private final AtomicInteger donationPollFailStreak = new AtomicInteger(0);
    private volatile long donateLastSuccessAtMillis;
    private volatile String donateLastError;
    private volatile boolean donateOutageAlerted;
    private volatile long donateLastAlertAtMillis;
    private final java.util.concurrent.atomic.AtomicLong donatePollAttempts = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong donatePollSuccesses = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong donatePollFailures = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong donateTransactionsProcessed = new java.util.concurrent.atomic.AtomicLong();

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
        this.audit = new AuditLog(plugin.getDataFolder().toPath(), logger, config.timeZone());
        this.notifications = new NotificationService(this::config, logger);
        this.throttle = new AuthThrottle(store, this::config);
        this.donations = new DonationService(store, this::config, audit, notifications, logger,
                messages, this::onPassGranted);
        this.logMasker = new LogMasker(logger);
        this.authDialogs = createAuthDialogs(plugin);
        this.totpQrMaps = new TotpQrMapService(this, logger);
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

        if (config.storageBackupOnStart()) {
            store.createBackup("start", config.storageBackupCount());
        }
        runHousekeeping();
        applyLogMasking();

        for (Player online : Bukkit.getOnlinePlayers()) {
            onPlayerJoin(online);
        }

        if (!config.donatePayReady()) {
            logger.warning("DonatePay API token is not set in plugins/CHRDK_F_REGISTER/config.yml. "
                    + "Automatic pass activation via DonatePay is paused until configured.");
        } else {
            logger.info("CHRDK_F_REGISTER active: minimum donation " + config.minimumDonationRubles()
                    + " RUB, pass duration " + config.grantDays() + " days.");
        }
    }

    public synchronized void reload(PluginConfig newConfig) {
        this.config = newConfig;
        this.donatePay.updateConfig(newConfig);
        stopTasks();

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
        logger.info("CHRDK_F_REGISTER storage flushed and notifications stopped.");
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
        if (!store.hasActivePass(playerName, nowMillis)) {
            authenticatedPlayers.remove(uuid);
            passwordVerifiedPlayers.remove(uuid);
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
        return PlayerStage.NEEDS_LOGIN;
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

        if (!store.hasActivePass(name, now)) {
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
            if (config.totpEnabled() && store.isTotpEnabled(name) && !config.sessionSkips2fa()) {
                // Security first: a stored IP session restores the password step only, 2FA is still required.
                passwordVerifiedPlayers.add(uuid);
                authSource.put(uuid, "session+2fa");
                audit.log(AuditLog.SESSION_RESTORE, name, name, ip,
                        AuditLog.details("twoFactorRequired", true));
                sendTwoFactorPrompt(player, true);
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
        if (stage == PlayerStage.NEEDS_LOGIN || stage == PlayerStage.NEEDS_2FA) {
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
        boolean registered = store.registerPassword(player.getName(), player.getUniqueId(), password, ip, now);
        if (!registered) {
            messages.send(player, "register.failed");
            return true;
        }

        completeAuthentication(player, "register.success");
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
                            + AuditLog.describeDuration(minutes * 60_000L) + ")", null, "CHRDK_F_REGISTER");
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
            completeAuthentication(player, "✓ Код 2FA подтверждён! Вы успешно вошли на сервер! Приятной игры!");
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
     * Starts TOTP 2FA enrollment for an authenticated player by generating a pending Base32 secret.
     */
    public boolean handleTwoFactorSetup(Player player) {
        if (getStage(player) != PlayerStage.AUTHENTICATED) {
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

        String secret = TotpService.generateSecret();
        pendingTotpSecrets.put(player.getUniqueId(), secret);
        sendTotpSetupInstructions(player, secret);
        return true;
    }

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
            giveTotpQrMap(player);
            messages.send(player, "two-factor.setup-instructions.qr-map");
        }
    }

    /**
     * Re-sends the 2FA binding QR map while a setup is pending ({@code /2fa qr}).
     */
    public boolean handleTwoFactorQrMap(Player player) {
        if (getStage(player) != PlayerStage.AUTHENTICATED) {
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
        giveTotpQrMap(player);
        messages.send(player, "two-factor.qr.sent");
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

    /** Hands the player a map with their personal otpauth:// QR (dropped at feet on full inventory). */
    private void giveTotpQrMap(Player player) {
        try {
            ItemStack mapItem = totpQrMaps.createMapItem(player.getWorld());
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(mapItem);
            leftover.values().forEach(rest ->
                    player.getWorld().dropItemNaturally(player.getLocation(), rest));
        } catch (Throwable throwable) {
            logger.log(Level.WARNING, "Не удалось выдать карту с QR-кодом 2FA игроку "
                    + player.getName(), throwable);
        }
    }

    /**
     * Confirms pending TOTP 2FA setup using a 6-digit code from the player's authenticator app.
     */
    public boolean handleTwoFactorConfirmSetup(Player player, String rawCode) {
        if (getStage(player) != PlayerStage.AUTHENTICATED) {
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
        messages.send(player, "two-factor.confirm.success");
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
        if (getStage(player) != PlayerStage.AUTHENTICATED) {
            messages.send(player, "two-factor.cancel.not-authenticated");
            return true;
        }
        String removed = pendingTotpSecrets.remove(player.getUniqueId());
        if (removed == null) {
            messages.send(player, "two-factor.cancel.nothing-pending");
        } else {
            messages.send(player, "two-factor.cancel.success");
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

        String description = source != null && source.startsWith("gift")
                ? "подарком"
                : "администратором";
        Player onlinePlayer = Bukkit.getPlayerExact(targetPlayerName);
        if (onlinePlayer != null) {
            notifyOnlinePassActivated(onlinePlayer, account.passUntil, description);
        }
        logger.info("Manual pass granted to " + targetPlayerName + " for " + lengthDays
                + " days (until " + formatTimestamp(account.passUntil) + ", source: " + source + ").");
        audit.log(AuditLog.PASS_GRANT_MANUAL, "admin", targetPlayerName, "",
                AuditLog.details("days", lengthDays, "source", source, "passUntil", account.passUntil));
        notifications.notify("pass.granted", "Проходка выдана вручную",
                "Игрок: " + targetPlayerName + "\nДней: " + lengthDays + "\nИсточник: " + source, "good");
        plugin.luckPermsPassSync().onPassGranted(targetPlayerName);
        fireEventSync(new PassGrantedEvent(targetPlayerName, account.passUntil, lengthDays, source));
        return account;
    }

    /**
     * Callback used by {@link DonationService} when a payment extends someone's pass.
     */
    public void onPassGranted(String playerName, long passUntil, String sourceDescription) {
        plugin.luckPermsPassSync().onPassGranted(playerName);
        int days = Math.max(0, (int) ((passUntil - System.currentTimeMillis()) / 86_400_000L));
        fireEventSync(new PassGrantedEvent(playerName, passUntil, days, sourceDescription));
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) {
            notifyOnlinePassActivated(online, passUntil, sourceDescription);
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
     */
    public boolean resetTwoFactor(String targetPlayerName) {
        boolean disabled = store.disableTotp(targetPlayerName);
        if (!disabled) return false;

        Player onlinePlayer = Bukkit.getPlayerExact(targetPlayerName);
        if (onlinePlayer != null) {
            UUID uuid = onlinePlayer.getUniqueId();
            pendingTotpSecrets.remove(uuid);
            messages.send(onlinePlayer, "pass.two-factor-reset");
            if (passwordVerifiedPlayers.remove(uuid) && !authenticatedPlayers.contains(uuid)) {
                store.touchSession(onlinePlayer.getName(), uuid, getPlayerIp(onlinePlayer), System.currentTimeMillis());
                completeAuthentication(onlinePlayer, "✓ Вход завершён (2FA была отключена администратором).");
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
        fireEventSync(new PlayerAuthCompletedEvent(player));
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
            if (config.totpEnabled() && store.isTotpEnabled(player.getName()) && passwordVerifiedPlayers.contains(uuid)) {
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
            boolean hasPass = store.hasActivePass(player.getName(), now);

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
                continue;
            }

            if (config.totpEnabled() && store.isTotpEnabled(player.getName()) && passwordVerifiedPlayers.contains(uuid)) {
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
                    "renew", config.hasPaymentUrl() ? config.donatePayPaymentUrl() : "/pass");
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

    public void sendNoPassPrompt(Player player, boolean showTitle) {
        player.sendMessage(Component.empty());
        messages.sendList(player, "no-pass.prompt",
                "price", config.minimumDonationRubles(),
                "days", config.grantDays(),
                "player", player.getName(),
                "payment-url", config.hasPaymentUrl() ? config.donatePayPaymentUrl() : "");

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

    public void sendRegisterPrompt(Player player, boolean showTitle) {
        messages.sendList(player, "register.prompt");
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

    /** Итог опроса DonatePay: сколько новых транзакций обработали (или текст ошибки). */
    public record DonatePollReport(int processed, String error) {
        public static DonatePollReport skipped() {
            return new DonatePollReport(0, null);
        }
    }

    /**
     * Опрос DonatePay. Это же и проверка связи: успех — связь есть (сбрасывает серию
     * отказов и шлёт «восстановление», если была тревога), ошибка HTTP — серия нарастает,
     * на переходе через donatepay.alert-fail-streak онлайн-админы узнают о падении
     * разом, а не из логов спустя часы.
     */
    private DonatePollReport pollDonationsSafely() {
        PluginConfig currentConfig = this.config;
        if (!currentConfig.donatePayReady() || !donationPollRunning.compareAndSet(false, true)) {
            return DonatePollReport.skipped();
        }
        int processed = 0;
        donatePollAttempts.incrementAndGet();
        try {
            if (!store.isDonationCursorInitialized()) {
                List<DonatePayClient.Transaction> recent = donatePay.fetchRecentSuccessful(100);
                long latest = 0;
                for (DonatePayClient.Transaction transaction : recent) {
                    latest = Math.max(latest, transaction.id());
                    if (transaction.createdAt() < serverStartedAtMillis || store.isDonationProcessed(transaction.id())) {
                        continue;
                    }
                    donations.process(transaction, System.currentTimeMillis(), true);
                    store.markDonationProcessed(transaction.id());
                    processed++;
                }
                store.initializeDonationCursor(latest);
                logger.info("DonatePay transaction cursor initialized at ID " + latest
                        + " (payments prior to initial setup are ignored).");
                markDonatePollSuccess();
                donateTransactionsProcessed.addAndGet(processed);
                return new DonatePollReport(processed, null);
            }

            long cursor = store.getDonationCursor();
            List<DonatePayClient.Transaction> transactions = donatePay.fetchAfter(cursor, 100);
            for (DonatePayClient.Transaction transaction : transactions) {
                if (transaction.id() <= cursor || store.isDonationProcessed(transaction.id())) {
                    continue;
                }
                donations.process(transaction, System.currentTimeMillis(), true);
                store.markDonationProcessed(transaction.id());
                cursor = Math.max(cursor, transaction.id());
                processed++;
            }
            markDonatePollSuccess();
            donateTransactionsProcessed.addAndGet(processed);
            return new DonatePollReport(processed, null);
        } catch (Exception exception) {
            String message = exception.getMessage();
            markDonatePollFailure(message);
            warnDonatePayThrottled(message);
            return new DonatePollReport(processed, message == null || message.isBlank()
                    ? "unknown error" : message);
        } finally {
            donationPollRunning.set(false);
        }
    }

    /** Успешный опрос: связь есть; если была тревога — шлём «DonatePay снова на связи». */
    private void markDonatePollSuccess() {
        donationPollFailStreak.set(0);
        donateLastSuccessAtMillis = System.currentTimeMillis();
        donateLastError = null;
        donatePollSuccesses.incrementAndGet();
        if (DonatePulse.shouldAlertRecovery(donateOutageAlerted)) {
            donateOutageAlerted = false;
            donateLastAlertAtMillis = 0;
            logger.info("DonatePay is reachable again — donation auto-check recovered.");
            audit.log(AuditLog.DONATEPAY_UP, "watcher", "", "",
                    AuditLog.details("lastErrorCleared", true));
            notifications.notify("donatepay.up",
                    messages == null ? "DonatePay UP" : messages.raw("donation.notify.up-title"),
                    messages == null ? "" : messages.raw("donation.notify.up-desc"),
                    "good");
            if (config.donateAlertAdmins()) {
                notifyAdminsOnline("donation.alert-online");
            }
        }
    }

    /** Сбой опроса: серия нарастает; на переходе через порог — разовая тревога админам. */
    private void markDonatePollFailure(String error) {
        donateLastError = error;
        donatePollFailures.incrementAndGet();
        int streak = donationPollFailStreak.incrementAndGet();
        if (DonatePulse.shouldAlertOutage(streak, config.donateAlertFailStreak(), donateOutageAlerted)) {
            donateOutageAlerted = true;
            donateLastAlertAtMillis = System.currentTimeMillis();
            logger.warning("DonatePay appears DOWN (" + streak + " failed polls in a row): "
                    + (error == null ? "unknown error" : error));
            audit.log(AuditLog.DONATEPAY_DOWN, "watcher", "", "",
                    AuditLog.details("fails", streak, "error", error == null ? "unknown" : error));
            Messages live = this.messages;
            notifications.notify("donatepay.down",
                    live == null ? "DonatePay DOWN" : live.raw("donation.notify.down-title"),
                    (live == null ? "Серия отказов: " + streak : live.raw("donation.notify.down-desc")
                            .replace("%fails%", String.valueOf(streak))
                            .replace("%error%", error == null ? "unknown" : error)),
                    "bad");
            if (config.donateAlertAdmins()) {
                notifyAdminsOnline("donation.alert-offline",
                        "fails", streak, "error", error == null ? "unknown" : error);
            }
        }
    }

    /** Heartbeat-строка в консоль раз в donatepay.health-seconds — видно, что сторож жив. */
    private void donateHeartbeat() {
        PluginConfig currentConfig = this.config;
        if (!currentConfig.donatePayReady()) {
            return;
        }
        String line = DonatePulse.heartbeatLine(donateLastSuccessAtMillis,
                System.currentTimeMillis(), donationPollFailStreak.get(), donateLastError);
        if (line.startsWith("DonatePay heartbeat: OK")) {
            logger.info(line);
        } else {
            logger.warning(line);
        }
        // повторные тревоги, если отказ затянулся (раз в donatepay.alert-repeat-minutes)
        if (donateOutageAlerted && DonatePulse.repeatDue(donateLastAlertAtMillis,
                System.currentTimeMillis(), currentConfig.donateAlertRepeatMinutes())) {
            donateLastAlertAtMillis = System.currentTimeMillis();
            int streak = donationPollFailStreak.get();
            logger.warning("DonatePay STILL DOWN — " + streak + " failed polls in a row.");
            if (currentConfig.donateAlertAdmins()) {
                notifyAdminsOnline("donation.alert-offline",
                        "fails", streak, "error", donateLastError == null ? "unknown" : donateLastError);
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

    /** Состояние связи (последний успех, серия отказов, последняя ошибка) для /pass doctor и др. */
    public record DonatePulseSnapshot(long lastSuccessAtMillis, int failStreak, String lastError) {
    }

    public DonatePulseSnapshot donatePulseSnapshot() {
        return new DonatePulseSnapshot(donateLastSuccessAtMillis,
                donationPollFailStreak.get(), donateLastError);
    }

    /** Полная карточка пульса для /pass health. */
    public record DonateHealthCard(DonatePulse.State state, long lastSuccessAtMillis, int failStreak,
                                   String lastError, long attempts, long successes, long failures,
                                   long processedTransactions, int pollSeconds, long cursorId) {
    }

    public DonateHealthCard donateHealthCard() {
        return new DonateHealthCard(
                DonatePulse.classify(donateLastSuccessAtMillis, donationPollFailStreak.get()),
                donateLastSuccessAtMillis, donationPollFailStreak.get(), donateLastError,
                donatePollAttempts.get(), donatePollSuccesses.get(), donatePollFailures.get(),
                donateTransactionsProcessed.get(),
                config.donatePollSeconds(), store.getDonationCursor());
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
            DonationService.Outcome outcome = donations.process(transaction, System.currentTimeMillis(), false);
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
     * Синтетическая транзакция DonatePay для {@code /pass testpay}: полный цикл
     * (матчинг, журнал, аудит, уведомления, выдача/продление) без обращения к API.
     * id отрицательный — тестовый диапазон, курсор боевых платежей не трогает.
     */
    public DonationService.Outcome simulateDonation(String playerName, int amountRubles) {
        long now = System.currentTimeMillis();
        DonatePayClient.Transaction transaction = new DonatePayClient.Transaction(
                -now, playerName, BigDecimal.valueOf(Math.max(0, amountRubles)), "success", "donation", now);
        return donations.process(transaction, now, false);
    }

    /** Performs a lightweight DonatePay API probe for {@code /pass doctor}. */
    public boolean probeDonatePay() {
        if (!config.donatePayReady()) {
            return false;
        }
        try {
            donatePay.fetchRecentSuccessful(1);
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
