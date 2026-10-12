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
                    "backup", "gift", "testpay", "recheck");
    private static final List<String> MUTE_DURATIONS = List.of("10m", "30m", "1h", "6h", "1d", "7d");
    private static final List<String> TWO_FACTOR_SUBCOMMANDS =
            List.of("setup", "confirm", "disable", "status", "cancel", "qr");
    private static final List<String> PLAYER_SUBCOMMANDS = List.of("menu", "gui", "claim");

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
        return switch (cmdName) {
            case "register" -> handleRegister(sender, args);
            case "login" -> handleLogin(sender, args);
            case "captcha" -> handleCaptcha(sender, args);
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
                if (sender.hasPermission("chrdkregister.admin")) {
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
        if (!sender.hasPermission("chrdkregister.admin")) {
            if (sender instanceof Player player) {
                showOwnPassStatus(player);
            } else {
                messages.send(sender, "error.no-permission");
            }
            return true;
        }

        switch (sub) {
            case "add", "grant" -> {
                if (args.length < 2) {
                    messages.send(sender, "usage.pass-add", "label", label);
                    return true;
                }
                String targetName = args[1].trim();
                if (!AccessManager.isValidMinecraftName(targetName)) {
                    messages.send(sender, "error.invalid-nickname", "target_name", targetName);
                    return true;
                }
                int days = accessManager.config().grantDays();
                if (args.length >= 3) {
                    try {
                        days = Math.clamp(Integer.parseInt(args[2]), 1, 3650);
                    } catch (NumberFormatException exception) {
                        messages.send(sender, "error.days-number");
                        return true;
                    }
                }
                RegistrationStore.PlayerAccount account = accessManager.grantPassManually(targetName, days, "manual");
                messages.send(sender, "pass.granted",
                        "player", account.playerName,
                        "days", days,
                        "until", AccessManager.formatTimestamp(account.passUntil));
                return true;
            }
            case "gift" -> {
                if (args.length < 3) {
                    messages.send(sender, "usage.pass-gift", "label", label);
                    return true;
                }
                String targetName = args[1].trim();
                int days;
                try {
                    days = Math.clamp(Integer.parseInt(args[2]), 1, 3650);
                } catch (NumberFormatException exception) {
                    messages.send(sender, "error.days-number");
                    return true;
                }
                if (!AccessManager.isValidMinecraftName(targetName)) {
                    messages.send(sender, "error.invalid-nickname", "target_name", targetName);
                    return true;
                }
                RegistrationStore.PlayerAccount account = accessManager.grantPassManually(targetName, days,
                        "gift:" + sender.getName());
                accessManager.audit().log(AuditLog.PASS_GRANT_GIFT, sender.getName(), targetName, null,
                        AuditLog.details("days", days));
                        messages.send(sender, "pass.gifted",
                        "player", account.playerName, "days", days);
                Player online = Bukkit.getPlayerExact(targetName);
                if (online != null) {
                    messages.send(online, "pass.gift-notice", "days", days, "sender", sender.getName());
                }
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
                                    ? "" : messages.raw("donation.suggested", "name", record.suggestedName),
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
                    String detail;
                    if (status == RegistrationStore.DonationStatus.GRANTED) {
                        detail = " → " + record.grantedTo + " (+" + record.days + " дн.)"
                                + (record.decidedBy == null || record.decidedBy.isBlank()
                                        ? "" : " (" + record.decidedBy + ")");
                    } else if (status == RegistrationStore.DonationStatus.REJECTED) {
                        detail = record.decidedBy == null || record.decidedBy.isBlank()
                                ? "" : " (" + record.decidedBy + ")";
                    } else {
                        detail = (record.note.isBlank() ? "" : " (" + record.note + ")")
                                + (record.suggestedName.isBlank() ? ""
                                        : messages.raw("donation.suggested", "name", record.suggestedName));
                    }
                    messages.send(sender, "donation.journal-entry",
                            "id", record.id,
                            "donor", record.donorName,
                            "amount", record.amountRubles(),
                            "status", donationStatusLabel(status),
                            "detail", detail,
                            "created", AccessManager.formatTimestamp(record.createdAt));
                }
                messages.send(sender, "donation.journal-hint", "label", label);
                return true;
            }
            case "health" -> {
                AccessManager.DonateHealthCard card = accessManager.donateHealthCard();
                messages.send(sender, "donation.health-header");
                if (!accessManager.config().donatePayReady()) {
                    check(sender, "DonatePay API", false,
                            "не задан donatepay.api-token — автопроверка донатов выключена");
                    return true;
                }
                String state = switch (card.state()) {
                    case OK -> messages.raw("donation.health-ok");
                    case FLAKY -> messages.raw("donation.health-flaky",
                            "fails", card.failStreak());
                    case NO_CONTACT -> messages.raw("donation.health-down",
                            "fails", card.failStreak());
                };
                messages.send(sender, "donation.health-state",
                        "state", state,
                        "poll_seconds", card.pollSeconds(),
                        "cursor", card.cursorId());
                if (card.lastSuccessAtMillis() > 0) {
                    messages.send(sender, "donation.health-last",
                            "ago", AccessManager.formatDuration(
                                    System.currentTimeMillis() - card.lastSuccessAtMillis()),
                            "error", card.lastError() == null ? "—" : card.lastError());
                }
                messages.send(sender, "donation.health-stats",
                        "attempts", card.attempts(), "ok", card.successes(),
                        "fail", card.failures(), "processed", card.processedTransactions(),
                        "pending", accessManager.donations().pendingCount());
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
            case "testpay" -> {
                if (args.length < 2) {
                    messages.send(sender, "usage.pass-testpay", "label", label);
                    return true;
                }
                String targetName = args[1].trim();
                int amount = accessManager.config().minimumDonationRubles();
                if (args.length >= 3) {
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
                }
                DonationService.Outcome outcome = accessManager.simulateDonation(targetName, amount);
                switch (outcome.status()) {
                    case GRANTED -> messages.send(sender, "donation.testpay-granted",
                            "amount", amount,
                            "name", outcome.playerName(),
                            "days", outcome.days(),
                            "until", AccessManager.formatTimestamp(outcome.passUntil()));
                    case REJECTED -> messages.send(sender, "donation.testpay-rejected",
                            "amount", amount,
                            "note", outcome.note().isBlank() ? "—" : outcome.note());
                    default -> messages.send(sender, "donation.testpay-pending",
                            "amount", amount,
                            "donor", targetName,
                            "status", outcome.status().name(),
                            "note", outcome.note().isBlank() ? "—" : outcome.note(),
                            "label", label);
                }
                return true;
            }
            case "recheck" -> {
                if (!accessManager.config().donatePayReady()) {
                    messages.send(sender, "donation.recheck-unavailable");
                    return true;
                }
                messages.send(sender, "donation.recheck-started", "label", label);
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
        messages.send(sender, "stats.tiers", "tiers", DonationService.describeTiers(cfg, messages));
    }

    private void showDoctor(CommandSender sender) {
        PluginConfig cfg = accessManager.config();
        messages.send(sender, "doctor.header");
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
        check(sender, "Vault (экономика и права)", plugin.externalHooks().economy().isPresent()
                        || plugin.externalHooks().permissions().isPresent(),
                "Vault не установлен — экономика для аддонов недоступна (опционально)");
        check(sender, "LuckPerms", plugin.externalHooks().luckPerms().isPresent(),
                cfg.passGroup().isBlank()
                        ? "LuckPerms не установлен (опционально — нужен для integrations.pass-group)"
                        : "задан integrations.pass-group, но LuckPerms не найден — группы не синхронизируются!");
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
                "value", messages.raw(account.isRegistered()
                        ? "account.state-registered" : "account.state-awaiting"));
                        messages.send(player, "pass.info.two-factor",
                "value", messages.raw(account.isTotpEnabled()
                        ? "account.state-totp-on" : "account.state-totp-off"));
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
                "url", accessManager.config().hasPaymentUrl()
                        ? accessManager.config().donatePayPaymentUrl() : "/pass");
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
                "value", messages.raw(activePass ? "pass.info.active" : "pass.info.inactive"));
        if (account.passUntil > 0) {
            sender.sendMessage(messages.get("account.until-label")
                    .append(messages.get("account.until-value",
                                    "until", AccessManager.formatTimestamp(account.passUntil),
                                    "remaining", AccessManager.formatRemainingTime(account.passUntil, now))));
        }
        if (account.passUntil > 0) {
            messages.send(sender, "account.grant-source",
                    "value", account.lastGrantSource.isBlank()
                            ? messages.raw("common.unknown") : account.lastGrantSource);
                            messages.send(sender, "account.registered",
                "value", messages.raw(account.isRegistered() ? "common.yes" : "common.no"));
                messages.send(sender, "account.two-factor",
                "value", account.isTotpEnabled()
                        ? messages.raw("account.state-totp-on-since",
                                "since", AccessManager.formatTimestamp(account.totpEnabledAt))
                        : messages.raw("account.state-totp-off"));
                        messages.send(sender, "account.stage", "value", stageText);
        if (account.passUntil > 0) {
            sender.sendMessage(messages.get("account.last-grant-label")
                    .append(messages.get("account.last-grant-value",
                                    "last_grant_days", account.lastGrantDays,
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
                                "state", messages.raw(cfg.totpEnabled() ? "common.on" : "common.off"))));
                                messages.send(sender, "status.donatepay",
                "value", messages.raw(cfg.donatePayReady() ? "common.connected" : "common.token-missing"));
        sender.sendMessage(messages.get("status.donatepay-label")
                .append(messages.get("status.donatepay-value",
                                "minimum_donation_rubles", cfg.minimumDonationRubles(),
                                "grant_days", cfg.grantDays())));
        sender.sendMessage(messages.get("status.session-label")
                .append(messages.get("status.session-value", "session_minutes", cfg.sessionMinutes())));
                messages.send(sender, "status.chat",
                "state", messages.raw(cfg.chat().enabled() ? "common.enabled-m" : "common.disabled-m"),
                "radius", cfg.chat().localRadius());
                messages.send(sender, "status.auto-messages",
                "value", cfg.chat().autoMessagesEnabled()
                        ? messages.raw("status.auto-messages-value",
                                "seconds", cfg.chat().autoMessagesIntervalSeconds(),
                                "count", cfg.chat().autoMessages().size())
                        : messages.raw("common.disabled-many"));
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
