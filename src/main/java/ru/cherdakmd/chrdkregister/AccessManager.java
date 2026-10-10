package ru.cherdakmd.chrdkregister;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Coordinates DonatePay pass verification, manual admin pass grants, and password registration/login state.
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
        AUTHENTICATED
    }

    private final JavaPlugin plugin;
    private final RegistrationStore store;
    private final DonatePayClient donatePay;
    private final Logger logger;

    private volatile PluginConfig config;
    private final Set<UUID> authenticatedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> failedLoginAttempts = new ConcurrentHashMap<>();
    private final Map<UUID, Location> freezeLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastChatReminderAt = new ConcurrentHashMap<>();
    private final AtomicBoolean donationPollRunning = new AtomicBoolean(false);

    private BukkitTask donatePollTask;
    private BukkitTask reminderTask;
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
        failedLoginAttempts.clear();
        freezeLocations.clear();
        lastChatReminderAt.clear();
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
    }

    public PlayerStage getStage(Player player) {
        return getStage(player.getUniqueId(), player.getName(), System.currentTimeMillis());
    }

    public PlayerStage getStage(UUID uuid, String playerName, long nowMillis) {
        if (!store.hasActivePass(playerName, nowMillis)) {
            authenticatedPlayers.remove(uuid);
            return PlayerStage.NO_PASS;
        }
        if (!store.isRegistered(playerName)) {
            authenticatedPlayers.remove(uuid);
            return PlayerStage.NEEDS_REGISTER;
        }
        if (!authenticatedPlayers.contains(uuid)) {
            return PlayerStage.NEEDS_LOGIN;
        }
        return PlayerStage.AUTHENTICATED;
    }

    public boolean isBlocked(Player player) {
        return getStage(player) != PlayerStage.AUTHENTICATED;
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
        failedLoginAttempts.remove(uuid);
        freezeLocations.put(uuid, player.getLocation().clone());
        lastChatReminderAt.put(uuid, now);

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
        if (store.hasValidIpSession(name, ip, now, sessionDurationMillis)) {
            authenticatedPlayers.add(uuid);
            freezeLocations.remove(uuid);
            lastChatReminderAt.remove(uuid);
            store.touchSession(name, uuid, ip, now);
            player.clearTitle();
            player.sendMessage(Component.text("✓ Сессия авторизации восстановлена по IP. Приятной игры!", NamedTextColor.GREEN));
            player.sendActionBar(Component.text("Авторизация подтверждена", NamedTextColor.GREEN));
            return;
        }

        player.closeInventory();
        sendLoginPrompt(player, true);
    }

    public void onPlayerQuit(Player player) {
        UUID uuid = player.getUniqueId();
        if (authenticatedPlayers.remove(uuid)) {
            store.touchSession(player.getName(), uuid, getPlayerIp(player), System.currentTimeMillis());
        }
        failedLoginAttempts.remove(uuid);
        freezeLocations.remove(uuid);
        lastChatReminderAt.remove(uuid);
    }

    public boolean handleRegisterCommand(Player player, String password, String confirmPassword) {
        long now = System.currentTimeMillis();
        PlayerStage stage = getStage(player.getUniqueId(), player.getName(), now);

        if (stage == PlayerStage.NO_PASS) {
            sendNoPassPrompt(player, false);
            return true;
        }
        if (stage == PlayerStage.NEEDS_LOGIN) {
            player.sendMessage(Component.text("Вы уже зарегистрированы! Используйте: /login <пароль>", NamedTextColor.YELLOW));
            return true;
        }
        if (stage == PlayerStage.AUTHENTICATED) {
            player.sendMessage(Component.text("Вы уже зарегистрированы и вошли в аккаунт.", NamedTextColor.GREEN));
            return true;
        }

        if (password.length() < config.minPasswordLength() || password.length() > config.maxPasswordLength()) {
            player.sendMessage(Component.text("Длина пароля должна быть от "
                    + config.minPasswordLength() + " до " + config.maxPasswordLength() + " символов.", NamedTextColor.RED));
            return true;
        }
        if (!password.equals(confirmPassword)) {
            player.sendMessage(Component.text("Пароли не совпадают! Проверьте ввод: /register <пароль> <повтор>", NamedTextColor.RED));
            return true;
        }

        String ip = getPlayerIp(player);
        boolean registered = store.registerPassword(player.getName(), player.getUniqueId(), password, ip, now);
        if (!registered) {
            player.sendMessage(Component.text("Не удалось завершить регистрацию. Возможно, аккаунт уже зарегистрирован.", NamedTextColor.RED));
            return true;
        }

        completeAuthentication(player, "✓ Вы успешно зарегистрировались и вошли на сервер! Приятной игры!");
        logger.info("Player " + player.getName() + " registered an account password.");
        return true;
    }

    public boolean handleLoginCommand(Player player, String password) {
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

        if (store.verifyPassword(player.getName(), password)) {
            store.touchSession(player.getName(), uuid, getPlayerIp(player), now);
            completeAuthentication(player, "✓ Вы успешно вошли на сервер! Приятной игры!");
            logger.info("Player " + player.getName() + " logged in.");
            return true;
        }

        int attempts = failedLoginAttempts.merge(uuid, 1, Integer::sum);
        int maxAttempts = config.maxLoginAttempts();
        if (attempts >= maxAttempts) {
            failedLoginAttempts.remove(uuid);
            player.kick(Component.text("Превышено количество неверных попыток ввода пароля (" + maxAttempts + ").", NamedTextColor.RED));
            return true;
        }

        int remaining = maxAttempts - attempts;
        player.sendMessage(Component.text("Неверный пароль! Осталось попыток: " + remaining, NamedTextColor.RED));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
        return true;
    }

    public boolean handleChangePasswordCommand(Player player, String oldPassword, String newPassword) {
        if (getStage(player) != PlayerStage.AUTHENTICATED) {
            player.sendMessage(Component.text("Сначала авторизуйтесь на сервере, чтобы сменить пароль.", NamedTextColor.RED));
            return true;
        }
        if (newPassword.length() < config.minPasswordLength() || newPassword.length() > config.maxPasswordLength()) {
            player.sendMessage(Component.text("Длина нового пароля должна быть от "
                    + config.minPasswordLength() + " до " + config.maxPasswordLength() + " символов.", NamedTextColor.RED));
            return true;
        }
        if (!store.changePassword(player.getName(), oldPassword, newPassword)) {
            player.sendMessage(Component.text("Старый пароль указан неверно!", NamedTextColor.RED));
            return true;
        }
        store.touchSession(player.getName(), player.getUniqueId(), getPlayerIp(player), System.currentTimeMillis());
        player.sendMessage(Component.text("✓ Пароль успешно изменён!", NamedTextColor.GREEN));
        logger.info("Player " + player.getName() + " changed their password.");
        return true;
    }

    /**
     * Grants a 1-month pass manually by an administrator.
     */
    public RegistrationStore.PlayerAccount grantPassManually(String targetPlayerName) {
        long now = System.currentTimeMillis();
        long durationMillis = Duration.ofDays(config.grantDays()).toMillis();
        RegistrationStore.PlayerAccount account = store.grantPassManual(targetPlayerName, now, durationMillis);

        Player onlinePlayer = Bukkit.getPlayerExact(targetPlayerName);
        if (onlinePlayer != null) {
            notifyOnlinePassActivated(onlinePlayer, account.passUntil, "администратором");
        }
        logger.info("Manual pass granted to " + targetPlayerName + " for " + config.grantDays()
                + " days (until " + formatTimestamp(account.passUntil) + ").");
        return account;
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
            freezeLocations.put(uuid, onlinePlayer.getLocation().clone());
            onlinePlayer.closeInventory();
            onlinePlayer.sendMessage(Component.text("Ваша проходка была отозвана администратором.", NamedTextColor.RED));
            sendNoPassPrompt(onlinePlayer, true);
        }
        logger.info("Pass revoked for player " + targetPlayerName + ".");
        return true;
    }

    /**
     * Resets a player's password so they must register again via /register.
     */
    public boolean resetPassword(String targetPlayerName) {
        boolean reset = store.resetPassword(targetPlayerName);
        if (!reset) return false;

        Player onlinePlayer = Bukkit.getPlayerExact(targetPlayerName);
        if (onlinePlayer != null) {
            UUID uuid = onlinePlayer.getUniqueId();
            authenticatedPlayers.remove(uuid);
            freezeLocations.put(uuid, onlinePlayer.getLocation().clone());
            onlinePlayer.closeInventory();
            onlinePlayer.sendMessage(Component.text("Ваш пароль был сброшен администратором. Зарегистрируйте новый пароль!", NamedTextColor.YELLOW));
            if (store.hasActivePass(onlinePlayer.getName(), System.currentTimeMillis())) {
                sendRegisterPrompt(onlinePlayer, true);
            }
        }
        logger.info("Password reset for player " + targetPlayerName + ".");
        return true;
    }

    private void completeAuthentication(Player player, String successMessage) {
        UUID uuid = player.getUniqueId();
        authenticatedPlayers.add(uuid);
        failedLoginAttempts.remove(uuid);
        freezeLocations.remove(uuid);
        lastChatReminderAt.remove(uuid);

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
            sendLoginPrompt(player, true);
        }
    }

    private void tickOnlinePlayers() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            boolean hasPass = store.hasActivePass(player.getName(), now);

            if (!hasPass) {
                if (authenticatedPlayers.remove(uuid)) {
                    freezeLocations.put(uuid, player.getLocation().clone());
                    player.closeInventory();
                    player.sendMessage(Component.text("Срок вашей месячной проходки истёк! Продлите проходку через DonatePay.", NamedTextColor.RED));
                    sendNoPassPrompt(player, true);
                    continue;
                }
                sendNoPassReminder(player, now);
                continue;
            }

            if (!store.isRegistered(player.getName())) {
                authenticatedPlayers.remove(uuid);
                sendRegisterReminder(player, now);
                continue;
            }

            if (!authenticatedPlayers.contains(uuid)) {
                sendLoginReminder(player, now);
            }
        }
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
                    processTransaction(transaction, currentConfig);
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
                processTransaction(transaction, currentConfig);
                store.markDonationProcessed(transaction.id());
                cursor = Math.max(cursor, transaction.id());
            }
        } catch (Exception exception) {
            warnDonatePayThrottled(exception.getMessage());
        } finally {
            donationPollRunning.set(false);
        }
    }

    public boolean qualifiesForPass(DonatePayClient.Transaction transaction, PluginConfig currentConfig, long nowMillis) {
        if (transaction == null || !transaction.isSuccessfulDonation()) return false;
        if (transaction.amount().compareTo(BigDecimal.valueOf(currentConfig.minimumDonationRubles())) < 0) return false;
        String donorName = transaction.donorName();
        if (!isValidMinecraftName(donorName)) return false;
        long durationMillis = Duration.ofDays(currentConfig.grantDays()).toMillis();
        return transaction.createdAt() + durationMillis > nowMillis;
    }

    private void processTransaction(DonatePayClient.Transaction transaction, PluginConfig currentConfig) {
        long now = System.currentTimeMillis();
        if (!qualifiesForPass(transaction, currentConfig, now)) {
            return;
        }
        String donorName = transaction.donorName().trim();
        long durationMillis = Duration.ofDays(currentConfig.grantDays()).toMillis();

        Optional<RegistrationStore.PlayerAccount> granted = store.grantPassFromDonation(
                donorName,
                transaction.createdAt(),
                now,
                durationMillis,
                currentConfig.strictCaseMatch()
        );
        if (granted.isEmpty()) {
            logger.warning("DonatePay donation #" + transaction.id() + " for donor '" + donorName
                    + "' skipped due to strict-case-match mismatch.");
            return;
        }

        RegistrationStore.PlayerAccount account = granted.get();
        String amountStr = transaction.amount().stripTrailingZeros().toPlainString();
        logger.info("DonatePay pass granted/extended for player " + account.playerName
                + " (" + amountStr + " RUB, valid until " + formatTimestamp(account.passUntil) + ").");

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = findOnlinePlayer(donorName, currentConfig.strictCaseMatch());
            if (online != null) {
                notifyOnlinePassActivated(online, account.passUntil, "DonatePay " + amountStr + "₽");
            }
        });
    }

    private Player findOnlinePlayer(String name, boolean strictCase) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact == null) return null;
        if (strictCase && !exact.getName().equals(name)) {
            return null;
        }
        return exact;
    }

    private void warnDonatePayThrottled(String detail) {
        long now = System.currentTimeMillis();
        if (now - lastDonatePayWarningMillis < TimeUnit.MINUTES.toMillis(5)) {
            return;
        }
        lastDonatePayWarningMillis = now;
        logger.log(Level.WARNING, "DonatePay check failed: "
                + (detail == null || detail.isBlank() ? "unknown error" : detail));
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
