package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Handles /register, /login, /changepassword, and /pass (/fregister, /prohodka) commands.
 */
public final class RegisterCommandHandler implements CommandExecutor, TabCompleter {
    private static final List<String> ADMIN_SUBCOMMANDS =
            List.of("add", "remove", "check", "unregister", "status", "reload");

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
        if (args.length != 1) {
            player.sendMessage(Component.text("Использование: /login <пароль>", NamedTextColor.YELLOW));
            return true;
        }
        return accessManager.handleLoginCommand(player, args[0]);
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

    private boolean handlePass(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                showOwnPassStatus(player);
                if (sender.hasPermission("chrdkregister.admin")) {
                    sender.sendMessage(Component.text(
                            "Админ-команды: /" + label + " <add|remove|check|unregister|status|reload> [ник]",
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
                    sender.sendMessage(Component.text("✓ Пароль игрока " + targetName + " сброшен. Проходка сохранена.", NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("Игрок " + targetName + " ещё не регистрировал пароль.", NamedTextColor.RED));
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
        sender.sendMessage(Component.text("DonatePay API: ", NamedTextColor.GRAY)
                .append(Component.text(cfg.donatePayReady() ? "подключён" : "токен не задан в config.yml",
                        cfg.donatePayReady() ? NamedTextColor.GREEN : NamedTextColor.RED)));
        sender.sendMessage(Component.text("Условия доната: ", NamedTextColor.GRAY)
                .append(Component.text(cfg.minimumDonationRubles() + " ₽ на " + cfg.grantDays() + " дн.", NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text("Сессия по IP: ", NamedTextColor.GRAY)
                .append(Component.text(cfg.sessionMinutes() + " мин.", NamedTextColor.WHITE)));
    }

    private void sendAdminHelp(CommandSender sender, String label) {
        sender.sendMessage(Component.text("Команды управления проходками:", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/" + label + " add <ник>", NamedTextColor.YELLOW)
                .append(Component.text(" — выдать/продлить проходку на " + accessManager.config().grantDays() + " дней", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " remove <ник>", NamedTextColor.YELLOW)
                .append(Component.text(" — отозвать проходку игрока", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " check <ник>", NamedTextColor.YELLOW)
                .append(Component.text(" — проверить статус проходки и регистрации игрока", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " unregister <ник>", NamedTextColor.YELLOW)
                .append(Component.text(" — сбросить пароль игрока", NamedTextColor.GRAY)));
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
                    || "unregister".equals(sub) || "resetpass".equals(sub)) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
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
        }
        return List.of();
    }
}
