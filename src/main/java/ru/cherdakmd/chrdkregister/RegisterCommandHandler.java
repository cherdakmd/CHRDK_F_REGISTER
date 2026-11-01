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
                    "pending", "donations", "health", "approve", "reject", "sessions", "logoutall", "stats", "doctor", "audit",
                    "backup", "gift", "testpay", "testpay-live", "shoppay", "recheck", "donationsgui",
                    "luckperms", "update", "shopbuy", "shoprevoke", "shopgift");
    private static final List<String> MUTE_DURATIONS = List.of("10m", "30m", "1h", "6h", "1d", "7d");
    private static final List<String> TWO_FACTOR_SUBCOMMANDS =
            List.of("setup", "confirm", "disable", "status", "cancel", "qr");
    private static final List<String> PLAYER_SUBCOMMANDS =
            List.of("menu", "gui", "claim", "donate", "shop", "my");

    private final RegisterPlugin plugin;
    private final AccessManager accessManager;
    private Messages messages;

    public RegisterCommandHandler(RegisterPlugin plugin, AccessManager accessManager) {
        this.plugin = plugin;
        this.accessManager = accessManager;
        this.messages = plugin.messages();
    }

    /** Обновляет тексты после перезагрузки {@code messages.yml}. */
    public void refreshMessages() {
        this.messages = plugin.messages();
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        String cmdName = command.getName().toLowerCase(Locale.ROOT);
        return RegistrationCommandAccess.dispatch(sender, cmdName, args, accessManager::getStage, messages,
                () -> switch (cmdName) {
                    case "register" -> handleRegister(sender, args);
                    case "login" -> handleLogin(sender, args);
                    case "captcha" -> handleCaptcha(sender, args);
                    case "changepassword" -> handleChangePassword(sender, args);
                    case "2fa" -> handleTwoFactor(sender, label, args);
                    case "pass" -> handlePass(sender, label, args);
                    case "logout" -> handleLogout(sender);
                    case "session" -> handleSession(sender, args);
                    case "guide" -> handleGuide(sender);
                    default -> false;
        });
    }

    /** {@code /guide} — повторно выдать книгу-путеводитель. */
    private boolean handleGuide(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.players-only");
            return true;
        }
        if (!player.hasPermission("chrdkregister.guide")) {
            messages.send(player, "error.no-permission");
            return true;
        }
        GuideBookService guide = plugin.guide();
        if (guide == null) {
            messages.send(player, "guide.disabled");
            return true;
        }
        guide.give(player, true);
        return true;
    }

    private boolean handleLogout(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.players-only");
            return true;
        }
        return accessManager.handleLogoutCommand(player);
    }

    private boolean handleSession(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.players-only-session");
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
        messages.send(sender, "session.list-header", "player", playerName);
        if (sessions.isEmpty()) {
            messages.send(sender, "session.list-empty");
            return;
        }
        long sessionMillis = java.util.concurrent.TimeUnit.MINUTES.toMillis(accessManager.config().sessionMinutes());
        for (var session : sessions) {
            long age = session.ageMillis(now);
            boolean active = sessionMillis > 0 && age <= sessionMillis;
            messages.send(sender, active ? "session.list-entry-active" : "session.list-entry-expired",
                    "ip", AuditLog.maskIp(session.ip),
                    "uuid", session.uuid == null || session.uuid.isBlank()
                            ? "" : session.uuid.substring(0, Math.min(8, session.uuid.length())) + "…",
                    "last-seen", AccessManager.formatTimestamp(session.lastSeenAt),
                    "source", session.source);
        }
        if (playerName.equalsIgnoreCase(sender.getName())) {
            messages.send(sender, "session.list-hint");
        }
    }

    private boolean handleRegister(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.players-only");
            return true;
        }
        if (args.length != 2) {
            messages.send(player, "usage.register");
            return true;
        }
        return accessManager.handleRegisterCommand(player, args[0], args[1]);
    }

    private boolean handleLogin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.players-only");
            return true;
        }
        if (args.length == 0 && accessManager.getStage(player) == AccessManager.PlayerStage.NEEDS_2FA_SETUP) {
            // Пароль уже принят — /login без аргументов показывает, что осталось привязать 2FA.
            accessManager.sendTotpEnrollmentPrompt(player, true);
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            messages.send(player, "usage.login");
            PasswordKeypadService keypad = plugin.keypadLogin();
            if (keypad != null && keypad.openIfEligible(player)) {
                return true;
            }
            accessManager.showAuthDialogNow(player);
            return true;
        }
        String totpCode = args.length == 2 ? args[1] : null;
        return accessManager.handleLoginCommand(player, args[0], totpCode);
    }

    /** {@code /captcha <число>} — ответ на эскалационную проверку после серии неудачных входов. */
    private boolean handleCaptcha(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.players-only");
            return true;
        }
        if (args.length != 1) {
            if (accessManager.isCaptchaRequired(player.getUniqueId(), player.getName())) {
                accessManager.presentCaptchaChallenge(player);
            } else {
                messages.send(player, "captcha.not-required");
            }
            return true;
        }
        return accessManager.handleCaptchaAnswer(player, args[0]);
    }

    private boolean handleChangePassword(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "password.change.players-only");
            return true;
        }
        if (args.length != 2) {
            messages.send(player, "password.change.usage");
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
                messages.send(sender, "two-factor.reset-success", "target_name", targetName);
            } else {
                messages.send(sender, "two-factor.reset-not-enabled", "target_name", targetName);
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            messages.send(sender, "two-factor.reset-players-only");
            return true;
        }

        AccessManager.PlayerStage stage = accessManager.getStage(player);
        if (stage == AccessManager.PlayerStage.NO_PASS) {
            accessManager.sendNoPassPrompt(player, false);
            return true;
        }
        if (stage == AccessManager.PlayerStage.NEEDS_REGISTER) {
            messages.send(player, "two-factor.need-register");
            return true;
        }
        if (stage == AccessManager.PlayerStage.NEEDS_LOGIN) {
            messages.send(player, "two-factor.need-login");
            return true;
        }
        if (stage == AccessManager.PlayerStage.NEEDS_2FA) {
            if (args.length == 0) {
                accessManager.sendTwoFactorPrompt(player, false);
                accessManager.showAuthDialogNow(player);
                return true;
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (("verify".equals(sub) || "confirm".equals(sub) || "code".equals(sub)) && args.length >= 2) {
                return accessManager.handleTwoFactorLoginCode(player, joinArgsFrom(args, 1));
            }
            return accessManager.handleTwoFactorLoginCode(player, String.join("", args));
        }
        if (stage == AccessManager.PlayerStage.NEEDS_2FA_SETUP) {
            // Обязательная привязка: до неё в игру не пускаем, но /2fa setup/confirm/qr работают.
            if (args.length == 0) {
                accessManager.sendTotpEnrollmentPrompt(player, true);
                return true;
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "setup", "enable", "on", "bind" -> {
                    return accessManager.handleTwoFactorSetup(player);
                }
                case "confirm", "verify", "activate", "code" -> {
                    if (args.length < 2) {
                        messages.send(player, "two-factor.usage-confirm", "label", label);
                        return true;
                    }
                    return accessManager.handleTwoFactorConfirmSetup(player, joinArgsFrom(args, 1));
                }
                case "qr", "qrcode", "map" -> {
                    return accessManager.handleTwoFactorQrMap(player);
                }
                case "cancel" -> {
                    return accessManager.handleTwoFactorCancel(player);
                }
                case "status", "info" -> {
                    showOwnTwoFactorStatus(player, label);
                    return true;
                }
                default -> {
                    String candidateCode = String.join("", args);
                    if (TotpService.isValidCodeFormat(candidateCode)) {
                        return accessManager.handleTwoFactorConfirmSetup(player, candidateCode);
                    }
                    accessManager.sendTotpEnrollmentPrompt(player, true);
                    return true;
                }
            }
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
                    messages.send(player, "two-factor.usage-confirm", "label", label);
                    return true;
                }
                return accessManager.handleTwoFactorConfirmSetup(player, joinArgsFrom(args, 1));
            }
            case "disable", "off", "unbind", "remove" -> {
                if (args.length < 2) {
                    messages.send(player, "two-factor.usage-disable", "label", label);
                    return true;
                }
                return accessManager.handleTwoFactorDisable(player, joinArgsFrom(args, 1));
            }
            case "cancel" -> {
                return accessManager.handleTwoFactorCancel(player);
            }
            case "qr", "qrcode", "map" -> {
                return accessManager.handleTwoFactorQrMap(player);
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

        messages.send(player, "two-factor.status.header");
        player.sendMessage(messages.get("two-factor.status.state-label")
                .append(messages.get(enabled ? "common.enabled" : "common.disabled")));

        if (enabled) {
            Optional<RegistrationStore.PlayerAccount> accountOpt = accessManager.store().getAccount(player.getName());
            if (accountOpt.isPresent() && accountOpt.get().totpEnabledAt > 0) {                messages.send(player, "two-factor.status.enabled-since",
                        "since", AccessManager.formatTimestamp(accountOpt.get().totpEnabledAt));
            }
            player.sendMessage(messages.get("two-factor.status.disable-hint")
                    .append(messages.get("two-factor.status.disable-hint-command", "label", label)));
        } else if (pendingSecret.isPresent()) {
            player.sendMessage(messages.get("two-factor.status.pending-label")
                    .append(messages.get("two-factor.status.pending-hint")));
                    messages.send(player, "two-factor.status.pending",
                    "secret", TotpService.formatSecretForDisplay(pendingSecret.get()));
            player.sendMessage(messages.get("two-factor.status.pending-confirm")
                    .append(messages.get("two-factor.status.pending-confirm-cmd", "label", label))
                    .append(messages.get("two-factor.status.pending-confirm-hint", "label", label)));
        } else {
            Component setupBtn = messages.get("two-factor.status.setup-button", "label", label)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.runCommand("/" + label + " setup"))
                    .hoverEvent(HoverEvent.showText(messages.get("two-factor.status.setup-button-hover")));
            messages.send(player, "two-factor.status.setup-hint");
            player.sendMessage(setupBtn);
        }
        messages.send(player, "two-factor.status.footer");
    }

    private void sendTwoFactorHelp(Player player, String label) {
        messages.sendList(player, "two-factor.help", "label", label);
    }

    private boolean handlePass(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                showOwnPassStatus(player);
                if (RegistrationCommandAccess.authenticatedSender(sender, accessManager::getStage)
                        && sender.hasPermission("chrdkregister.admin")) {
                    messages.send(sender, "pass.admin-help", "label", label);
                }
            } else {
                showPluginStatus(sender);
                sendAdminHelp(sender, label);
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (("menu".equals(sub) || "gui".equals(sub)) && sender instanceof Player player) {
            plugin.passMenu().openMenu(player);
            return true;
        }
        if (("donate".equals(sub) || "donates".equals(sub)) && sender instanceof Player player) {
            plugin.donateMenu().openMenu(player);
            return true;
        }
        if ("claim".equals(sub) && sender instanceof Player player) {
            if (args.length < 2) {
                messages.send(player, "usage.pass-claim", "label", label);
                return true;
            }
            long id;
            try {
                id = Long.parseLong(args[1]);
            } catch (NumberFormatException exception) {
                messages.send(player, "error.donation-id-number");
                return true;
            }
            String errorKey = accessManager.donations().claim(id, player.getName(),
                    AccessManager.getPlayerIp(player), System.currentTimeMillis());
            if (errorKey.isEmpty()) {
                messages.send(player, "pass.claim-success", "id", id);
            } else {
                messages.send(player, errorKey, "id", id);
            }
            return true;
        }
        if ("shop".equals(sub) || "catalog".equals(sub)) {
            showShop(sender, label);
            return true;
        }
        if ("my".equals(sub) || "purchases".equals(sub)) {
            showPurchases(sender, args, label);
            return true;
        }
        if (!sender.hasPermission("chrdkregister.admin")) {
            if (sender instanceof Player player) {
                showOwnPassStatus(player);
            } else {
                messages.send(sender, "error.no-permission");
            }
            return true;
        }

        switch (sub) {
            case "boost" -> {
                if (accessManager.config().trademc().enabled()) {
                    messages.send(sender, "boost.disabled");
                    return true;
                }
                if (!(sender instanceof Player boostPlayer)) {
                    messages.send(sender, "error.players-only");
                    return true;
                }
                if (args.length < 2) {
                    messages.send(boostPlayer, "usage.pass-boost", "label", label);
                    return true;
                }
                String boostArg = args[1].trim();
                if (boostArg.equalsIgnoreCase("reset") || boostArg.equalsIgnoreCase("off")) {
                    boolean wasBound = accessManager.boostyManager().unlink(boostPlayer.getName());
                    messages.send(boostPlayer, wasBound ? "pass.boost-unbound" : "pass.boost-not-bound");
                    return true;
                }
                accessManager.bindBoostyAsync(boostPlayer, boostArg);
                return true;
            }
            case "add", "grant", "gift" -> {
                messages.send(sender, "paid-only.blocked");
                return true;
            }
            case "pending" -> {
                var pending = accessManager.donations().pending(20);
                if (pending.isEmpty()) {
                    messages.send(sender, "donation.pending-empty");
                    return true;
                }
                messages.send(sender, "donation.pending-header", "size", pending.size());
                for (RegistrationStore.DonationRecord record : pending) {                    messages.send(sender, "donation.pending-entry",
                            "id", record.id,
                            "donor", record.donorName,
                            "amount", record.amountRubles(),
                            "status", record.status,
                            "note", record.note.isBlank() ? "" : " (" + record.note + ")",
                            "suggested", record.suggestedName.isBlank()
                                    ? "" : messages.mini("donation.suggested", "name", record.suggestedName),
                            "created", AccessManager.formatTimestamp(record.createdAt));
                }
                messages.send(sender, "donation.pending-hint", "label", label);
                return true;
            }
            case "approve" -> {
                if (args.length < 2) {
                    messages.send(sender, "usage.pass-approve", "label", label);
                    return true;
                }
                long id;
                try {
                    id = Long.parseLong(args[1]);
                } catch (NumberFormatException exception) {
                    messages.send(sender, "error.donation-id-number");
                    return true;
                }
                var record = accessManager.store().getDonation(id).orElse(null);
                if (record == null) {
                    messages.send(sender, "donation.not-found", "id", id);
                    return true;
                }
                String targetName = args.length >= 3 ? args[2].trim()
                        : (record.suggestedName.isBlank() ? record.donorName : record.suggestedName);
                int days = 0;
                if (args.length >= 4) {
                    try {
                        days = Math.clamp(Integer.parseInt(args[3]), 1, 3650);
                    } catch (NumberFormatException exception) {
                        messages.send(sender, "error.days-number");
                        return true;
                    }
                }
                if (!AccessManager.isValidMinecraftName(targetName)) {
                    messages.send(sender, "error.invalid-nickname-soft", "target_name", targetName);
                    return true;
                }
                if (accessManager.donations().approve(id, targetName, days, sender.getName(),
                        System.currentTimeMillis())) {
                    messages.send(sender, "donation.approved", "id", id, "target_name", targetName);
                } else {
                    messages.send(sender, "donation.approve-failed", "id", id);
                }
                return true;
            }
            case "reject" -> {
                if (args.length < 2) {
                    messages.send(sender, "usage.pass-reject", "label", label);
                    return true;
                }
                long id;
                try {
                    id = Long.parseLong(args[1]);
                } catch (NumberFormatException exception) {
                    messages.send(sender, "error.donation-id-number");
                    return true;
                }
                String reason = args.length >= 3
                        ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                        : "";
                if (accessManager.donations().reject(id, sender.getName(), reason, System.currentTimeMillis())) {
                    messages.send(sender, "donation.rejected", "id", id);
                } else {
                    messages.send(sender, "donation.not-found-short", "id", id);
                }
                return true;
            }
            case "donationsgui" -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    messages.send(sender, "error.players-only");
                    return true;
                }
                plugin.adminDonationsGui().openMenu(player);
                return true;
            }
            case "donations", "journal" -> {
                String filter = args.length >= 2
                        ? args[1].toLowerCase(Locale.ROOT) : "all";
                if (!DonationService.JOURNAL_FILTERS.contains(filter)) {
                    messages.send(sender, "usage.pass-donations", "label", label);
                    return true;
                }
                int limit = 10;
                if (args.length >= 3) {
                    try {
                        limit = Math.clamp(Integer.parseInt(args[2]), 1, 50);
                    } catch (NumberFormatException ignored) {
                        limit = 10;
                    }
                }
                List<RegistrationStore.DonationRecord> all =
                        accessManager.store().listDonations(null, 200);
                List<RegistrationStore.DonationRecord> shown = new ArrayList<>();
                for (RegistrationStore.DonationRecord record : all) {
                    if (DonationService.journalFilterMatches(record.donationStatus(), filter)) {
                        shown.add(record);
                    }
                    if (shown.size() >= limit) break;
                }
                messages.send(sender, "donation.journal-header",
                        "size", shown.size(), "filter", journalFilterLabel(filter));
                if (shown.isEmpty()) {
                    messages.send(sender, "donation.journal-empty", "label", label);
                    return true;
                }
                for (RegistrationStore.DonationRecord record : shown) {
                    RegistrationStore.DonationStatus status = record.donationStatus();
                    // detail собирается вручную: в нём и ник из журнала, и готовая
                    // подсказка «donation.suggested». Пользовательские части
                    // экранируются поштучно, поэтому всю строку можно пометить
                    // доверенной — иначе разметка подсказки вышла бы литералами.
                    String detail;
                    if (status == RegistrationStore.DonationStatus.GRANTED) {
                        detail = " → " + Messages.escape(record.grantedTo) + " (+" + record.days + " дн.)"
                                + (record.decidedBy == null || record.decidedBy.isBlank()
                                        ? "" : " (" + Messages.escape(record.decidedBy) + ")");
                    } else if (status == RegistrationStore.DonationStatus.REJECTED) {
                        detail = record.decidedBy == null || record.decidedBy.isBlank()
                                ? "" : " (" + Messages.escape(record.decidedBy) + ")";
                    } else {
                        detail = (record.note.isBlank() ? "" : " (" + Messages.escape(record.note) + ")")
                                + (record.suggestedName.isBlank() ? ""
                                        : messages.raw("donation.suggested", "name", record.suggestedName));
                    }
                    messages.send(sender, "donation.journal-entry",
                            "source", EasyDonateClient.SOURCE.equals(record.source)
                                    ? "ed" : "dp",
                            "id", record.id,
                            "donor", record.donorName,
                            "amount", record.amountRubles(),
                            "status", donationStatusLabel(status),
                            "detail", Messages.trusted(detail),
                            "created", AccessManager.formatTimestamp(record.createdAt));
                }
                messages.send(sender, "donation.journal-hint", "label", label);
                return true;
            }
            case "health" -> {
                if (accessManager.config().trademc().enabled()) {
                    messages.send(sender, "donation.health-trademc-mode");
                    return true;
                }
                java.util.List<AccessManager.DonateHealthCard> cards = accessManager.donateHealthCards();
                messages.send(sender, "donation.health-header");
                boolean anyReady = cards.stream().anyMatch(AccessManager.DonateHealthCard::ready);
                for (AccessManager.DonateHealthCard card : cards) {
                    if (!card.ready()) {
                        if (!anyReady) {
                            messages.send(sender, "donation.health-service-off", "service", card.serviceName());
                        }
                        continue;
                    }
                    String state = switch (card.state()) {
                        case OK -> messages.raw("donation.health-ok");
                        case FLAKY -> messages.raw("donation.health-flaky",
                                "fails", card.failStreak());
                        case NO_CONTACT -> messages.raw("donation.health-down",
                                "fails", card.failStreak());
                    };
                    messages.send(sender, "donation.health-state",
                            "service", card.serviceName(),
                            "state", Messages.trusted(state),
                            "poll_seconds", card.pollSeconds(),
                            "cursor", card.cursorId());
                    if (card.apiWaitMillis() > 0) {
                        messages.send(sender, "donation.health-api-window",
                                "seconds", Math.max(1, AccessManager.waitSeconds(card.apiWaitMillis())));
                    }
                    if (card.lastSuccessAtMillis() > 0) {
                        messages.send(sender, "donation.health-last",
                                "service", card.serviceName(),
                                "ago", AccessManager.formatDuration(
                                        System.currentTimeMillis() - card.lastSuccessAtMillis()),
                                "error", card.lastError() == null ? "—" : card.lastError());
                    }
                }
                long attempts = cards.stream().mapToLong(AccessManager.DonateHealthCard::attempts).sum();
                long successes = cards.stream().mapToLong(AccessManager.DonateHealthCard::successes).sum();
                long failures = cards.stream().mapToLong(AccessManager.DonateHealthCard::failures).sum();
                long processedTotal = cards.stream().mapToLong(AccessManager.DonateHealthCard::processedTransactions).sum();
                long avgLatency = cards.stream().mapToLong(AccessManager.DonateHealthCard::avgLatencyMs).max().orElse(0);
                long maxLatency = cards.stream().mapToLong(AccessManager.DonateHealthCard::maxLatencyMs).max().orElse(0);
                int pendingCount = accessManager.donations().pendingCount();
                messages.send(sender, "donation.health-stats",
                        "attempts", attempts, "ok", successes,
                        "fail", failures, "uptime", DonatePulse.uptimePercent(attempts, successes),
                        "avg_ms", avgLatency, "max_ms", maxLatency);
                messages.send(sender, "donation.health-stats2",
                        "processed", processedTotal,
                        "pending", pendingCount,
                        "stale", pendingCount == 0 ? "" : " (разбор: /" + label + " pending)");
                messages.send(sender, "donation.health-footer", "label", label);
                return true;
            }
            case "sessions" -> {
                if (args.length < 2) {
                    messages.send(sender, "usage.pass-sessions", "label", label);
                    return true;
                }
                showSessions(sender, args[1].trim());
                return true;
            }
            case "logoutall" -> {
                if (args.length < 2) {
                    messages.send(sender, "usage.pass-logoutall", "label", label);
                    return true;
                }
                String targetName = args[1].trim();
                boolean cleared = accessManager.clearAllSessions(targetName);
                accessManager.lockOnlinePlayer(targetName,
                        "Все сессии вашего аккаунта завершены администратором — войдите заново.");
                        messages.send(sender, cleared ? "session.cleared" : "session.none-active",
                        "player", targetName);
                return true;
            }
            case "testpay", "testpay-live" -> {
                messages.send(sender, "paid-only.test-disabled");
                return true;
            }
            case "shoppay" -> {
                // Хук магазина TradeMC: панель шлёт по RCON 'pass shoppay <ник> <сумма_руб>'
                // после оплаты товара — проводим покупку полным донат-конвейером (журнал, тарифы, действия).
                if (args.length < 3) {
                    messages.send(sender, "usage.pass-shoppay", "label", label);
                    return true;
                }
                String donorName = args[1].trim();
                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                    if (amount < 0 || amount > 1_000_000) {
                        messages.send(sender, "error.amount-number");
                        return true;
                    }
                } catch (NumberFormatException exception) {
                    messages.send(sender, "error.amount-number");
                    return true;
                }
                // 1.4.167: сначала карточка витрины по цене. Панель TradeMC присылает
                // только сумму, поэтому «Странник, год» за 990 ₽ должен найтись в
                // каталоге, а не превратиться в дни старого тарифа.
                ShopProduct card = plugin.shop().productByPrice(amount).orElse(null);
                if (card != null) {
                    PaymentConfirmation payment = PaymentConfirmation.confirm(sender, PaymentConfirmation.reference(args),
                            String.join(" ", args), accessManager);
                    if (payment == null) return true;
                    ShopService.Result result = plugin.shop().grant(donorName, card.id(), 0, payment);
                    switch (result.status()) {
                        case GRANTED -> messages.send(sender, "donation.shoppay-card",
                                "amount", amount,
                                "product", Messages.stripTags(card.name()),
                                "name", result.playerName(),
                                "days", result.days(),
                                "until", AccessManager.formatTimestamp(result.untilMillis()));
                        case RANK_HIGHER, TIME_LIMIT, INVALID_BUNDLE, INVALID_RECIPIENT -> messages.send(sender, "shop.review-required",
                                "player", result.playerName(), "product", card.id(),
                                "reason", result.status().name(), "label", label);
                        default -> messages.send(sender, "donation.shoppay-rejected",
                                "amount", amount, "note", result.status().name());
                    }
                    return true;
                }
                // Цена без однозначной карточки не превращается в синтетический платёж.
                messages.send(sender, "paid-only.unknown-price");
                return true;
            }
            case "shopbuy" -> {
                handleShopBuy(sender, label, args);
                return true;
            }
            case "shoprevoke" -> {
                handleShopRevoke(sender, label, args);
                return true;
            }
            case "shopgift" -> {
                handleShopGift(sender, label, args);
                return true;
            }
            case "update" -> {
                handleUpdate(sender, label, args);
                return true;
            }
            case "luckperms" -> {
                // Авто-настройка LuckPerms по схеме CHRDK: default → <pass-group> → admin
                String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
                LuckPermsBootstrap bootstrap = accessManager.luckPermsBootstrap();
                if (!bootstrap.available()) {
                    messages.send(sender, "lp.unavailable");
                    return true;
                }
                switch (action) {
                    case "setup" -> {
                        messages.send(sender, "lp.setup-start");
                        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
                                bootstrap.setup().whenComplete((result, error) ->
                                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                                            if (error != null) {
                                                messages.send(sender, "lp.setup-fail",
                                                        "error", error.getMessage() == null
                                                                ? "unknown" : error.getMessage());
                                                return;
                                            }
                                            messages.send(sender, "lp.setup-done",
                                                    "groups", String.join(", ", result.groups()),
                                                    "created", result.createdCount(),
                                                    "nodes", result.nodeCount());
                                            if (accessManager.config().passGroup().isBlank()) {
                                                messages.send(sender, "lp.setup-hint-pass",
                                                        "group", bootstrap.passGroupName());
                                            }
                                        })));
                    }
                    case "status" -> {
                        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
                                bootstrap.status().whenComplete((statuses, error) ->
                                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                                            if (error != null) {
                                                messages.send(sender, "lp.setup-fail",
                                                        "error", error.getMessage() == null
                                                                ? "unknown" : error.getMessage());
                                                return;
                                            }
                                            messages.send(sender, "lp.status-header");
                                            for (LuckPermsBootstrap.GroupStatus line : statuses) {
                                                if (line.exists()) {
                                                    messages.send(sender, "lp.status-line",
                                                            "group", line.name(),
                                                            "weight", line.weight(),
                                                            "perms", line.permissionNodes());
                                                } else {
                                                    messages.send(sender, "lp.status-missing",
                                                            "group", line.name(),
                                                            "label", label);
                                                }
                                            }
                                        })));
                    }
                    default -> messages.send(sender, "usage.pass-luckperms", "label", label);
                }
                return true;
            }
            case "recheck" -> {
                if (accessManager.config().trademc().enabled()) {
                    messages.send(sender, "donation.recheck-unavailable");
                    return true;
                }
                if (!accessManager.config().donatePayReady() && !accessManager.config().easyDonateReady()) {
                    messages.send(sender, "donation.recheck-unavailable");
                    return true;
                }
                long apiWait = accessManager.config().donatePayReady()
                        ? accessManager.donatePayApiWaitMillis() : 0;
                if (apiWait > 0) {
                    // окно API DonatePay занято (раз в 20 с) — опрос дождётся своего слота,
                    // но честно предупреждаем, что ответ будет не мгновенный
                    messages.send(sender, "donation.recheck-waiting",
                            "seconds", Math.max(1, AccessManager.waitSeconds(apiWait)));
                } else {
                    messages.send(sender, "donation.recheck-started", "label", label);
                }
                accessManager.forceDonationPoll(report -> {
                    if (report.error() != null) {
                        messages.send(sender, "donation.recheck-failed", "error", report.error());
                    } else {
                        messages.send(sender, "donation.recheck-done",
                                "count", report.processed(),
                                "pending", accessManager.donations().pendingCount(),
                                "label", label);
                    }
                });
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
            case "paidaudit" -> {
                plugin.writePaidAudit(sender);
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
                    messages.send(sender, "usage.pass-remove", "label", label);
                    return true;
                }
                String targetName = args[1].trim();
                if (accessManager.revokePass(targetName)) {
                    messages.send(sender, "pass.revoked-notice", "target_name", targetName);
                } else {
                    messages.send(sender, "pass.not-active", "target_name", targetName);
                }
                return true;
            }
            case "check", "info" -> {
                if (args.length < 2) {
                    messages.send(sender, "usage.pass-check", "label", label);
                    return true;
                }
                showAccountDetails(sender, args[1].trim());
                return true;
            }
            case "unregister", "resetpass" -> {
                if (args.length < 2) {
                    messages.send(sender, "usage.pass-unregister", "label", label);
                    return true;
                }
                String targetName = args[1].trim();
                if (accessManager.resetPassword(targetName)) {
                    messages.send(sender, "pass.password-reset-notice", "target_name", targetName);
                } else {
                    messages.send(sender, "pass.not-registered", "target_name", targetName);
                }
                return true;
            }
            case "reset2fa", "disable2fa", "un2fa" -> {
                if (args.length < 2) {
                    messages.send(sender, "usage.pass-reset2fa", "label", label);
                    return true;
                }
                String targetName = args[1].trim();
                if (accessManager.resetTwoFactor(targetName)) {
                    messages.send(sender, "pass.two-factor-reset-notice", "target_name", targetName);
                } else {
                    messages.send(sender, "pass.two-factor-not-enabled", "target_name", targetName);
                }
                return true;
            }
            case "status" -> {
                showPluginStatus(sender);
                return true;
            }
            case "bank", "bal" -> {
                handleAdminBank(sender, args);
                return true;
            }
            case "automessages", "automessage", "am" -> {
                handleAutoMessages(sender, args, label);
                return true;
            }
            case "reload" -> {
                plugin.reloadPluginConfig();
                java.util.List<String> problems = plugin.reloadMessages();
                refreshMessages();
                messages.send(sender, "config.reloaded");
                if (problems.isEmpty()) {
                    messages.send(sender, "config.messages-ok");
                } else {
                    messages.send(sender, "config.messages-problems", "count", problems.size());
                    for (String problem : problems.subList(0, Math.min(10, problems.size()))) {
                        messages.send(sender, "config.messages-problem", "problem", problem);
                    }
                }
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
        messages.send(sender, "stats.header");
        messages.send(sender, "stats.revenue",
                "day", DonationService.formatRubles(store.revenueKopecks(dayAgo, now)),
                "week", DonationService.formatRubles(store.revenueKopecks(weekAgo, now)),
                "month", DonationService.formatRubles(store.revenueKopecks(monthAgo, now)));
        messages.send(sender, "stats.pending",
                "count", accessManager.donations().pendingCount());
        int total = Math.max(1, store.getTotalAccountCount());
        int withPass = store.getActivePassCount(now);
        messages.send(sender, "stats.conversion",
                "active", withPass,
                "total", total,
                "percent", Math.round(100.0 * withPass / total));
        messages.send(sender, "stats.granted",
                "count", store.countDonations(RegistrationStore.DonationStatus.GRANTED));
        // сводка по каждому активному провайдеру: опросы и оборот за текущую сессию
        for (AccessManager.DonateHealthCard card : accessManager.donateHealthCards()) {
            if (!card.ready()) {
                continue;
            }
            messages.send(sender, "stats.provider-line",
                    "service", card.serviceName(),
                    "attempts", card.attempts(),
                    "ok", card.successes(),
                    "fail", card.failures(),
                    "processed", card.processedTransactions(),
                    "sum", DonationService.formatRubles(card.sessionSumKopecks()));
        }
        messages.send(sender, "stats.tiers", "tiers",
                Messages.trusted(DonationService.describeTiers(cfg, messages)));
    }

    private void showDoctor(CommandSender sender) {
        PluginConfig cfg = accessManager.config();
        messages.send(sender, "doctor.header");
        if (!cfg.donatePayEnabled()) {
            check(sender, "DonatePay (провайдер)", true, "выключен в конфиге (donatepay.enabled: false)");
        } else {
            check(sender, "DonatePay API-токен", cfg.donatePayReady(),
                    "не задан donatepay.api-token — авто-выдача проходок не работает");
            if (cfg.donatePayReady()) {
                check(sender, "Ссылка DonatePay для игроков", cfg.hasPaymentUrl(),
                        "не задан donatepay.payment-url — игроки не видят ссылку");
                check(sender, "Тестовый запрос к DonatePay", accessManager.probeDonatePay(),
                        "не удалось подключиться к API DonatePay — проверьте токен и сеть");
            }
        }
        check(sender, "Сессия не обходит 2FA", !cfg.sessionSkips2fa() || !cfg.totpEnabled(),
                "auth.session-skips-2fa: true — IP-сессия отключает 2FA (осознанный выбор?)");
        check(sender, "Счётчики попыток сохраняются", cfg.storageWriteAsync(),
                "storage.write-async: false — запись на диск в главном потоке");
        check(sender, "Политика паролей", cfg.minPasswordLength() >= 8,
                "auth.min-password-length < 8 — разрешены короткие пароли");
        // EasyDonate — только если хозяин решил его использовать
        if (cfg.easyDonateEnabled()) {
            check(sender, "EasyDonate Shop-Key", cfg.easyDonateReady(),
                    "easydonate.enabled: true, но shop-key пуст — опрос не ведётся");
            if (cfg.easyDonateReady()) {
                check(sender, "Ссылка EasyDonate для игроков", !cfg.easyDonatePaymentUrl().isBlank(),
                        "не задан easydonate.payment-url — ссылка для игроков не показывается");
                check(sender, "Тестовый запрос к EasyDonate",
                        accessManager.probeDonationProvider(EasyDonateClient.SOURCE),
                        "не удалось подключиться к API EasyDonate — проверьте shop-key и сеть");
            }
        }
        PluginConfig.BoostySettings boosty = cfg.boosty();
        if (boosty.enabled()) {
            check(sender, "Boosty: блог и токены", boosty.ready(),
                    boosty.blog().isBlank()
                            ? "не задан boosty.blog — URL блога на boosty.to"
                            : "нет boosty.token / refresh-token+device-id — снимите токены из браузера");
            if (boosty.ready()) {
                check(sender, "Тестовый запрос к Boosty", accessManager.probeBoosty(),
                        "API Boosty не ответил — проверьте токен, имя блога и сеть");
            }
        }
        check(sender, "Аудит-лог", cfg.auditEnabled(),
                "audit.enabled: false — история событий не сохраняется");
        check(sender, "Discord-уведомления", !cfg.discordEnabled() || !cfg.notificationEvents().isEmpty(),
                "вебхук задан, но notifications.events пуст — уведомления не отправляются");
        check(sender, "Хранилище доступно для записи", storeWritable(),
                "нет доступа на запись в data.json — данные могут не сохраняться");
        java.nio.file.Path pluginsDir = plugin.getDataFolder().getParentFile() != null
                ? plugin.getDataFolder().getParentFile().toPath() : null;
        var duplicateJars = DuplicatePluginJarDetector.scan(pluginsDir);
        check(sender, "Дубликаты JAR в plugins/", duplicateJars.isEmpty(),
                "найдены конфликтующие версии (Paper их отключает!): "
                        + DuplicatePluginJarDetector.formatSummary(duplicateJars));
        check(sender, "Vault (экономика и права)", plugin.externalHooks().economy().isPresent()
                        || plugin.externalHooks().permissions().isPresent(),
                "Vault не установлен — экономика для аддонов недоступна (опционально)");
        check(sender, "LuckPerms", plugin.externalHooks().luckPerms().isPresent(),
                cfg.passGroup().isBlank()
                        ? "LuckPerms не установлен (опционально — нужен для integrations.pass-group)"
                        : "задан integrations.pass-group, но LuckPerms не найден — группы не синхронизируются!");
        ShopCatalog shop = cfg.shop();
        check(sender, "Каталог товаров", !shop.enabled() || shop.usable(),
                "shop.enabled: true, но ни один товар не прошёл проверку — смотрите лог и shop.products");
        if (!shop.problems().isEmpty()) {
            check(sender, "Каталог: замечания", false,
                    String.join("; ", shop.problems().stream().limit(3).toList()));
        }
        ShopDiscount discount = cfg.shopDiscount();
        if (discount.usable()) {
            check(sender, "Скидка подписки", boosty.enabled() && !boosty.dryRun(),
                    "shop.boosty-bonus включён, но Boosty выключен или в dry-run — бонус не начислится");
        }
        long bundles = shop.products().stream().filter(ShopProduct::isBundle).count();
        if (bundles > 0) {
            check(sender, "Каталог: наборы", true, "наборов: " + bundles + " — состав раскрыт при выдаче");
        }
        messages.send(sender, "doctor.footer");
    }

    private void check(CommandSender sender, String name, boolean ok, String warning) {
        messages.sendList(sender, ok ? "doctor.check-ok" : "doctor.check-warning",
                "name", name, "warning", warning == null ? "" : warning);
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

    /** Свёртка статуса доната в цветное слово из messages.yml (литеральные ключи). */
    private String donationStatusLabel(RegistrationStore.DonationStatus status) {
        return switch (status) {
            case GRANTED -> messages.raw("donation.status-granted");
            case PENDING_NAME -> messages.raw("donation.status-pending-name");
            case PENDING_AMOUNT -> messages.raw("donation.status-pending-amount");
            case PENDING_REVIEW -> messages.raw("donation.status-pending-review");
            case REJECTED -> messages.raw("donation.status-rejected");
        };
    }

    /** Подпись фильтра журнала в заголовке (литеральные ключи). */
    private String journalFilterLabel(String filter) {
        return switch (filter) {
            case "granted" -> messages.raw("donation.filter-granted");
            case "pending" -> messages.raw("donation.filter-pending");
            case "rejected" -> messages.raw("donation.filter-rejected");
            case "name" -> messages.raw("donation.filter-name");
            case "amount" -> messages.raw("donation.filter-amount");
            case "review" -> messages.raw("donation.filter-review");
            default -> messages.raw("donation.filter-all");
        };
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
            messages.send(sender, "audit.empty");
            return;
        }        messages.send(sender, "audit.header",
                "filter", playerFilter.isBlank() ? "" : " (" + playerFilter + ")");
        for (var entry : entries) {
            messages.send(sender, "audit.entry",
                    "time", entry.has("time") ? entry.get("time").getAsString() : "",
                    "event", entry.has("event") ? entry.get("event").getAsString() : "",
                    "actor", entry.has("actor") ? entry.get("actor").getAsString() : "",
                    "target", entry.has("target") && !entry.get("target").getAsString().isBlank()
                            ? " → " + entry.get("target").getAsString() : "",
                    "ip", entry.has("ip") && !entry.get("ip").getAsString().isBlank()
                            ? " (" + entry.get("ip").getAsString() + ")" : "",
                    "details", entry.has("details") ? " " + entry.get("details") : "");
        }
    }

    private void handleBackup(CommandSender sender, String[] args, String label) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "create";
        switch (action) {
            case "create", "now" -> {
                var created = plugin.createBackup("manual");
                sender.sendMessage(created.map(path -> messages.get("backup.created", "path", path.getFileName()))
                        .orElseGet(() -> messages.get("backup.create-failed")));
            }
            case "list" -> {
                var backups = accessManager.store().listBackups();
                if (backups.isEmpty()) {
                    messages.send(sender, "backup.empty");
                    return;
                }
                messages.send(sender, "backup.header");
                for (var backup : backups) {
                    messages.send(sender, "backup.entry", "backup", backup.getFileName());
                }
                messages.send(sender, "backup.hint", "label", label);
            }
            case "restore" -> {
                if (args.length < 3) {
                    messages.send(sender, "usage.backup-restore", "label", label);
                    return;
                }
                java.nio.file.Path target = plugin.getDataFolder().toPath()
                        .resolve("backups").resolve(args[2].trim()).normalize();
                if (!target.startsWith(plugin.getDataFolder().toPath().resolve("backups"))) {
                    messages.send(sender, "backup.invalid-path");
                    return;
                }
                boolean restored = accessManager.store().restoreBackup(target);
                messages.send(sender, restored ? "backup.restored" : "backup.restore-failed",
                        "file", String.valueOf(target.getFileName()));
                accessManager.audit().log(AuditLog.STORAGE_RESTORE, sender.getName(), "", null,
                        AuditLog.details("file", target.getFileName().toString(), "ok", restored));
            }
            default -> messages.send(sender, "usage.backup", "label", label);
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
        messages.send(player, "pass.info.header-line");
        player.sendMessage(messages.get("pass.info.status")
                .append(messages.get("pass.info.active")));
        player.sendMessage(messages.get("pass.info.until-label")
                .append(Component.text(AccessManager.formatTimestamp(account.passUntil), NamedTextColor.AQUA))
                .append(messages.get("pass.info.until-value",
                                "now", AccessManager.formatRemainingTime(account.passUntil, now))));
                                messages.send(player, "pass.info.registered",
                "value", Messages.trusted(messages.raw(account.isRegistered()
                        ? "account.state-registered" : "account.state-awaiting")));
                        messages.send(player, "pass.info.two-factor",
                "value", Messages.trusted(messages.raw(account.isTotpEnabled()
                        ? "account.state-totp-on" : "account.state-totp-off")));
        if (accessManager.config().paymentCodeEnabled()) {
            accessManager.donations().paymentCode(player.getName()).ifPresent(code ->                    player.sendMessage(messages.get("pass.info.payment-code")
                            .append(messages.get("pass.info.payment-code-value", "code", code))));
        }
        if (account.paymentsCount > 0) {
            player.sendMessage(messages.get("pass.info.paid-label")
                    .append(messages.get("pass.info.paid-value",
                                    "total_paid_kopecks", DonationService.formatRubles(account.totalPaidKopecks),
                                    "payments_count", account.paymentsCount)));
        }        messages.send(player, "pass.info.renew",
                "url", accessManager.config().hasEffectiveDonateUrl()
                        ? accessManager.config().effectiveDonateUrl() : "/pass");
        messages.send(player, "pass.info.footer-line");
    }

    private void showAccountDetails(CommandSender sender, String targetName) {
        long now = System.currentTimeMillis();
        Optional<RegistrationStore.PlayerAccount> opt = accessManager.store().getAccount(targetName);
        if (opt.isEmpty()) {
            messages.send(sender, "account.not-found", "target_name", targetName);
            return;
        }
        RegistrationStore.PlayerAccount account = opt.get();
        boolean activePass = account.hasActivePass(now);
        Player online = Bukkit.getPlayerExact(account.playerName);
        String stageText = online == null ? "оффлайн" : accessManager.getStage(online).name();

        messages.send(sender, "account.header", "player_name", account.playerName);
        messages.send(sender, "account.pass",
                "value", Messages.trusted(messages.raw(activePass ? "pass.info.active" : "pass.info.inactive")));
        if (account.passUntil > 0) {
            sender.sendMessage(messages.get("account.until-label")
                    .append(messages.get("account.until-value",
                                    "until", AccessManager.formatTimestamp(account.passUntil),
                                    "remaining", AccessManager.formatRemainingTime(account.passUntil, now))));
        }
        if (account.passUntil > 0) {
            messages.send(sender, "account.grant-source",
                    "value", account.lastGrantSource.isBlank()
                            ? Messages.trusted(messages.raw("common.unknown")) : account.lastGrantSource);
                            messages.send(sender, "account.registered",
                "value", Messages.trusted(messages.raw(account.isRegistered() ? "common.yes" : "common.no")));
                messages.send(sender, "account.two-factor",
                "value", account.isTotpEnabled()
                        ? Messages.trusted(messages.raw("account.state-totp-on-since",
                                "since", AccessManager.formatTimestamp(account.totpEnabledAt)))
                        : Messages.trusted(messages.raw("account.state-totp-off")));
                        messages.send(sender, "account.stage", "value", stageText);
        if (account.passUntil > 0) {
            // 1.4.184: награды аддонов измеряются часами — показываем их как часы,
            // а дни оставляем покупкам, ручной выдаче и приветственному рангу.
            sender.sendMessage(messages.get("account.last-grant-label")
                    .append(messages.get(account.lastGrantHours > 0
                                    ? "account.last-grant-hours-value" : "account.last-grant-value",
                            "last_grant_days", account.lastGrantDays,
                            "last_grant_hours", account.lastGrantHours,
                            "last_grant_source", account.lastGrantSource.isBlank() ? "неизвестно" : account.lastGrantSource,
                            "last_grant_at", AccessManager.formatTimestamp(account.lastGrantAt))));
        }
        if (account.paymentsCount > 0) {
            sender.sendMessage(messages.get("account.paid-label")
                    .append(messages.get("account.paid-value",
                                    "total_paid_kopecks", DonationService.formatRubles(account.totalPaidKopecks),
                                    "payments_count", account.paymentsCount,
                                    "last_payment_at", AccessManager.formatTimestamp(account.lastPaymentAt))));
        }
        if (account.lastAuthIp != null && !account.lastAuthIp.isBlank()) {
            sender.sendMessage(messages.get("account.last-auth-label")
                    .append(messages.get("account.last-auth-value",
                                    "last_auth_ip", AuditLog.maskIp(account.lastAuthIp),
                                    "last_auth_at", AccessManager.formatTimestamp(account.lastAuthAt))));
        }
        if (account.mute != null && account.mute.until > now) {
            sender.sendMessage(messages.get("account.mute-label")
                    .append(messages.get("account.mute-value",
                                    "until", AccessManager.formatTimestamp(account.mute.until),
                                    "reason", account.mute.reason.isBlank() ? "" : " (" + account.mute.reason + ")")));
        }
        if (account.sessions != null && !account.sessions.isEmpty()) {
            sender.sendMessage(messages.get("account.sessions-label")
                    .append(messages.get("account.sessions-value",
                                    "count", account.sessions.size(),
                                    "player", account.playerName)));
        }
        if (accessManager.config().paymentCodeEnabled()) {
            accessManager.donations().paymentCode(account.playerName).ifPresent(code ->
                    sender.sendMessage(messages.get("pass.info.payment-code")
                            .append(Component.text(code, NamedTextColor.GOLD))));
        }
    }
    }

    /**
     * {@code /pass automessages [list|next|test <номер>]} — проверка автосообщений.
     *
     * <p>{@code list} показывает настроенные тексты и их условия, {@code next}
     * немедленно отправляет следующее сообщение подходящим игрокам, {@code test}
     * присылает выбранный текст только администратору — удобно проверять
     * разметку, не дожидаясь интервала.</p>
     */
    private void handleAutoMessages(CommandSender sender, String[] args, String label) {
        ChatService chat = plugin.chatService();
        if (chat == null) {
            messages.send(sender, "automessages.disabled");
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "list" -> {
                List<AutoMessage> entries = chat.autoMessageEntries();
                messages.send(sender, "automessages.list-header",
                        "count", String.valueOf(entries.size()),
                        "seconds", String.valueOf(accessManager.config().chat().autoMessagesIntervalSeconds()));
                for (int index = 0; index < entries.size(); index++) {
                    AutoMessage entry = entries.get(index);
                    String condition = entry.condition().equals("always")
                            ? messages.raw("automessages.condition-always") : entry.condition();
                    messages.send(sender, "automessages.list-line",
                            "number", String.valueOf(index + 1),
                            "condition", condition,
                            "target", targetOf(entry));
                }
                messages.send(sender, "automessages.list-footer", "label", label);
            }
            case "next", "send" -> {
                int delivered = chat.broadcastAutoMessageNow();
                if (delivered <= 0) {
                    messages.send(sender, "automessages.sent-none");
                } else {
                    messages.send(sender, "automessages.sent", "count", String.valueOf(delivered));
                }
            }
            case "test" -> {
                if (!(sender instanceof Player player)) {
                    messages.send(sender, "common.player-only");
                    return;
                }
                if (args.length < 3) {
                    messages.send(sender, "automessages.usage", "label", label);
                    return;
                }
                int index;
                try {
                    index = Integer.parseInt(args[2]);
                } catch (NumberFormatException exception) {
                    messages.send(sender, "automessages.usage", "label", label);
                    return;
                }
                if (chat.previewAutoMessage(player, index)) {
                    messages.send(sender, "automessages.test-sent", "number", String.valueOf(index));
                } else {
                    messages.send(sender, "automessages.test-unknown",
                            "number", String.valueOf(index),
                            "count", String.valueOf(chat.autoMessageCount()));
                }
            }
            case "help", "?" -> messages.send(sender, "automessages.usage", "label", label);
            default -> messages.send(sender, "automessages.usage", "label", label);
        }
    }

    /** Кому адресовано сообщение — для {@code /pass automessages list}. */
    private String targetOf(AutoMessage entry) {
        List<String> parts = new java.util.ArrayList<>(2);
        if (!entry.plugin().isBlank()) {
            parts.add(entry.plugin());
        }
        if (!entry.permission().isBlank()) {
            parts.add(entry.permission());
        }
        if (entry.minOnline() > 0) {
            parts.add("online>=" + entry.minOnline());
        }
        return parts.isEmpty() ? messages.plain("automessages.target-all") : String.join(", ", parts);
    }

    /**
     * {@code /pass bank <give|take|set|reset|info> <ник> [сумма]} — админское
     * управление счетами. Каждая операция пишется в аудит: счёт — это собственность
     * игрока, и любое его изменение должно быть объяснимо.
     */
    private void handleAdminBank(CommandSender sender, String[] args) {
        BankService bank = plugin.bank();
        if (bank == null || !bank.enabled()) {
            messages.send(sender, "economy.disabled");
            return;
        }
        if (args.length < 2) {
            messages.send(sender, "economy.admin-usage", "label", "pass");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("info") || action.equals("top")) {
            var entries = bank.store().top(20);
            messages.send(sender, "economy.admin-summary",
                    "accounts", String.valueOf(bank.store().accountCount()),
                    "total", BankService.format(bank.store().total()));
            for (BankStore.Entry entry : entries) {
                messages.send(sender, "economy.admin-summary-line",
                        "player", entry.name(), "balance", BankService.format(entry.balance()));
            }
            return;
        }
        if (args.length < 3) {
            messages.send(sender, "economy.admin-usage", "label", "pass");
            return;
        }
        String targetName = args[2].trim();
        if (!AccessManager.isValidMinecraftName(targetName)) {
            messages.send(sender, "error.invalid-nickname", "target_name", targetName);
            return;
        }
        Player online = org.bukkit.Bukkit.getPlayerExact(targetName);
        java.util.UUID targetId = online != null ? online.getUniqueId() : bank.store().idByName(targetName);
        if (targetId == null) {
            messages.send(sender, "economy.unknown-player", "player", targetName);
            return;
        }
        String display = online != null ? online.getName() : targetName;
        if (action.equals("reset") || action.equals("zero")) {
            bank.store().setBalance(targetId, display, 0L);
            auditBank(sender, targetName, "reset", 0L, 0L);
            messages.send(sender, "economy.admin-done", "player", display,
                    "balance", BankService.format(0L));
            return;
        }
        if (args.length < 4) {
            messages.send(sender, "economy.admin-usage", "label", "pass");
            return;
        }
        long amount = BankService.parseAmount(args[3]);
        if (amount <= 0L) {
            messages.send(sender, "economy.invalid-amount");
            return;
        }
        long before = bank.store().balance(targetId);
        long after;
        switch (action) {
            case "give", "add" -> after = bank.store().deposit(targetId, display, amount) < 0L
                    ? before : bank.store().balance(targetId);
            case "take", "remove" -> {
                boolean taken = bank.store().withdraw(targetId, Math.min(amount, before));
                after = taken ? bank.store().balance(targetId) : before;
            }
            case "set" -> after = bank.store().setBalance(targetId, display, amount);
            default -> {
                messages.send(sender, "economy.admin-usage", "label", "pass");
                return;
            }
        }
        auditBank(sender, targetName, action, amount, after - before);
        messages.send(sender, "economy.admin-done", "player", display,
                "balance", BankService.format(after));
    }

    private void auditBank(CommandSender sender, String target, String action, long amount, long delta) {
        try {
            accessManager.audit().log("BANK_ADMIN", sender.getName(), target, null,
                    java.util.Map.of("action", action, "amount", amount, "delta", delta));
        } catch (RuntimeException ignored) {
            // аудит — вспомогательный канал, операция уже выполнена
        }
    }

    private void showPluginStatus(CommandSender sender) {
        long now = System.currentTimeMillis();
        RegistrationStore store = accessManager.store();
        PluginConfig cfg = accessManager.config();

        messages.send(sender, "status.header");
        messages.send(sender, "status.active-passes",
                "count", store.getActivePassCount(now));
        sender.sendMessage(messages.get("status.accounts")
                .append(messages.get("status.accounts-value",
                                "registered", store.getRegisteredCount(),
                                "total", store.getTotalAccountCount())));
        sender.sendMessage(messages.get("status.two-factor")
                .append(messages.get("status.two-factor-value",
                                "enabled", store.getTotpEnabledCount(),
                                "state", Messages.trusted(messages.raw(cfg.totpEnabled() ? "common.on" : "common.off")))));
                                if (cfg.trademc().enabled()) {
                                    messages.send(sender, "status.trademc-mode",
                                            "shop", cfg.trademc().shopId());
                                } else {
                                messages.send(sender, "status.donatepay",
                "value", Messages.trusted(messages.raw(!cfg.donatePayEnabled() ? "common.provider-disabled"
                        : (cfg.donatePayReady() ? "common.connected" : "common.token-missing"))));
                                }
        sender.sendMessage(messages.get("status.donatepay-label")
                .append(messages.get("status.donatepay-value",
                                "minimum_donation_rubles", cfg.minimumDonationRubles(),
                                "grant_days", cfg.grantDays())));
        sender.sendMessage(messages.get("status.session-label")
                .append(messages.get("status.session-value", "session_minutes", cfg.sessionMinutes())));
                messages.send(sender, "status.chat",
                "state", Messages.trusted(messages.raw(cfg.chat().enabled() ? "common.enabled-m" : "common.disabled-m")),
                "radius", cfg.chat().localRadius());
                messages.send(sender, "status.auto-messages",
                "value", cfg.chat().autoMessagesEnabled()
                        ? Messages.trusted(messages.raw("status.auto-messages-value",
                                "seconds", cfg.chat().autoMessagesIntervalSeconds(),
                                "count", cfg.chat().autoMessageEntries().size()))
                        : Messages.trusted(messages.raw("common.disabled-many")));
    }

    /* ------------------------------ каталог товаров ------------------------------ */

    /** {@code /pass shop} — витрина: что можно купить и сколько это стоит. */
    private void showShop(CommandSender sender, String label) {
        ShopCatalog catalog = accessManager.config().shop();
        if (!catalog.usable()) {
            messages.send(sender, "shop.disabled");
            return;
        }
        messages.send(sender, "shop.header",
                "url", accessManager.config().effectiveDonateUrl());
        for (ShopProduct product : catalog.products()) {
            // Название и описание товара — разметка из config.yml, а не текст игрока.
            messages.send(sender, product.isBundle() ? "shop.entry-bundle" : "shop.entry",
                    "name", Messages.trusted(product.name()),
                    "price", product.priceRubles(),
                    "days", product.days(),
                    "description", Messages.trusted(product.description()));
            if (product.isBundle()) {
                messages.send(sender, "shop.entry-includes",
                        "items", Messages.trusted(describeContents(product, catalog)));
            }
        }
        ShopDiscount discount = accessManager.config().shopDiscount();
        if (plugin.shop().bonusAvailable()) {
            boolean active = sender instanceof Player player
                    && plugin.shop().discountFor(player.getName()).usable();
            messages.send(sender, active ? "shop.boosty-active" : "shop.boosty-footer",
                    "percent", discount.percent(),
                    "url", accessManager.config().boosty().linkForPlayers());
        }
        messages.send(sender, "shop.footer", "label", label);
    }

    /** {@code /pass my [ник]} — активные покупки игрока (админ может посмотреть чужого). */
    /** Состав набора для витрины: «Шлейф огня, …» плюс дни проходки, если есть. */
    private String describeContents(ShopProduct product, ShopCatalog catalog) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String id : product.includes()) {
            parts.add(catalog.find(id).map(ShopProduct::name).orElse(id));
        }
        if (product.hasPassDays()) {
            parts.add(plugin.messages().raw("shop.includes-pass", "days", product.passDays()));
        }
        return String.join(", ", parts);
    }

    private void showPurchases(CommandSender sender, String[] args, String label) {
        String target;
        if (args.length >= 2) {
            if (!sender.hasPermission("chrdkregister.admin")) {
                messages.send(sender, "error.no-permission");
                return;
            }
            target = args[1];
        } else if (sender instanceof Player player) {
            target = player.getName();
        } else {
            messages.send(sender, "usage.pass-my", "label", label);
            return;
        }
        var active = plugin.shop().active(target);
        messages.send(sender, "shop.my-header", "player", target);
        ShopDiscount discount = plugin.shop().discountFor(target);
        if (discount.usable()) {
            messages.send(sender, "shop.my-boosty", "percent", discount.percent());
        }
        if (active.isEmpty()) {
            messages.send(sender, "shop.my-empty", "label", label);
            return;
        }
        for (var purchase : active) {
            messages.send(sender, "shop.my-entry",
                    "name", Messages.trusted(purchase.product().name()),
                    "until", AccessManager.formatTimestamp(purchase.untilMillis()),
                    "left", ShopEntitlements.remainingDays(purchase.untilMillis(), System.currentTimeMillis()));
        }
        messages.send(sender, "shop.my-footer", "count", active.size());
    }

    /** {@code /pass shopbuy <ник> <товар> [дней]} — RCON-хук магазина и ручная выдача. */
    private void handleShopBuy(CommandSender sender, String label, String[] args) {
        String paymentReference = PaymentConfirmation.reference(args);
        String operation = String.join(" ", args);
        args = PaymentConfirmation.arguments(args);

        if (args.length < 3) {
            messages.send(sender, "usage.pass-shopbuy", "label", label);
            return;
        }
        String playerName = args[1].trim();
        String productId = args[2].trim();
        int days = 0;
        if (args.length >= 4) {
            try {
                days = Integer.parseInt(args[3].trim());
            } catch (NumberFormatException exception) {
                messages.send(sender, "error.amount-number");
                return;
            }
            if (days <= 0) {
                messages.send(sender, "error.amount-number");
                return;
            }
        }
        ShopProduct paidProduct = plugin.shop().product(productId).orElse(null);
        if (days > 0 && paidProduct != null && days != paidProduct.days()) {
            messages.send(sender, "paid-only.period");
            return;
        }
        PaymentConfirmation payment = PaymentConfirmation.confirm(sender, paymentReference, operation, accessManager);
        if (payment == null) return;
        ShopService.Result result = plugin.shop().grant(playerName, productId, days, payment);
        switch (result.status()) {
            case GRANTED -> {
                messages.send(sender, "shop.granted",
                        "player", result.playerName(),
                        "name", Messages.trusted(result.product().name()),
                        "days", result.days(),
                        "until", AccessManager.formatTimestamp(result.untilMillis()));
                if (result.isBundle()) {
                    messages.send(sender, "shop.granted-includes",
                            "items", String.join(", ", result.included()));
                }
                if (result.hasBonus()) {
                    messages.send(sender, result.isBundle() ? "shop.granted-bonus-bundle" : "shop.granted-bonus", "days", result.bonusDays());
                }
            }
            case UNKNOWN_PRODUCT -> messages.send(sender, "shop.unknown-product",
                    "id", productId, "list", String.join(", ", plugin.shop().catalog().ids()));
            case RANK_HIGHER, TIME_LIMIT, INVALID_BUNDLE, INVALID_RECIPIENT -> messages.send(sender, "shop.review-required",
                    "player", result.playerName(), "product", productId,
                    "reason", result.status().name(), "label", label);
            default -> messages.send(sender, "shop.disabled");
        }
    }

    /**
     * {@code /pass shopgift <ник> <товар> [даритель]} — подарить товар.
     *
     * <p>Отличается от {@code shopbuy} только журналом, аудитом и поздравлением:
     * получатель видит, что вещь подарена, а в журнале продаж остаётся имя дарителя.
     * Платёж всё равно проходит на витрине — команда лишь фиксирует, кто платил.</p>
     */
    private void handleShopGift(CommandSender sender, String label, String[] args) {
        String paymentReference = PaymentConfirmation.reference(args);
        String operation = String.join(" ", args);
        args = PaymentConfirmation.arguments(args);

        if (args.length < 3) {
            messages.send(sender, "usage.pass-shopgift", "label", label);
            return;
        }
        String playerName = args[1].trim();
        String productId = args[2].trim();
        String giver = args.length >= 4 && !args[3].isBlank()
                ? args[3].trim()
                : sender.getName();
        PaymentConfirmation payment = PaymentConfirmation.confirm(sender, paymentReference, operation, accessManager);
        if (payment == null) return;
        ShopService.Result result = plugin.shop().grant(playerName, productId, 0, payment, giver);
        switch (result.status()) {
            case GRANTED -> {
                messages.send(sender, "shop.gift-sent",
                        "player", result.playerName(),
                        "name", Messages.trusted(result.product().name()),
                        "until", AccessManager.formatTimestamp(result.untilMillis()));
                if (result.isBundle()) {
                    messages.send(sender, "shop.granted-includes",
                            "items", String.join(", ", result.included()));
                }
            }
            case UNKNOWN_PRODUCT -> messages.send(sender, "shop.unknown-product",
                    "id", productId, "list", String.join(", ", plugin.shop().catalog().ids()));
            case RANK_HIGHER, TIME_LIMIT, INVALID_BUNDLE, INVALID_RECIPIENT -> messages.send(sender, "shop.review-required",
                    "player", result.playerName(), "product", productId,
                    "reason", result.status().name(), "label", label);
            default -> messages.send(sender, "shop.disabled");
        }
    }

    /** {@code /pass shoprevoke <ник> <товар>} — снять товар и его группу. */
    private void handleShopRevoke(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            messages.send(sender, "usage.pass-shoprevoke", "label", label);
            return;
        }
        String playerName = args[1].trim();
        String productId = args[2].trim();
        ShopProduct product = plugin.shop().product(productId).orElse(null);
        if (product != null && product.hasRank() && product.isBundle()) {
            messages.send(sender, "shop.hybrid-revoke-manual");
            return;
        }
        if (!plugin.shop().revoke(playerName, productId, sender.getName())) {
            messages.send(sender, "shop.not-active", "player", playerName, "id", productId);
            return;
        }
        String name = plugin.shop().product(productId).map(ShopProduct::name).orElse(productId);
        messages.send(sender, "shop.revoked", "player", playerName, "name", name);
    }

    private void handleUpdate(CommandSender sender, String label, String[] args) {
        String action = args.length == 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (!action.equals("check") && !action.equals("download")) {
            messages.send(sender, "update.command-usage", "label", label);
            return;
        }
        boolean download = action.equals("download");
        if (download && !canDownloadUpdates(sender)) {
            messages.send(sender, "update.console-only");
            return;
        }
        PluginUpdateService updater = plugin.pluginUpdater();
        String current = plugin.getPluginMeta().getVersion();
        messages.send(sender, download ? "update.download-start" : "update.check-start");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PluginUpdateService.Result result = updater.execute(download, current);
                updateReply(() -> {
                    Messages messages = plugin.messages();
                    if (!download) {
                        messages.send(sender, "update.check-result", "current", current,
                                "latest", result.version(), "url", result.url(), "label", label);
                    } else if (result.filesCurrent()) {
                        messages.send(sender, "update.files-current", "latest", result.version());
                    } else {
                        messages.send(sender, "update.download-ready", "latest", result.version(),
                                "count", result.count(), "backup", result.backup(), "folder", result.updateDirectory());
                    }
                });
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                updateReply(() -> plugin.messages().send(sender, "update.command-error", "error", "Операция прервана."));
            } catch (Exception exception) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "[updates] Command failed", exception);
                updateReply(() -> plugin.messages().send(sender, "update.command-error", "error",
                        exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
            }
        });
    }

    // Downloading executable code is console/RCON-only, even for an in-game OP.
    static boolean canDownloadUpdates(CommandSender sender) {
        return sender instanceof org.bukkit.command.ConsoleCommandSender
                || sender instanceof org.bukkit.command.RemoteConsoleCommandSender;
    }

    private void updateReply(Runnable reply) {
        if (!plugin.isEnabled()) return;
        try { plugin.getServer().getScheduler().runTask(plugin, reply); }
        catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { /* server is stopping */ }
    }

    private void sendAdminHelp(CommandSender sender, String label) {
        // Одна многострочная запись в messages.yml — список строк, а не 37 ключей.
        messages.sendList(sender, "pass.admin-help", "label", label,
                "grant_days", accessManager.config().grantDays());
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        String cmdName = command.getName().toLowerCase(Locale.ROOT);
        if (!"pass".equals(cmdName) && !"2fa".equals(cmdName)) return List.of();
        boolean authenticated = RegistrationCommandAccess.authenticatedSender(sender, accessManager::getStage);
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
                if (authenticated && sender.hasPermission("chrdkregister.admin") && "reset".startsWith(prefix)) {
                    matches.add("reset");
                }
                return matches;
            }
            if (args.length == 2
                    && ("reset".equalsIgnoreCase(args[0]) || "reset2fa".equalsIgnoreCase(args[0]))
                    && authenticated && sender.hasPermission("chrdkregister.admin")) {
                return completePlayerNames(args[1]);
            }
            return List.of();
        }

        if (!"pass".equals(cmdName)) {
            return List.of();
        }
        if (!authenticated || !sender.hasPermission("chrdkregister.admin")) {
            if (args.length == 1) {
                String prefix = args[0].toLowerCase(Locale.ROOT);
                List<String> matches = new ArrayList<>();
                for (String sub : PLAYER_SUBCOMMANDS) {
                    if (sub.startsWith(prefix)) {
                        matches.add(sub);
                    }
                }
                return matches;
            }
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
            if ("testpay".equals(sub)) {
                return completePlayerNames(args[1]);
            }
            if ("audit".equals(sub)) {
                return completePlayerNames(args[1]);
            }
            if ("backup".equals(sub)) {
                return List.of("create", "list", "restore");
            }
            if ("update".equals(sub)) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return (canDownloadUpdates(sender) ? List.of("check", "download") : List.of("check"))
                        .stream().filter(value -> value.startsWith(prefix)).toList();
            }
            if ("shopbuy".equals(sub) || "shoprevoke".equals(sub) || "shopgift".equals(sub)
                    || "my".equals(sub)) {
                return completePlayerNames(args[1]);
            }
        }
        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String action = args[1].toLowerCase(Locale.ROOT);
            if ("shopbuy".equals(sub) || "shoprevoke".equals(sub) || "shopgift".equals(sub)) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                return plugin.shop().catalog().ids().stream()
                        .filter(id -> id.startsWith(prefix)).toList();
            }
            if ("my".equals(sub)) {
                return List.of();
            }
            if ("gift".equals(sub) || ("add".equals(sub) && args[2].isBlank())) {
                return List.of("30", "90", "365");
            }
            if ("backup".equals(sub) && "restore".equals(action)) {
                return accessManager.store().listBackups().stream()
                        .map(path -> path.getFileName().toString())
                        .toList();
            }
        }
        if (args.length == 4 && "testpay".equals(args[0].toLowerCase(Locale.ROOT))) {
            return List.of(RegistrationStore.DONATION_SOURCE_DONATEPAY, EasyDonateClient.SOURCE);
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
