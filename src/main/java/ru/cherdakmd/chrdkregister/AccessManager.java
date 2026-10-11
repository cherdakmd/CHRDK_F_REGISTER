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
import org.bukkit.plugin.java.JavaPlugin;
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
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private final JavaPlugin plugin;
    private final RegistrationStore store;
    private final DonatePayClient donatePay;
    private final Logger logger;

    private volatile PluginConfig config;
    private final Set<UUID> authenticatedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> passwordVerifiedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> pendingTotpSecrets = new ConcurrentHashMap<>();
    private final Map<UUID, Location> freezeLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastChatReminderAt = new ConcurrentHashMap<>();
    private final Map<UUID, String> authSource = new ConcurrentHashMap<>();
    private final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> expiryBars = new ConcurrentHashMap<>();
    private final AtomicBoolean donationPollRunning = new AtomicBoolean(false);

    private final AuditLog audit;
    private final NotificationService notifications;
    private final AuthThrottle throttle;
    private final DonationService donations;
    private final LogMasker logMasker;

    private BukkitTask donatePollTask;
    private BukkitTask reminderTask;
    private BukkitTask flushTask;
    private BukkitTask housekeepingTask;
    private volatile long lastBackupAtMillis;
    private volatile long lastPurgeAtMillis;
    private volatile long serverStartedAtMillis;
    private volatile long lastDonatePayWarningMillis;

    public AccessManager(
            JavaPlugin plugin,
            PluginConfig config,
            RegistrationStore store,
            DonatePayClient donatePay,
            Logger logger
    ) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.donatePay = donatePay;
        this.logger = logger;
        this.audit = new AuditLog(plugin.getDataFolder().toPath(), logger, config.timeZone());
        this.notifications = new NotificationService(this::config, logger);
        this.throttle = new AuthThrottle(store, this::config);
        this.donations = new DonationService(store, this::config, audit, notifications, logger,
                this::onPassGranted);
        this.logMasker = new LogMasker(logger);
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

    public synchronized void start() {
        stopTasks();
        this.serverStartedAtMillis = System.currentTimeMillis();

        long pollTicks = Math.max(20L * 15L, config.donatePollSeconds() * 20L);
        this.donatePollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::pollDonationsSafely, 20L * 5L, pollTicks);

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
                plugin, this::pollDonationsSafely, 20L * 2L, pollTicks);

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
        for (BossBar bar : expiryBars.values()) {
            bar.hide();
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
            player.sendMessage(Component.text("✓ Сессия авторизации восстановлена по IP. Приятной игры!", NamedTextColor.GREEN));
            player.sendActionBar(Component.text("Авторизация подтверждена", NamedTextColor.GREEN));
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
                    online.sendMessage(Component.text("[CHRDK] С одного IP (" + AuditLog.maskIp(ip)
                            + ") заходят несколько аккаунтов с проходками: " + String.join(", ", sharing),
                            NamedTextColor.YELLOW));
                }
            }
        }
        notifications.notify("multialt.alert", "Подозрение на общий аккаунт",
                "IP: " + AuditLog.maskIp(ip) + "\nАккаунты: " + String.join(", ", sharing)
                        + "\nЛимит: " + config.maxAccountsPerIp(), "warn");
        return config.antiMultiAltDeny()
                ? "С вашего IP уже играет слишком много аккаунтов с проходками. Обратитесь к администрации."
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
            player.sendMessage(Component.text("Вы уже зарегистрированы! Используйте: /login <пароль>", NamedTextColor.YELLOW));
            return true;
        }
        if (stage == PlayerStage.AUTHENTICATED) {
            player.sendMessage(Component.text("Вы уже зарегистрированы и вошли в аккаунт.", NamedTextColor.GREEN));
            return true;
        }

        if (!password.equals(confirmPassword)) {
            player.sendMessage(Component.text("Пароли не совпадают! Проверьте ввод: /register <пароль> <повтор>", NamedTextColor.RED));
            return true;
        }
        PasswordPolicy.Result policy = PasswordPolicy.check(player.getName(), password, config);
        if (!policy.accepted()) {
            player.sendMessage(Component.text(policy.message(), NamedTextColor.RED));
            if (config.passwordStrengthHint()) {
                player.sendMessage(Component.text("Надёжность: " + policy.strength()
                        + ". " + String.join(", ", PasswordPolicy.hints(password)) + ".", NamedTextColor.GRAY));
            }
            return true;
        }

        String ip = getPlayerIp(player);
        Optional<String> shared = findSharedPassword(player.getName(), password, ip, now);
        if (shared.isPresent()) {
            player.sendMessage(Component.text("Этот пароль уже используется другим аккаунтом, который заходил "
                    + "с вашего IP. Придумайте свой пароль.", NamedTextColor.RED));
            return true;
        }
        boolean registered = store.registerPassword(player.getName(), player.getUniqueId(), password, ip, now);
        if (!registered) {
            player.sendMessage(Component.text("Не удалось завершить регистрацию. Возможно, аккаунт уже зарегистрирован.", NamedTextColor.RED));
            return true;
        }

        completeAuthentication(player, "✓ Вы успешно зарегистрировались и вошли на сервер! Приятной игры!");
        if (config.passwordStrengthHint()) {
            player.sendMessage(Component.text("Надёжность пароля: " + policy.strength()
                    + ". Сменить пароль можно командой /changepassword.", NamedTextColor.GRAY));
        }
        logger.info("Player " + player.getName() + " registered an account password.");
        audit.log(AuditLog.ADMIN_COMMAND, player.getName(), player.getName(), ip,
                AuditLog.details("action", "register", "strength", policy.strength()));
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
            player.sendMessage(Component.text("Сначала зарегистрируйте пароль: /register <пароль> <повтор>", NamedTextColor.YELLOW));
            return true;
        }
        if (stage == PlayerStage.AUTHENTICATED) {
            player.sendMessage(Component.text("Вы уже вошли в аккаунт!", NamedTextColor.GREEN));
            return true;
        }
        if (stage == PlayerStage.NEEDS_2FA) {
            if (optionalTotpCode == null && TotpService.isValidCodeFormat(password)) {
                return handleTwoFactorLoginCode(player, password);
            }
            player.sendMessage(Component.text("Пароль уже подтверждён! Введите 6-значный код 2FA: /2fa <код>", NamedTextColor.AQUA));
            return true;
        }

        AuthThrottle.Result throttleResult = throttle.check(player.getName(), getPlayerIp(player), now);
        if (throttleResult.blocked()) {
            player.sendMessage(Component.text("Вход временно заблокирован: " + throttleResult.reason()
                    + ". Попробуйте позже (" + AccessManager.formatTimestamp(throttleResult.blockedUntil()) + ").",
                    NamedTextColor.RED));
            audit.log(AuditLog.BRUTEFORCE_ALERT, player.getName(), player.getName(), getPlayerIp(player),
                    AuditLog.details("decision", "blocked", "reason", throttleResult.reason()));
            return true;
        }
        if (throttleResult.decision() == AuthThrottle.Decision.DELAY) {
            player.sendMessage(Component.text("Слишком много неудачных попыток. Подождите "
                    + throttleResult.delaySeconds() + " сек.", NamedTextColor.RED));
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
            completeAuthentication(player, "✓ Вы успешно вошли на сервер! Приятной игры!");
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
            player.kick(Component.text("Слишком много неверных попыток входа. Аккаунт временно заблокирован на "
                    + config.tempIpBanMinutes() + " мин.", NamedTextColor.RED));
            notifyBruteForce(player, totalFailures);
            return true;
        }
        int remaining = Math.max(0, config.accountMaxAttempts() - totalFailures);
        player.sendMessage(Component.text("Неверный пароль! Осталось попыток до временной блокировки: " + remaining,
                NamedTextColor.RED));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
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
                    online.sendMessage(Component.text("[CHRDK] " + message, NamedTextColor.RED));
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
            player.sendMessage(Component.text("Сначала зарегистрируйте пароль: /register <пароль> <повтор>", NamedTextColor.YELLOW));
            return true;
        }
        if (stage == PlayerStage.NEEDS_LOGIN) {
            player.sendMessage(Component.text("Сначала введите пароль от аккаунта: /login <пароль>", NamedTextColor.YELLOW));
            return true;
        }
        if (stage == PlayerStage.AUTHENTICATED) {
            player.sendMessage(Component.text("Вы уже авторизованы на сервере!", NamedTextColor.GREEN));
            return true;
        }

        if (!TotpService.isValidCodeFormat(rawCode)) {
            player.sendMessage(Component.text("Код 2FA должен состоять из 6 цифр! Использование: /2fa <6-значный_код>", NamedTextColor.RED));
            return true;
        }

        AuthThrottle.Result throttleResult = throttle.check(player.getName(), getPlayerIp(player), now);
        if (throttleResult.blocked()) {
            player.sendMessage(Component.text("Вход временно заблокирован: " + throttleResult.reason()
                    + ". Попробуйте позже.", NamedTextColor.RED));
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
            player.kick(Component.text("Превышено количество неверных попыток ввода кода 2FA ("
                    + config.totpMaxAttempts() + ").", NamedTextColor.RED));
            notifyBruteForce(player, throttle.totalFailures(player.getName()));
            return true;
        }

        player.sendMessage(Component.text("Неверный или уже использованный 6-значный код 2FA! Повторите ввод.",
                NamedTextColor.RED));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
        return true;
    }

    /**
     * Starts TOTP 2FA enrollment for an authenticated player by generating a pending Base32 secret.
     */
    public boolean handleTwoFactorSetup(Player player) {
        if (getStage(player) != PlayerStage.AUTHENTICATED) {
            player.sendMessage(Component.text("Сначала полностью авторизуйтесь на сервере.", NamedTextColor.RED));
            return true;
        }
        if (!config.totpEnabled()) {
            player.sendMessage(Component.text("Двухфакторная аутентификация (2FA) отключена в конфигурации сервера.", NamedTextColor.RED));
            return true;
        }
        if (store.isTotpEnabled(player.getName())) {
            player.sendMessage(Component.text(
                    "У вас уже включена TOTP 2FA! Чтобы перепривязать приложение, сначала отключите текущую защиту: /2fa disable <код_2FA>",
                    NamedTextColor.YELLOW));
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

        Component copyKeyButton = Component.text("[ Скопировать секретный ключ ]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.copyToClipboard(secret))
                .hoverEvent(HoverEvent.showText(Component.text("Нажмите, чтобы скопировать Base32 ключ в буфер обмена", NamedTextColor.GREEN)));

        Component copyUriButton = Component.text("[ Скопировать otpauth:// ссылку ]", NamedTextColor.AQUA, TextDecoration.BOLD)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.copyToClipboard(otpAuthUri))
                .hoverEvent(HoverEvent.showText(Component.text("Нажмите, чтобы скопировать URI для приложения-аутентификатора", NamedTextColor.AQUA)));

        Component confirmCommandHint = Component.text("/2fa confirm <6-значный_код>", NamedTextColor.GOLD, TextDecoration.BOLD)
                .clickEvent(ClickEvent.suggestCommand("/2fa confirm "))
                .hoverEvent(HoverEvent.showText(Component.text("Нажмите, чтобы подставить команду в чат", NamedTextColor.YELLOW)));

        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("==============================================", NamedTextColor.GOLD));
        player.sendMessage(Component.text("Настройка двухфакторной защиты (TOTP 2FA)", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text("1. Откройте приложение ", NamedTextColor.GRAY)
                .append(Component.text("Google Authenticator / 2FAS / Authy / Яндекс Ключ", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("2. Добавьте аккаунт по секретному ключу (Time-based / По времени):", NamedTextColor.GRAY));
        player.sendMessage(Component.text("   Ключ: ", NamedTextColor.GRAY)
                .append(Component.text(formattedSecret, NamedTextColor.YELLOW, TextDecoration.BOLD)));
        player.sendMessage(Component.text("   ", NamedTextColor.GRAY)
                .append(copyKeyButton)
                .append(Component.text("  ", NamedTextColor.GRAY))
                .append(copyUriButton));
        player.sendMessage(Component.text("3. Подтвердите привязку текущим кодом из приложения:", NamedTextColor.GRAY));
        player.sendMessage(Component.text("   Введите: ", NamedTextColor.YELLOW).append(confirmCommandHint));
        player.sendMessage(Component.text("==============================================", NamedTextColor.GOLD));
    }

    /**
     * Confirms pending TOTP 2FA setup using a 6-digit code from the player's authenticator app.
     */
    public boolean handleTwoFactorConfirmSetup(Player player, String rawCode) {
        if (getStage(player) != PlayerStage.AUTHENTICATED) {
            return handleTwoFactorLoginCode(player, rawCode);
        }
        if (store.isTotpEnabled(player.getName())) {
            player.sendMessage(Component.text("У вас уже включена двухфакторная аутентификация (2FA)!", NamedTextColor.GREEN));
            return true;
        }
        UUID uuid = player.getUniqueId();
        String pendingSecret = pendingTotpSecrets.get(uuid);
        if (pendingSecret == null || pendingSecret.isBlank()) {
            player.sendMessage(Component.text("Сначала начните настройку 2FA командой: /2fa setup", NamedTextColor.YELLOW));
            return true;
        }
        if (!TotpService.isValidCodeFormat(rawCode)) {
            player.sendMessage(Component.text("Код должен состоять из 6 цифр! Использование: /2fa confirm <6-значный_код>", NamedTextColor.RED));
            return true;
        }

        long now = System.currentTimeMillis();
        OptionalLong matchedStep = TotpService.verifyAndGetStep(
                pendingSecret, rawCode, now, config.totpWindowSteps(), -1L);
        if (matchedStep.isEmpty()) {
            player.sendMessage(Component.text(
                    "Неверный 6-значный код! Проверьте правильность ключа и синхронизацию времени на устройстве.",
                    NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return true;
        }

        if (!store.enableTotp(player.getName(), pendingSecret, matchedStep.getAsLong(), now)) {
            player.sendMessage(Component.text("Не удалось активировать 2FA. Попробуйте снова.", NamedTextColor.RED));
            return true;
        }

        pendingTotpSecrets.remove(uuid);
        store.touchSession(player.getName(), uuid, getPlayerIp(player), now);
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        player.sendMessage(Component.text(
                "✓ Двухфакторная аутентификация (TOTP 2FA) успешно включена! Теперь при входе после пароля потребуется 6-значный код.",
                NamedTextColor.GREEN));
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
            player.sendMessage(Component.text("Сначала авторизуйтесь на сервере, чтобы управлять 2FA.", NamedTextColor.RED));
            return true;
        }
        if (!store.isTotpEnabled(player.getName())) {
            player.sendMessage(Component.text("На вашем аккаунте не включена двухфакторная аутентификация.", NamedTextColor.YELLOW));
            return true;
        }
        if (verificationInput == null || verificationInput.isBlank()) {
            player.sendMessage(Component.text("Укажите текущий 6-значный код 2FA (или пароль): /2fa disable <код_2FA>", NamedTextColor.YELLOW));
            return true;
        }

        long now = System.currentTimeMillis();
        boolean verified = store.verifyTotp(player.getName(), verificationInput, now, config.totpWindowSteps())
                || store.verifyPassword(player.getName(), verificationInput);
        if (!verified) {
            player.sendMessage(Component.text("Неверный 6-значный код 2FA или пароль! Отключение отменено.", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return true;
        }

        store.disableTotp(player.getName());
        pendingTotpSecrets.remove(player.getUniqueId());
        player.sendMessage(Component.text("✓ Двухфакторная аутентификация (TOTP 2FA) отключена для вашего аккаунта.", NamedTextColor.GREEN));
        logger.info("Player " + player.getName() + " disabled TOTP 2FA.");
        audit.log(AuditLog.TOTP_DISABLE, player.getName(), player.getName(), getPlayerIp(player));
        notifications.notify("totp.disabled", "2FA отключена игроком",
                "Игрок: " + player.getName() + "\nIP: " + AuditLog.maskIp(getPlayerIp(player)), "warn");
        return true;
    }

    public boolean handleTwoFactorCancel(Player player) {
        if (getStage(player) != PlayerStage.AUTHENTICATED) {
            player.sendMessage(Component.text("Сначала авторизуйтесь на сервере.", NamedTextColor.RED));
            return true;
        }
        String removed = pendingTotpSecrets.remove(player.getUniqueId());
        if (removed == null) {
            player.sendMessage(Component.text("У вас нет незавершённой настройки 2FA.", NamedTextColor.YELLOW));
        } else {
            player.sendMessage(Component.text("✓ Настройка 2FA отменена.", NamedTextColor.GREEN));
        }
        return true;
    }

    public boolean handleChangePasswordCommand(Player player, String oldPassword, String newPassword) {
        if (getStage(player) != PlayerStage.AUTHENTICATED) {
            player.sendMessage(Component.text("Сначала авторизуйтесь на сервере, чтобы сменить пароль.", NamedTextColor.RED));
            return true;
        }
        PasswordPolicy.Result policy = PasswordPolicy.check(player.getName(), newPassword, config);
        if (!policy.accepted()) {
            player.sendMessage(Component.text(policy.message(), NamedTextColor.RED));
            if (config.passwordStrengthHint()) {
                player.sendMessage(Component.text("Надёжность: " + policy.strength()
                        + ". " + String.join(", ", PasswordPolicy.hints(newPassword)) + ".", NamedTextColor.GRAY));
            }
            return true;
        }
        if (!store.changePassword(player.getName(), oldPassword, newPassword)) {
            player.sendMessage(Component.text("Старый пароль указан неверно!", NamedTextColor.RED));
            return true;
        }
        long changedAt = System.currentTimeMillis();
        store.clearSessions(player.getName());
        store.touchSession(player.getName(), player.getUniqueId(), getPlayerIp(player), changedAt);
        player.sendMessage(Component.text("✓ Пароль успешно изменён! Все прочие сессии сброшены.", NamedTextColor.GREEN));
        logger.info("Player " + player.getName() + " changed their password.");
        audit.log(AuditLog.PASSWORD_CHANGE, player.getName(), player.getName(), getPlayerIp(player),
                AuditLog.details("strength", policy.strength()));
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
        return account;
    }

    /**
     * Callback used by {@link DonationService} when a payment extends someone's pass.
     */
    public void onPassGranted(String playerName, long passUntil, String sourceDescription) {
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
            onlinePlayer.sendMessage(Component.text("Ваша проходка была отозвана администратором.", NamedTextColor.RED));
            sendNoPassPrompt(onlinePlayer, true);
        }
        logger.info("Pass revoked for player " + targetPlayerName + ".");
        audit.log(AuditLog.PASS_REVOKE, "admin", targetPlayerName, "");
        notifications.notify("pass.revoked", "Проходка отозвана",
                "Игрок: " + targetPlayerName, "warn");
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
            onlinePlayer.sendMessage(Component.text("Ваш пароль был сброшен администратором. Зарегистрируйте новый пароль!", NamedTextColor.YELLOW));
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
            onlinePlayer.sendMessage(Component.text(
                    "Двухфакторная аутентификация (2FA) вашего аккаунта была сброшена администратором.",
                    NamedTextColor.YELLOW));
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

    private void completeAuthentication(Player player, String successMessage) {
        UUID uuid = player.getUniqueId();
        authenticatedPlayers.add(uuid);
        passwordVerifiedPlayers.remove(uuid);
        freezeLocations.remove(uuid);
        lastChatReminderAt.remove(uuid);
        hideExpiryBar(uuid);

        player.clearTitle();
        player.sendMessage(Component.text(successMessage, NamedTextColor.GREEN));
        player.sendActionBar(Component.text("Доступ открыт!", NamedTextColor.GREEN));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
    }

    private void notifyOnlinePassActivated(Player player, long passUntil, String sourceDescription) {
        UUID uuid = player.getUniqueId();
        lastChatReminderAt.put(uuid, System.currentTimeMillis());

        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("==============================================", NamedTextColor.GOLD));
        player.sendMessage(Component.text("✓ Проходка на сервер активирована (" + sourceDescription + ")!", NamedTextColor.GREEN, TextDecoration.BOLD));
        player.sendMessage(Component.text("Срок действия до: ", NamedTextColor.GRAY)
                .append(Component.text(formatTimestamp(passUntil), NamedTextColor.AQUA)));
        player.sendMessage(Component.text("==============================================", NamedTextColor.GOLD));

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
                    player.sendMessage(Component.text("Срок вашей месячной проходки истёк! Продлите проходку через DonatePay.", NamedTextColor.RED));
                    audit.log(AuditLog.PASS_EXPIRE, player.getName(), player.getName(), getPlayerIp(player));
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
            player.sendMessage(Component.text("Внимание! Проходка заканчивается через "
                    + AccessManager.describeRemaining(warnMinutes * 60_000L)
                    + " (до " + formatTime(passUntil) + "). Продлить: "
                    + (config.hasPaymentUrl() ? config.donatePayPaymentUrl() : "/pass"), NamedTextColor.GOLD));
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
        bar.name(Component.text("Проходка заканчивается через "
                + AccessManager.describeRemaining(total), NamedTextColor.YELLOW));
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
        player.kick(Component.text("Вы не авторизовались за " + limitSeconds
                + " сек. Зайдите снова и введите пароль.", NamedTextColor.RED));
    }

    private void sendNoPassReminder(Player player, long now) {
        player.sendActionBar(Component.text(
                "Нет проходки! Оплатите " + config.minimumDonationRubles()
                        + "₽ на DonatePay (укажите ник " + player.getName() + " в имени донатера)",
                NamedTextColor.RED));
        long lastChat = lastChatReminderAt.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastChat >= CHAT_REMINDER_INTERVAL_MILLIS) {
            lastChatReminderAt.put(player.getUniqueId(), now);
            sendNoPassPrompt(player, false);
        }
    }

    private void sendRegisterReminder(Player player, long now) {
        player.sendActionBar(Component.text(
                "Зарегистрируйтесь: /register <пароль> <повтор>",
                NamedTextColor.GOLD));
        long lastChat = lastChatReminderAt.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastChat >= CHAT_REMINDER_INTERVAL_MILLIS) {
            lastChatReminderAt.put(player.getUniqueId(), now);
            sendRegisterPrompt(player, false);
        }
    }

    private void sendLoginReminder(Player player, long now) {
        player.sendActionBar(Component.text(
                "Авторизуйтесь: /login <пароль>",
                NamedTextColor.YELLOW));
        long lastChat = lastChatReminderAt.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastChat >= CHAT_REMINDER_INTERVAL_MILLIS) {
            lastChatReminderAt.put(player.getUniqueId(), now);
            sendLoginPrompt(player, false);
        }
    }

    private void sendTwoFactorReminder(Player player, long now) {
        player.sendActionBar(Component.text(
                "Подтвердите 2FA: /2fa <6-значный_код>",
                NamedTextColor.AQUA));
        long lastChat = lastChatReminderAt.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastChat >= CHAT_REMINDER_INTERVAL_MILLIS) {
            lastChatReminderAt.put(player.getUniqueId(), now);
            sendTwoFactorPrompt(player, false);
        }
    }

    public void sendNoPassPrompt(Player player, boolean showTitle) {
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("==============================================", NamedTextColor.RED));
        player.sendMessage(Component.text("У вас нет активной проходки на сервер!", NamedTextColor.RED, TextDecoration.BOLD));
        player.sendMessage(Component.text("Стоимость проходки: ", NamedTextColor.GRAY)
                .append(Component.text(config.minimumDonationRubles() + " ₽", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" (выдаётся на ", NamedTextColor.GRAY))
                .append(Component.text(config.grantDays() + " дней", NamedTextColor.AQUA))
                .append(Component.text(")", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("ВАЖНО: В поле «Ваше имя» (имя донатера) укажите ваш точный ник: ", NamedTextColor.YELLOW)
                .append(Component.text(player.getName(), NamedTextColor.GREEN, TextDecoration.BOLD)));

        if (config.hasPaymentUrl()) {
            Component link = Component.text("[ Открыть страницу оплаты DonatePay ]", NamedTextColor.AQUA, TextDecoration.BOLD)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(config.donatePayPaymentUrl()))
                    .hoverEvent(HoverEvent.showText(Component.text("Нажмите, чтобы перейти к оплате проходки", NamedTextColor.GREEN)));
            player.sendMessage(link);
        }
        player.sendMessage(Component.text("Проходка активируется автоматически сразу после оплаты (не выходя с сервера).", NamedTextColor.GRAY));
        player.sendMessage(Component.text("==============================================", NamedTextColor.RED));

        if (showTitle) {
            Title title = Title.title(
                    Component.text("НЕТ ПРОХОДКИ", NamedTextColor.RED, TextDecoration.BOLD),
                    Component.text("Донат " + config.minimumDonationRubles() + "₽ на DonatePay (ник: " + player.getName() + ")", NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(6), Duration.ofMillis(500))
            );
            player.showTitle(title);
        }
    }

    public void sendRegisterPrompt(Player player, boolean showTitle) {
        player.sendMessage(Component.text("Проходка активна! Зарегистрируйте пароль командой: ", NamedTextColor.YELLOW)
                .append(Component.text("/register <пароль> <повтор>", NamedTextColor.GOLD, TextDecoration.BOLD)));
        if (showTitle) {
            Title title = Title.title(
                    Component.text("РЕГИСТРАЦИЯ", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("Введите /register <пароль> <повтор>", NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofMillis(500))
            );
            player.showTitle(title);
        }
    }

    public void sendLoginPrompt(Player player, boolean showTitle) {
        player.sendMessage(Component.text("Введите пароль для входа на сервер: ", NamedTextColor.YELLOW)
                .append(Component.text("/login <пароль>", NamedTextColor.GOLD, TextDecoration.BOLD)));
        if (showTitle) {
            Title title = Title.title(
                    Component.text("АВТОРИЗАЦИЯ", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("Введите /login <пароль>", NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofMillis(500))
            );
            player.showTitle(title);
        }
    }

    public void sendTwoFactorPrompt(Player player, boolean showTitle) {
        player.sendMessage(Component.text("✓ Пароль верный! Введите 6-значный код TOTP 2FA из приложения: ", NamedTextColor.AQUA)
                .append(Component.text("/2fa <6-значный_код>", NamedTextColor.GOLD, TextDecoration.BOLD)));
        if (showTitle) {
            Title title = Title.title(
                    Component.text("2FA ЗАЩИТА", NamedTextColor.AQUA, TextDecoration.BOLD),
                    Component.text("Введите /2fa <6-значный код>", NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofMillis(500))
            );
            player.showTitle(title);
        }
    }

    private void pollDonationsSafely() {
        PluginConfig currentConfig = this.config;
        if (!currentConfig.donatePayReady() || !donationPollRunning.compareAndSet(false, true)) {
            return;
        }
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
                }
                store.initializeDonationCursor(latest);
                logger.info("DonatePay transaction cursor initialized at ID " + latest
                        + " (payments prior to initial setup are ignored).");
                return;
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
            }
        } catch (Exception exception) {
            warnDonatePayThrottled(exception.getMessage());
        } finally {
            donationPollRunning.set(false);
        }
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
        player.sendMessage(Component.text("Вы входили с этого IP недавно — пароль и 2FA не требуются "
                + config.sessionMinutes() + " мин. Выйти: /logout", NamedTextColor.GRAY));
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
        player.sendMessage(Component.text("✓ Вы вышли из аккаунта. Сессия по IP сброшена, пароль потребуется снова.",
                NamedTextColor.GREEN));
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
