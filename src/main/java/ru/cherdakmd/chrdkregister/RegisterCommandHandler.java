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
            List.of("add", "remove", "check", "unregister", "reset2fa", "status", "reload");
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
            default -> false;
        };
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
                            "Админ-команды: /" + label + " <add|remove|check|unregister|reset2fa|status|reload> [ник]",
                            NamedTextColor.DARK_GRAY));
                }
            } else {
                showPluginStatus(sender);
                sendAdminHelp(sender, label);
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
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
                    sender.sendMessage(Component.text("Использование: /" + label + " add <ник>", NamedTextColor.YELLOW));
                    return true;
                }
                String targetName = args[1].trim();
                if (!AccessManager.isValidMinecraftName(targetName)) {
                    sender.sendMessage(Component.text("Некорректный ник Minecraft: " + targetName, NamedTextColor.RED));
                    return true;
                }
                RegistrationStore.PlayerAccount account = accessManager.grantPassManually(targetName);
                sender.sendMessage(Component.text("✓ Проходка выдана игроку ", NamedTextColor.GREEN)
                        .append(Component.text(account.playerName, NamedTextColor.GOLD, TextDecoration.BOLD))
                        .append(Component.text(" на " + accessManager.config().grantDays() + " дн. (до "
                                + AccessManager.formatTimestamp(account.passUntil) + ").", NamedTextColor.GREEN)));
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
                    || "reset2fa".equals(sub) || "disable2fa".equals(sub) || "un2fa".equals(sub)) {
                return completePlayerNames(args[1]);
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
