package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Handles /register, /login, /changepassword, /2fa (/totp), and /pass (/fregister, /prohodka) commands.
 */
public final class RegisterCommandHandler implements CommandExecutor, TabCompleter {
    private static final List<String> ADMIN_SUBCOMMANDS =
            List.of("add", "remove", "check", "unregister", "reset2fa", "status", "reload",
                    "pending", "approve", "reject", "sessions", "logoutall", "stats", "doctor", "audit",
                    "backup", "gift");
    private static final List<String> MUTE_DURATIONS = List.of("10m", "30m", "1h", "6h", "1d", "7d");
    private static final List<String> TWO_FACTOR_SUBCOMMANDS =
            List.of("setup", "confirm", "disable", "status", "cancel");

    private final RegisterPlugin plugin;
    private final AccessManager accessManager;

    public RegisterCommandHandler(RegisterPlugin plugin, AccessManager accessManager) {
        this.plugin = plugin;
        this.accessManager = accessManager;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        String cmdName = command.getName().toLowerCase(Locale.ROOT);
        return switch (cmdName) {
            case "register" -> handleRegister(sender, args);
            case "login" -> handleLogin(sender, args);
            case "changepassword" -> handleChangePassword(sender, args);
            case "2fa" -> handleTwoFactor(sender, label, args);
            case "pass" -> handlePass(sender, label, args);
            case "logout" -> handleLogout(sender);
            case "session" -> handleSession(sender, args);
            default -> false;
        };
    }

    private boolean handleLogout(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Эта команда доступна только игрокам.", NamedTextColor.RED));
            return true;
        }
        return accessManager.handleLogoutCommand(player);
    }

    private boolean handleSession(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Эта команда доступна только игрокам. Админ: /pass sessions <ник>",
                    NamedTextColor.RED));
            return true;
        }
        if (args.length >= 1 && ("end".equalsIgnoreCase(args[0]) || "logout".equalsIgnoreCase(args[0]))) {
            return accessManager.handleLogoutCommand(player);
        }
        showSessions(sender, player.getName());
        return true;
    }

    private void showSessions(CommandSender sender, String playerName) {
        long now = System.currentTimeMillis();
        var sessions = accessManager.sessionsOf(playerName);
        sender.sendMessage(Component.text("=== Сессии игрока " + playerName + " ===", NamedTextColor.GOLD));
        if (sessions.isEmpty()) {
            sender.sendMessage(Component.text("Активных сессий нет.", NamedTextColor.GRAY));
            return;
        }
        long sessionMillis = java.util.concurrent.TimeUnit.MINUTES.toMillis(accessManager.config().sessionMinutes());
        for (var session : sessions) {
            long age = session.ageMillis(now);
            boolean active = sessionMillis > 0 && age <= sessionMillis;
            sender.sendMessage(Component.text("- " + AuditLog.maskIp(session.ip)
                            + (session.uuid == null || session.uuid.isBlank() ? "" : " (UUID " + session.uuid.substring(0,
                            Math.min(8, session.uuid.length())) + "…)")
                            + " — " + (active ? "активна" : "истекла")
                            + ", последний вход: " + AccessManager.formatTimestamp(session.lastSeenAt)
                            + ", источник: " + session.source,
                    active ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        }
        if (playerName.equalsIgnoreCase(sender.getName())) {
            sender.sendMessage(Component.text("Завершить все сессии: /logout", NamedTextColor.YELLOW));
        }
    }

    private boolean handleRegister(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Эта команда доступна только игрокам.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 2) {
            player.sendMessage(Component.text("Использование: /register <пароль> <повтор>", NamedTextColor.YELLOW));
            return true;
        }
        return accessManager.handleRegisterCommand(player, args[0], args[1]);
    }

    private boolean handleLogin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Эта команда доступна только игрокам.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            player.sendMessage(Component.text("Использование: /login <пароль> [код_2FA]", NamedTextColor.YELLOW));
            return true;
        }
        String totpCode = args.length == 2 ? args[1] : null;
        return accessManager.handleLoginCommand(player, args[0], totpCode);
    }

    private boolean handleChangePassword(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Эта команда доступна только игрокам.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 2) {
            player.sendMessage(Component.text("Использование: /changepassword <старый_пароль> <новый_пароль>", NamedTextColor.YELLOW));
            return true;
        }
        return accessManager.handleChangePasswordCommand(player, args[0], args[1]);
    }

    private boolean handleTwoFactor(CommandSender sender, String label, String[] args) {
        if (args.length >= 2
                && ("reset".equalsIgnoreCase(args[0]) || "reset2fa".equalsIgnoreCase(args[0]))
                && sender.hasPermission("chrdkregister.admin")) {
            String targetName = args[1].trim();
            if (accessManager.resetTwoFactor(targetName)) {
                sender.sendMessage(Component.text("✓ TOTP 2FA для игрока " + targetName + " сброшена.", NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text("У игрока " + targetName + " не включена двухфакторная аутентификация.", NamedTextColor.RED));
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text(
                    "Эта команда доступна только игрокам. Для сброса 2FA игрока используйте: /pass reset2fa <ник>",
                    NamedTextColor.RED));
            return true;
        }

        AccessManager.PlayerStage stage = accessManager.getStage(player);
        if (stage == AccessManager.PlayerStage.NO_PASS) {
            accessManager.sendNoPassPrompt(player, false);
            return true;
        }
        if (stage == AccessManager.PlayerStage.NEEDS_REGISTER) {
            player.sendMessage(Component.text("Сначала зарегистрируйте пароль: /register <пароль> <повтор>", NamedTextColor.YELLOW));
            return true;
        }
        if (stage == AccessManager.PlayerStage.NEEDS_LOGIN) {
            player.sendMessage(Component.text("Сначала введите пароль: /login <пароль>", NamedTextColor.YELLOW));
            return true;
        }
        if (stage == AccessManager.PlayerStage.NEEDS_2FA) {
            if (args.length == 0) {
                accessManager.sendTwoFactorPrompt(player, false);
                return true;
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (("verify".equals(sub) || "confirm".equals(sub) || "code".equals(sub)) && args.length >= 2) {
                return accessManager.handleTwoFactorLoginCode(player, joinArgsFrom(args, 1));
            }
            return accessManager.handleTwoFactorLoginCode(player, String.join("", args));
        }

        // Player is AUTHENTICATED
        if (args.length == 0) {
            showOwnTwoFactorStatus(player, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status", "info" -> {
                showOwnTwoFactorStatus(player, label);
                return true;
            }
            case "setup", "enable", "on", "bind" -> {
                return accessManager.handleTwoFactorSetup(player);
            }
            case "confirm", "verify", "activate" -> {
                if (args.length < 2) {
                    player.sendMessage(Component.text("Использование: /" + label + " confirm <6-значный_код>", NamedTextColor.YELLOW));
                    return true;
                }
                return accessManager.handleTwoFactorConfirmSetup(player, joinArgsFrom(args, 1));
            }
            case "disable", "off", "unbind", "remove" -> {
                if (args.length < 2) {
                    player.sendMessage(Component.text("Использование: /" + label + " disable <6-значный_код_или_пароль>", NamedTextColor.YELLOW));
                    return true;
                }
                return accessManager.handleTwoFactorDisable(player, joinArgsFrom(args, 1));
            }
            case "cancel" -> {
                return accessManager.handleTwoFactorCancel(player);
            }
            default -> {
                String candidateCode = String.join("", args);
                if (TotpService.isValidCodeFormat(candidateCode) && accessManager.hasPendingTotpSetup(player.getUniqueId())) {
                    return accessManager.handleTwoFactorConfirmSetup(player, candidateCode);
                }
                sendTwoFactorHelp(player, label);
                return true;
            }
        }
    }

    private void showOwnTwoFactorStatus(Player player, String label) {
        boolean enabled = accessManager.store().isTotpEnabled(player.getName());
        Optional<String> pendingSecret = accessManager.getPendingTotpSecret(player.getUniqueId());

        player.sendMessage(Component.text("==============================================", NamedTextColor.GOLD));
        player.sendMessage(Component.text("Двухфакторная аутентификация (TOTP 2FA): ", NamedTextColor.GRAY)
                .append(Component.text(enabled ? "ВКЛЮЧЕНА" : "ОТКЛЮЧЕНА",
                        enabled ? NamedTextColor.GREEN : NamedTextColor.YELLOW, TextDecoration.BOLD)));

        if (enabled) {
            Optional<RegistrationStore.PlayerAccount> accountOpt = accessManager.store().getAccount(player.getName());
            if (accountOpt.isPresent() && accountOpt.get().totpEnabledAt > 0) {
                player.sendMessage(Component.text("Подключена: ", NamedTextColor.GRAY)
                        .append(Component.text(AccessManager.formatTimestamp(accountOpt.get().totpEnabledAt), NamedTextColor.AQUA)));
            }
            player.sendMessage(Component.text("Для отключения используйте: ", NamedTextColor.GRAY)
                    .append(Component.text("/" + label + " disable <6-значный_код>", NamedTextColor.YELLOW)));
        } else if (pendingSecret.isPresent()) {
            player.sendMessage(Component.text("Статус: ", NamedTextColor.GRAY)
                    .append(Component.text("Ожидает подтверждения 6-значным кодом", NamedTextColor.AQUA)));
            player.sendMessage(Component.text("Ваш ключ: ", NamedTextColor.GRAY)
                    .append(Component.text(TotpService.formatSecretForDisplay(pendingSecret.get()), NamedTextColor.YELLOW, TextDecoration.BOLD)));
            player.sendMessage(Component.text("Подтвердите: ", NamedTextColor.GRAY)
                    .append(Component.text("/" + label + " confirm <6-значный_код>", NamedTextColor.GOLD, TextDecoration.BOLD))
                    .append(Component.text(" (или /" + label + " cancel)", NamedTextColor.DARK_GRAY)));
        } else {
            Component setupBtn = Component.text("[ Включить TOTP 2FA (/" + label + " setup) ]", NamedTextColor.GREEN, TextDecoration.BOLD)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.runCommand("/" + label + " setup"))
                    .hoverEvent(HoverEvent.showText(Component.text("Нажмите, чтобы сгенерировать секретный ключ 2FA", NamedTextColor.GREEN)));
            player.sendMessage(Component.text("Защитите свой аккаунт приложением Google Authenticator / 2FAS:", NamedTextColor.GRAY));
            player.sendMessage(setupBtn);
        }
        player.sendMessage(Component.text("==============================================", NamedTextColor.GOLD));
    }

    private void sendTwoFactorHelp(Player player, String label) {
        player.sendMessage(Component.text("Команды двухфакторной защиты (TOTP 2FA):", NamedTextColor.GOLD));
        player.sendMessage(Component.text("/" + label + " setup", NamedTextColor.YELLOW)
                .append(Component.text(" — сгенерировать секретный ключ для приложения-аутентификатора", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/" + label + " confirm <код>", NamedTextColor.YELLOW)
                .append(Component.text(" — подтвердить ключ 6-значным кодом и включить 2FA", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/" + label + " disable <код>", NamedTextColor.YELLOW)
                .append(Component.text(" — отключить 2FA (по текущему коду или паролю)", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/" + label + " cancel", NamedTextColor.YELLOW)
                .append(Component.text(" — отменить незавершённую настройку 2FA", NamedTextColor.GRAY)));
    }

    private boolean handlePass(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                showOwnPassStatus(player);
                if (sender.hasPermission("chrdkregister.admin")) {
                    sender.sendMessage(Component.text(
                            "Админ-команды: /" + label + " <add|gift|remove|check|pending|approve|reject"
                                    + "|sessions|logoutall|unregister|reset2fa|stats|doctor|audit|backup|reload>",
                            NamedTextColor.DARK_GRAY));
                }
            } else {
                showPluginStatus(sender);
                sendAdminHelp(sender, label);
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if ("claim".equals(sub) && sender instanceof Player player) {
            if (args.length < 2) {
                player.sendMessage(Component.text("Использование: /" + label + " claim <id доната>",
                        NamedTextColor.YELLOW));
                return true;
            }
            long id;
            try {
                id = Long.parseLong(args[1]);
            } catch (NumberFormatException exception) {
                player.sendMessage(Component.text("ID доната должен быть числом.", NamedTextColor.RED));
                return true;
            }
            String error = accessManager.donations().claim(id, player.getName(),
                    AccessManager.getPlayerIp(player), System.currentTimeMillis());
            if (error.isEmpty()) {
                player.sendMessage(Component.text("✓ Платёж #" + id + " подтверждён, проходка активирована!",
                        NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text(error, NamedTextColor.RED));
            }
            return true;
        }
        if (!sender.hasPermission("chrdkregister.admin")) {
            if (sender instanceof Player player) {
                showOwnPassStatus(player);
            } else {
                sender.sendMessage(Component.text("Недостаточно прав.", NamedTextColor.RED));
            }
            return true;
        }

        switch (sub) {
            case "add", "grant" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Использование: /" + label + " add <ник> [дней]",
                            NamedTextColor.YELLOW));
                    return true;
                }
                String targetName = args[1].trim();
                if (!AccessManager.isValidMinecraftName(targetName)) {
                    sender.sendMessage(Component.text("Некорректный ник Minecraft: " + targetName, NamedTextColor.RED));
                    return true;
                }
                int days = accessManager.config().grantDays();
                if (args.length >= 3) {
                    try {
                        days = Math.clamp(Integer.parseInt(args[2]), 1, 3650);
                    } catch (NumberFormatException exception) {
                        sender.sendMessage(Component.text("Количество дней должно быть числом.", NamedTextColor.RED));
                        return true;
                    }
                }
                RegistrationStore.PlayerAccount account = accessManager.grantPassManually(targetName, days, "manual");
                sender.sendMessage(Component.text("✓ Проходка выдана игроку ", NamedTextColor.GREEN)
                        .append(Component.text(account.playerName, NamedTextColor.GOLD, TextDecoration.BOLD))
                        .append(Component.text(" на " + days + " дн. (до "
                                + AccessManager.formatTimestamp(account.passUntil) + ").", NamedTextColor.GREEN)));
                return true;
            }
            case "gift" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text("Использование: /" + label + " gift <ник> <дней>",
                            NamedTextColor.YELLOW));
                    return true;
                }
                String targetName = args[1].trim();
                int days;
                try {
                    days = Math.clamp(Integer.parseInt(args[2]), 1, 3650);
                } catch (NumberFormatException exception) {
                    sender.sendMessage(Component.text("Количество дней должно быть числом.", NamedTextColor.RED));
                    return true;
                }
                if (!AccessManager.isValidMinecraftName(targetName)) {
                    sender.sendMessage(Component.text("Некорректный ник Minecraft: " + targetName, NamedTextColor.RED));
                    return true;
                }
                RegistrationStore.PlayerAccount account = accessManager.grantPassManually(targetName, days,
                        "gift:" + sender.getName());
                accessManager.audit().log(AuditLog.PASS_GRANT_GIFT, sender.getName(), targetName, null,
                        AuditLog.details("days", days));
                sender.sendMessage(Component.text("✓ Подарок отправлен: ", NamedTextColor.GREEN)
                        .append(Component.text(account.playerName, NamedTextColor.GOLD))
                        .append(Component.text(" получил " + days + " дн. проходки.", NamedTextColor.GREEN)));
                Player online = Bukkit.getPlayerExact(targetName);
                if (online != null) {
                    online.sendMessage(Component.text("Вам подарили проходку на " + days + " дн. от "
                            + sender.getName() + "!", NamedTextColor.GREEN));
                }
                return true;
            }
            case "pending" -> {
                var pending = accessManager.donations().pending(20);
                if (pending.isEmpty()) {
                    sender.sendMessage(Component.text("Нераспознанных донатов нет — всё выдано автоматически.",
                            NamedTextColor.GREEN));
                    return true;
                }
                sender.sendMessage(Component.text("=== Донаты, требующие решения (" + pending.size() + ") ===",
                        NamedTextColor.GOLD));
                for (RegistrationStore.DonationRecord record : pending) {
                    sender.sendMessage(Component.text("#" + record.id + " " + record.donorName
                                    + " — " + record.amountRubles() + " ₽, " + record.status,
                            NamedTextColor.YELLOW)
                            .append(Component.text(record.note.isBlank() ? "" : " (" + record.note + ")",
                                    NamedTextColor.GRAY))
                            .append(record.suggestedName.isBlank() ? Component.empty()
                                    : Component.text(" → похоже на " + record.suggestedName, NamedTextColor.AQUA))
                            .append(Component.text(" | " + AccessManager.formatTimestamp(record.createdAt),
                                    NamedTextColor.DARK_GRAY)));
                }
                sender.sendMessage(Component.text("Выдать: /" + label + " approve <id> [ник] [дней] | "
                        + "Отклонить: /" + label + " reject <id> [причина]", NamedTextColor.GRAY));
                return true;
            }
            case "approve" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Использование: /" + label + " approve <id> [ник] [дней]",
                            NamedTextColor.YELLOW));
                    return true;
                }
                long id;
                try {
                    id = Long.parseLong(args[1]);
                } catch (NumberFormatException exception) {
                    sender.sendMessage(Component.text("ID доната должен быть числом.", NamedTextColor.RED));
                    return true;
                }
                var record = accessManager.store().getDonation(id).orElse(null);
                if (record == null) {
                    sender.sendMessage(Component.text("Платёж #" + id + " не найден в журнале.", NamedTextColor.RED));
                    return true;
                }
                String targetName = args.length >= 3 ? args[2].trim()
                        : (record.suggestedName.isBlank() ? record.donorName : record.suggestedName);
                int days = 0;
                if (args.length >= 4) {
                    try {
                        days = Math.clamp(Integer.parseInt(args[3]), 1, 3650);
                    } catch (NumberFormatException exception) {
                        sender.sendMessage(Component.text("Количество дней должно быть числом.", NamedTextColor.RED));
                        return true;
                    }
                }
                if (!AccessManager.isValidMinecraftName(targetName)) {
                    sender.sendMessage(Component.text("Некорректный ник: " + targetName, NamedTextColor.RED));
                    return true;
                }
                if (accessManager.donations().approve(id, targetName, days, sender.getName(),
                        System.currentTimeMillis())) {
                    sender.sendMessage(Component.text("✓ Платёж #" + id + " подтверждён, проходка выдана игроку "
                            + targetName + ".", NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("Не удалось подтвердить платёж #" + id + ".", NamedTextColor.RED));
                }
                return true;
            }
            case "reject" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Использование: /" + label + " reject <id> [причина]",
                            NamedTextColor.YELLOW));
                    return true;
                }
                long id;
                try {
                    id = Long.parseLong(args[1]);
                } catch (NumberFormatException exception) {
                    sender.sendMessage(Component.text("ID доната должен быть числом.", NamedTextColor.RED));
                    return true;
                }
                String reason = args.length >= 3
                        ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                        : "";
                if (accessManager.donations().reject(id, sender.getName(), reason, System.currentTimeMillis())) {
                    sender.sendMessage(Component.text("Платёж #" + id + " отклонён.", NamedTextColor.YELLOW));
                } else {
                    sender.sendMessage(Component.text("Платёж #" + id + " не найден.", NamedTextColor.RED));
                }
                return true;
            }
            case "sessions" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Использование: /" + label + " sessions <ник>",
                            NamedTextColor.YELLOW));
                    return true;
                }
                showSessions(sender, args[1].trim());
                return true;
            }
            case "logoutall" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Использование: /" + label + " logoutall <ник>",
                            NamedTextColor.YELLOW));
                    return true;
                }
                String targetName = args[1].trim();
                boolean cleared = accessManager.clearAllSessions(targetName);
                accessManager.lockOnlinePlayer(targetName,
                        "Все сессии вашего аккаунта завершены администратором — войдите заново.");
                sender.sendMessage(Component.text(cleared
                        ? "✓ Сессии игрока " + targetName + " завершены."
                        : "У игрока " + targetName + " не было активных сессий.", NamedTextColor.GREEN));
                return true;
            }
            case "stats" -> {
                showStats(sender);
                return true;
            }
            case "doctor" -> {
                showDoctor(sender);
                return true;
            }
            case "audit" -> {
                showAudit(sender, args);
                return true;
            }
            case "backup" -> {
                handleBackup(sender, args, label);
                return true;
            }
            case "remove", "revoke" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Использование: /" + label + " remove <ник>", NamedTextColor.YELLOW));
                    return true;
                }
                String targetName = args[1].trim();
                if (accessManager.revokePass(targetName)) {
                    sender.sendMessage(Component.text("✓ Проходка игрока " + targetName + " отозвана.", NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("У игрока " + targetName + " не найдено активной проходки.", NamedTextColor.RED));
                }
                return true;
            }
            case "check", "info" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Использование: /" + label + " check <ник>", NamedTextColor.YELLOW));
                    return true;
                }
                showAccountDetails(sender, args[1].trim());
                return true;
            }
            case "unregister", "resetpass" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Использование: /" + label + " unregister <ник>", NamedTextColor.YELLOW));
                    return true;
                }
                String targetName = args[1].trim();
                if (accessManager.resetPassword(targetName)) {
                    sender.sendMessage(Component.text("✓ Пароль (и 2FA) игрока " + targetName + " сброшены. Проходка сохранена.", NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("Игрок " + targetName + " ещё не регистрировал пароль.", NamedTextColor.RED));
                }
                return true;
            }
            case "reset2fa", "disable2fa", "un2fa" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Использование: /" + label + " reset2fa <ник>", NamedTextColor.YELLOW));
                    return true;
                }
                String targetName = args[1].trim();
                if (accessManager.resetTwoFactor(targetName)) {
                    sender.sendMessage(Component.text("✓ Двухфакторная защита (2FA) игрока " + targetName + " сброшена.", NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("У игрока " + targetName + " не включена двухфакторная аутентификация.", NamedTextColor.RED));
                }
                return true;
            }
            case "status" -> {
                showPluginStatus(sender);
                return true;
            }
            case "reload" -> {
                plugin.reloadPluginConfig();
                sender.sendMessage(Component.text("✓ Конфигурация CHRDK_F_REGISTER перезагружена.", NamedTextColor.GREEN));
                return true;
            }
            default -> {
                sendAdminHelp(sender, label);
                return true;
            }
        }
    }

    private void showStats(CommandSender sender) {
        long now = System.currentTimeMillis();
        RegistrationStore store = accessManager.store();
        PluginConfig cfg = accessManager.config();
        long dayAgo = now - java.util.concurrent.TimeUnit.DAYS.toMillis(1);
        long weekAgo = now - java.util.concurrent.TimeUnit.DAYS.toMillis(7);
        long monthAgo = now - java.util.concurrent.TimeUnit.DAYS.toMillis(30);

        sender.sendMessage(Component.text("=== Статистика донатов ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Выручка (подтверждённые донаты): ", NamedTextColor.GRAY)
                .append(Component.text("24ч: " + DonationService.formatRubles(store.revenueKopecks(dayAgo, now))
                        + " ₽, 7д: " + DonationService.formatRubles(store.revenueKopecks(weekAgo, now))
                        + " ₽, 30д: " + DonationService.formatRubles(store.revenueKopecks(monthAgo, now)) + " ₽",
                        NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Ждут решения: ", NamedTextColor.GRAY)
                .append(Component.text(accessManager.donations().pendingCount() + " платежей (/"
                        + "pass pending)", NamedTextColor.YELLOW)));
        int total = Math.max(1, store.getTotalAccountCount());
        int withPass = store.getActivePassCount(now);
        sender.sendMessage(Component.text("Конверсия: ", NamedTextColor.GRAY)
                .append(Component.text(withPass + " активных проходок из " + total + " известных аккаунтов ("
                        + Math.round(100.0 * withPass / total) + "%)", NamedTextColor.GREEN)));
        sender.sendMessage(Component.text("Всего выдано донатами: ", NamedTextColor.GRAY)
                .append(Component.text(store.countDonations(RegistrationStore.DonationStatus.GRANTED)
                        + " платежей", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Тарифы: ", NamedTextColor.GRAY)
                .append(Component.text(String.join("; ", DonationService.describeTiers(cfg)
                        .stream().map(line -> line.replace("&e", "").replace("&7", "").replace("&b", ""))
                        .toList()), NamedTextColor.WHITE)));
    }

    private void showDoctor(CommandSender sender) {
        PluginConfig cfg = accessManager.config();
        sender.sendMessage(Component.text("=== Проверка конфигурации (/pass doctor) ===", NamedTextColor.GOLD));
        check(sender, "DonatePay API-токен", cfg.donatePayReady(),
                "не задан donatepay.api-token — авто-выдача проходок не работает");
        check(sender, "Ссылка на оплату", cfg.hasPaymentUrl(),
                "не задан donatepay.payment-url — игроки не видят ссылку");
        check(sender, "Сессия не обходит 2FA", !cfg.sessionSkips2fa() || !cfg.totpEnabled(),
                "auth.session-skips-2fa: true — IP-сессия отключает 2FA (осознанный выбор?)");
        check(sender, "Счётчики попыток сохраняются", cfg.storageWriteAsync(),
                "storage.write-async: false — запись на диск в главном потоке");
        check(sender, "Политика паролей", cfg.minPasswordLength() >= 8,
                "auth.min-password-length < 8 — разрешены короткие пароли");
        check(sender, "Тестовый запрос к DonatePay", accessManager.probeDonatePay(),
                "не удалось подключиться к API DonatePay — проверьте токен и сеть");
        check(sender, "Аудит-лог", cfg.auditEnabled(),
                "audit.enabled: false — история событий не сохраняется");
        check(sender, "Discord-уведомления", !cfg.discordEnabled() || !cfg.notificationEvents().isEmpty(),
                "вебхук задан, но notifications.events пуст — уведомления не отправляются");
        check(sender, "Хранилище доступно для записи", storeWritable(),
                "нет доступа на запись в data.json — данные могут не сохраняться");
        sender.sendMessage(Component.text("Проверок выполнено: 9. Запустите /pass stats для сводки.",
                NamedTextColor.GRAY));
    }

    private void check(CommandSender sender, String name, boolean ok, String warning) {
        sender.sendMessage(Component.text("[" + (ok ? "OK" : "ВНИМАНИЕ") + "] ", ok ? NamedTextColor.GREEN
                        : NamedTextColor.YELLOW)
                .append(Component.text(name, NamedTextColor.GRAY))
                .append(ok ? Component.empty() : Component.text(" — " + warning, NamedTextColor.YELLOW)));
    }

    private boolean storeWritable() {
        try {
            java.nio.file.Path folder = plugin.getDataFolder().toPath();
            java.nio.file.Files.createDirectories(folder);
            return java.nio.file.Files.isWritable(folder);
        } catch (Exception exception) {
            return false;
        }
    }

    private void showAudit(CommandSender sender, String[] args) {
        String playerFilter = args.length >= 2 ? args[1].trim() : "";
        int limit = 15;
        if (args.length >= 3) {
            try {
                limit = Math.clamp(Integer.parseInt(args[2]), 1, 100);
            } catch (NumberFormatException ignored) {
                limit = 15;
            }
        }
        var entries = accessManager.audit().read(playerFilter, null, limit);
        if (entries.isEmpty()) {
            sender.sendMessage(Component.text("Записей аудита не найдено.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("=== Аудит" + (playerFilter.isBlank() ? "" : " (" + playerFilter + ")")
                + " ===", NamedTextColor.GOLD));
        for (var entry : entries) {
            sender.sendMessage(Component.text(
                    (entry.has("time") ? entry.get("time").getAsString() : "") + " "
                            + (entry.has("event") ? entry.get("event").getAsString() : "")
                            + " | " + (entry.has("actor") ? entry.get("actor").getAsString() : "")
                            + (entry.has("target") && !entry.get("target").getAsString().isBlank()
                            ? " → " + entry.get("target").getAsString() : "")
                            + (entry.has("ip") && !entry.get("ip").getAsString().isBlank()
                            ? " (" + entry.get("ip").getAsString() + ")" : "")
                            + (entry.has("details") ? " " + entry.get("details") : ""),
                    NamedTextColor.GRAY));
        }
    }

    private void handleBackup(CommandSender sender, String[] args, String label) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "create";
        switch (action) {
            case "create", "now" -> {
                var created = plugin.createBackup("manual");
                sender.sendMessage(created.map(path -> Component.text("✓ Бэкап создан: " + path.getFileName(),
                                NamedTextColor.GREEN))
                        .orElseGet(() -> Component.text("Не удалось создать бэкап.", NamedTextColor.RED)));
            }
            case "list" -> {
                var backups = accessManager.store().listBackups();
                if (backups.isEmpty()) {
                    sender.sendMessage(Component.text("Бэкапов нет.", NamedTextColor.YELLOW));
                    return;
                }
                sender.sendMessage(Component.text("=== Бэкапы ===", NamedTextColor.GOLD));
                for (var backup : backups) {
                    sender.sendMessage(Component.text("- " + backup.getFileName(), NamedTextColor.GRAY));
                }
                sender.sendMessage(Component.text("Восстановление: /" + label + " backup restore <файл>",
                        NamedTextColor.DARK_GRAY));
            }
            case "restore" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text("Использование: /" + label + " backup restore <файл>",
                            NamedTextColor.YELLOW));
                    return;
                }
                java.nio.file.Path target = plugin.getDataFolder().toPath()
                        .resolve("backups").resolve(args[2].trim()).normalize();
                if (!target.startsWith(plugin.getDataFolder().toPath().resolve("backups"))) {
                    sender.sendMessage(Component.text("Недопустимый путь к файлу бэкапа.", NamedTextColor.RED));
                    return;
                }
                boolean restored = accessManager.store().restoreBackup(target);
                sender.sendMessage(Component.text(restored
                                ? "✓ Данные восстановлены из " + target.getFileName() + "."
                                : "Не удалось восстановить из указанного файла.",
                        restored ? NamedTextColor.GREEN : NamedTextColor.RED));
                accessManager.audit().log(AuditLog.STORAGE_RESTORE, sender.getName(), "", null,
                        AuditLog.details("file", target.getFileName().toString(), "ok", restored));
            }
            default -> sender.sendMessage(Component.text("Использование: /" + label
                    + " backup <create|list|restore <файл>>", NamedTextColor.YELLOW));
        }
    }

    private void showOwnPassStatus(Player player) {
        long now = System.currentTimeMillis();
        Optional<RegistrationStore.PlayerAccount> opt = accessManager.store().getAccount(player.getName());
        if (opt.isEmpty() || !opt.get().hasActivePass(now)) {
            accessManager.sendNoPassPrompt(player, false);
            return;
        }
        RegistrationStore.PlayerAccount account = opt.get();
        player.sendMessage(Component.text("==============================================", NamedTextColor.GOLD));
        player.sendMessage(Component.text("Статус проходки: ", NamedTextColor.GRAY)
                .append(Component.text("АКТИВНА", NamedTextColor.GREEN, TextDecoration.BOLD)));
        player.sendMessage(Component.text("Действует до: ", NamedTextColor.GRAY)
                .append(Component.text(AccessManager.formatTimestamp(account.passUntil), NamedTextColor.AQUA))
                .append(Component.text(" (осталось " + AccessManager.formatRemainingTime(account.passUntil, now) + ")", NamedTextColor.YELLOW)));
        player.sendMessage(Component.text("Регистрация пароля: ", NamedTextColor.GRAY)
                .append(Component.text(account.isRegistered() ? "зарегистрирован" : "ожидает (/register)",
                        account.isRegistered() ? NamedTextColor.GREEN : NamedTextColor.YELLOW)));
        player.sendMessage(Component.text("Защита TOTP 2FA: ", NamedTextColor.GRAY)
                .append(Component.text(account.isTotpEnabled() ? "включена" : "не подключена (/2fa setup)",
                        account.isTotpEnabled() ? NamedTextColor.GREEN : NamedTextColor.GRAY)));
        if (accessManager.config().paymentCodeEnabled()) {
            accessManager.donations().paymentCode(player.getName()).ifPresent(code ->
                    player.sendMessage(Component.text("Код вашего платежа (укажите в имени донатера): ", NamedTextColor.GRAY)
                            .append(Component.text(code, NamedTextColor.GOLD, TextDecoration.BOLD))));
        }
        if (account.paymentsCount > 0) {
            player.sendMessage(Component.text("Оплачено всего: ", NamedTextColor.GRAY)
                    .append(Component.text(DonationService.formatRubles(account.totalPaidKopecks) + " ₽ ("
                            + account.paymentsCount + " платежей)", NamedTextColor.AQUA)));
        }
        player.sendMessage(Component.text("Продлить: ", NamedTextColor.GRAY)
                .append(Component.text(accessManager.config().hasPaymentUrl()
                        ? accessManager.config().donatePayPaymentUrl() : "/pass", NamedTextColor.AQUA))
                .append(Component.text(" · Сессии: /session · Выйти: /logout", NamedTextColor.DARK_GRAY)));
        player.sendMessage(Component.text("==============================================", NamedTextColor.GOLD));
    }

    private void showAccountDetails(CommandSender sender, String targetName) {
        long now = System.currentTimeMillis();
        Optional<RegistrationStore.PlayerAccount> opt = accessManager.store().getAccount(targetName);
        if (opt.isEmpty()) {
            sender.sendMessage(Component.text("Игрок " + targetName + " не найден в базе плагина.", NamedTextColor.RED));
            return;
        }
        RegistrationStore.PlayerAccount account = opt.get();
        boolean activePass = account.hasActivePass(now);
        Player online = Bukkit.getPlayerExact(account.playerName);
        String stageText = online == null ? "оффлайн" : accessManager.getStage(online).name();

        sender.sendMessage(Component.text("=== Информация об игроке " + account.playerName + " ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Проходка: ", NamedTextColor.GRAY)
                .append(Component.text(activePass ? "АКТИВНА" : "НЕТ / ИСТЕКЛА",
                        activePass ? NamedTextColor.GREEN : NamedTextColor.RED)));
        if (account.passUntil > 0) {
            sender.sendMessage(Component.text("Срок до: ", NamedTextColor.GRAY)
                    .append(Component.text(AccessManager.formatTimestamp(account.passUntil)
                            + " (" + AccessManager.formatRemainingTime(account.passUntil, now) + ")", NamedTextColor.AQUA)));
            sender.sendMessage(Component.text("Источник выдачи: ", NamedTextColor.GRAY)
                    .append(Component.text(account.lastGrantSource.isBlank() ? "неизвестно" : account.lastGrantSource, NamedTextColor.WHITE)));
        }
        sender.sendMessage(Component.text("Пароль зарегистрирован: ", NamedTextColor.GRAY)
                .append(Component.text(account.isRegistered() ? "да" : "нет",
                        account.isRegistered() ? NamedTextColor.GREEN : NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text("TOTP 2FA: ", NamedTextColor.GRAY)
                .append(Component.text(account.isTotpEnabled()
                                ? "включена (" + AccessManager.formatTimestamp(account.totpEnabledAt) + ")"
                                : "отключена",
                        account.isTotpEnabled() ? NamedTextColor.GREEN : NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text("Статус на сервере: ", NamedTextColor.GRAY)
                .append(Component.text(stageText, NamedTextColor.WHITE)));
        if (account.passUntil > 0) {
            sender.sendMessage(Component.text("Последняя выдача: ", NamedTextColor.GRAY)
                    .append(Component.text(account.lastGrantDays + " дн., источник "
                            + (account.lastGrantSource.isBlank() ? "неизвестно" : account.lastGrantSource)
                            + ", " + AccessManager.formatTimestamp(account.lastGrantAt), NamedTextColor.WHITE)));
        }
        if (account.paymentsCount > 0) {
            sender.sendMessage(Component.text("Всего оплачено: ", NamedTextColor.GRAY)
                    .append(Component.text(DonationService.formatRubles(account.totalPaidKopecks) + " ₽ ("
                            + account.paymentsCount + " платежей, последний: "
                            + AccessManager.formatTimestamp(account.lastPaymentAt) + ")", NamedTextColor.AQUA)));
        }
        if (account.lastAuthIp != null && !account.lastAuthIp.isBlank()) {
            sender.sendMessage(Component.text("Последний вход: ", NamedTextColor.GRAY)
                    .append(Component.text(AuditLog.maskIp(account.lastAuthIp) + " "
                            + AccessManager.formatTimestamp(account.lastAuthAt), NamedTextColor.WHITE)));
        }
        if (account.mute != null && account.mute.until > now) {
            sender.sendMessage(Component.text("Мут: ", NamedTextColor.GRAY)
                    .append(Component.text("до " + AccessManager.formatTimestamp(account.mute.until)
                            + (account.mute.reason.isBlank() ? "" : " (" + account.mute.reason + ")"),
                            NamedTextColor.RED)));
        }
        if (account.sessions != null && !account.sessions.isEmpty()) {
            sender.sendMessage(Component.text("Сессии: ", NamedTextColor.GRAY)
                    .append(Component.text(account.sessions.size() + " шт. (/pass sessions " + account.playerName + ")",
                            NamedTextColor.WHITE)));
        }
        if (accessManager.config().paymentCodeEnabled()) {
            accessManager.donations().paymentCode(account.playerName).ifPresent(code ->
                    sender.sendMessage(Component.text("Код платежа: ", NamedTextColor.GRAY)
                            .append(Component.text(code, NamedTextColor.GOLD))));
        }
    }

    private void showPluginStatus(CommandSender sender) {
        long now = System.currentTimeMillis();
        RegistrationStore store = accessManager.store();
        PluginConfig cfg = accessManager.config();

        sender.sendMessage(Component.text("=== Статус CHRDK_F_REGISTER ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Активных проходок: ", NamedTextColor.GRAY)
                .append(Component.text(Integer.toString(store.getActivePassCount(now)), NamedTextColor.GREEN)));
        sender.sendMessage(Component.text("Зарегистрировано паролей: ", NamedTextColor.GRAY)
                .append(Component.text(store.getRegisteredCount() + " / " + store.getTotalAccountCount(), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Аккаунтов с TOTP 2FA: ", NamedTextColor.GRAY)
                .append(Component.text(store.getTotpEnabledCount() + " (2FA в конфиге: "
                        + (cfg.totpEnabled() ? "вкл." : "выкл.") + ")", NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("DonatePay API: ", NamedTextColor.GRAY)
                .append(Component.text(cfg.donatePayReady() ? "подключён" : "токен не задан в config.yml",
                        cfg.donatePayReady() ? NamedTextColor.GREEN : NamedTextColor.RED)));
        sender.sendMessage(Component.text("Условия доната: ", NamedTextColor.GRAY)
                .append(Component.text(cfg.minimumDonationRubles() + " ₽ на " + cfg.grantDays() + " дн.", NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text("Сессия по IP: ", NamedTextColor.GRAY)
                .append(Component.text(cfg.sessionMinutes() + " мин.", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Встроенный чат: ", NamedTextColor.GRAY)
                .append(Component.text(cfg.chat().enabled() ? "включён" : "выключен",
                        cfg.chat().enabled() ? NamedTextColor.GREEN : NamedTextColor.RED))
                .append(Component.text(", локальный радиус: " + (int) cfg.chat().localRadius() + " блоков",
                        NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Автосообщения: ", NamedTextColor.GRAY)
                .append(Component.text(cfg.chat().autoMessagesEnabled()
                                ? "каждые " + cfg.chat().autoMessagesIntervalSeconds() + " сек. ("
                                        + cfg.chat().autoMessages().size() + " шт.)"
                                : "выключены",
                        cfg.chat().autoMessagesEnabled() ? NamedTextColor.AQUA : NamedTextColor.YELLOW)));
    }

    private void sendAdminHelp(CommandSender sender, String label) {
        sender.sendMessage(Component.text("Команды управления проходками:", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/" + label + " add <ник>", NamedTextColor.YELLOW)
                .append(Component.text(" — выдать/продлить проходку на " + accessManager.config().grantDays() + " дней", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " remove <ник>", NamedTextColor.YELLOW)
                .append(Component.text(" — отозвать проходку игрока", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " check <ник>", NamedTextColor.YELLOW)
                .append(Component.text(" — проверить статус проходки, пароля и 2FA игрока", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " unregister <ник>", NamedTextColor.YELLOW)
                .append(Component.text(" — сбросить пароль и 2FA игрока", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " reset2fa <ник>", NamedTextColor.YELLOW)
                .append(Component.text(" — сбросить только TOTP 2FA игрока", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " status", NamedTextColor.YELLOW)
                .append(Component.text(" — общая статистика плагина", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " add <ник> [дней]", NamedTextColor.YELLOW)
                .append(Component.text(" — выдать/продлить проходку на произвольный срок", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " gift <ник> <дней>", NamedTextColor.YELLOW)
                .append(Component.text(" — подарить проходку", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " pending", NamedTextColor.YELLOW)
                .append(Component.text(" — нераспознанные донаты", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " approve <id> [ник] [дней]", NamedTextColor.YELLOW)
                .append(Component.text(" — подтвердить платёж и выдать проходку", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " reject <id> [причина]", NamedTextColor.YELLOW)
                .append(Component.text(" — отклонить платёж", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " sessions <ник>", NamedTextColor.YELLOW)
                .append(Component.text(" — активные сессии и IP игрока", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " logoutall <ник>", NamedTextColor.YELLOW)
                .append(Component.text(" — завершить все сессии игрока", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " stats", NamedTextColor.YELLOW)
                .append(Component.text(" — выручка, конверсия и тарифы", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " doctor", NamedTextColor.YELLOW)
                .append(Component.text(" — самопроверка конфигурации", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " audit [ник] [N]", NamedTextColor.YELLOW)
                .append(Component.text(" — последние записи аудита", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " backup <create|list|restore>", NamedTextColor.YELLOW)
                .append(Component.text(" — резервные копии данных", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " reload", NamedTextColor.YELLOW)
                .append(Component.text(" — перезагрузить config.yml", NamedTextColor.GRAY)));
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        String cmdName = command.getName().toLowerCase(Locale.ROOT);
        if ("2fa".equals(cmdName)) {
            if (args.length == 1) {
                if (sender instanceof Player player && accessManager.getStage(player) == AccessManager.PlayerStage.NEEDS_2FA) {
                    return List.of();
                }
                String prefix = args[0].toLowerCase(Locale.ROOT);
                List<String> matches = new ArrayList<>();
                for (String sub : TWO_FACTOR_SUBCOMMANDS) {
                    if (sub.startsWith(prefix)) {
                        matches.add(sub);
                    }
                }
                if (sender.hasPermission("chrdkregister.admin") && "reset".startsWith(prefix)) {
                    matches.add("reset");
                }
                return matches;
            }
            if (args.length == 2
                    && ("reset".equalsIgnoreCase(args[0]) || "reset2fa".equalsIgnoreCase(args[0]))
                    && sender.hasPermission("chrdkregister.admin")) {
                return completePlayerNames(args[1]);
            }
            return List.of();
        }

        if (!"pass".equals(cmdName)) {
            return List.of();
        }
        if (!sender.hasPermission("chrdkregister.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String sub : ADMIN_SUBCOMMANDS) {
                if (sub.startsWith(prefix)) {
                    matches.add(sub);
                }
            }
            return matches;
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("add".equals(sub) || "grant".equals(sub)
                    || "remove".equals(sub) || "revoke".equals(sub)
                    || "check".equals(sub) || "info".equals(sub)
                    || "unregister".equals(sub) || "resetpass".equals(sub)
                    || "reset2fa".equals(sub) || "disable2fa".equals(sub) || "un2fa".equals(sub)
                    || "sessions".equals(sub) || "logoutall".equals(sub)) {
                return completePlayerNames(args[1]);
            }
            if ("gift".equals(sub)) {
                return completePlayerNames(args[1]);
            }
            if ("audit".equals(sub)) {
                return completePlayerNames(args[1]);
            }
            if ("backup".equals(sub)) {
                return List.of("create", "list", "restore");
            }
        }
        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String action = args[1].toLowerCase(Locale.ROOT);
            if ("gift".equals(sub) || ("add".equals(sub) && args[2].isBlank())) {
                return List.of("30", "90", "365");
            }
            if ("backup".equals(sub) && "restore".equals(action)) {
                return accessManager.store().listBackups().stream()
                        .map(path -> path.getFileName().toString())
                        .toList();
            }
        }
        return List.of();
    }

    private List<String> completePlayerNames(String rawPrefix) {
        String prefix = rawPrefix.toLowerCase(Locale.ROOT);
        Set<String> candidates = new LinkedHashSet<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                candidates.add(online.getName());
            }
        }
        for (String stored : accessManager.store().getAllAccountNames()) {
            if (stored.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                candidates.add(stored);
            }
        }
        return new ArrayList<>(candidates);
    }

    private static String joinArgsFrom(String[] args, int startIndex) {
        if (startIndex >= args.length) return "";
        return String.join("", Arrays.copyOfRange(args, startIndex, args.length));
    }
}
