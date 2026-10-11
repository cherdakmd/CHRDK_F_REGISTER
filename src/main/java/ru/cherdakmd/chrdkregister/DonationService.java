package ru.cherdakmd.chrdkregister;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Turns DonatePay transactions into pass grants and keeps a journal of every payment.
 *
 * <p>Nothing is dropped silently: a payment that cannot be matched to a nickname automatically is stored in
 * the journal with a {@code PENDING_*} status, shown by {@code /pass pending}, and resolved by an administrator
 * ({@code /pass approve}, {@code /pass reject}) or by the paying player ({@code /pass claim}).</p>
 */
public final class DonationService {
    /** Callback used to notify a player whose pass was granted or extended. */
    public interface PassNotifier {
        void onPassGranted(String playerName, long passUntil, String sourceDescription);
    }

    /** Result of processing one transaction. */
    public record Outcome(
            RegistrationStore.DonationStatus status,
            String playerName,
            long passUntil,
            int days,
            String note,
            String suggestion
    ) {
        public boolean granted() {
            return status == RegistrationStore.DonationStatus.GRANTED;
        }
    }

    private final RegistrationStore store;
    private final java.util.function.Supplier<PluginConfig> configSupplier;
    private final AuditLog audit;
    private final NotificationService notifications;
    private final Logger logger;
    private final PassNotifier notifier;

    public DonationService(
            RegistrationStore store,
            java.util.function.Supplier<PluginConfig> configSupplier,
            AuditLog audit,
            NotificationService notifications,
            Logger logger,
            PassNotifier notifier
    ) {
        this.store = store;
        this.configSupplier = configSupplier;
        this.audit = audit;
        this.notifications = notifications;
        this.logger = logger;
        this.notifier = notifier;
    }

    /**
     * Processes one DonatePay transaction: grants the pass when the donor name is unambiguous, otherwise
     * journals the payment with a pending status. The transaction is always marked as processed.
     */
    public Outcome process(DonatePayClient.Transaction transaction, long nowMillis, boolean pollMode) {
        PluginConfig config = configSupplier.get();
        int amountRubles = toRubles(transaction.amount());
        int days = config.daysForAmount(amountRubles);
        List<String> suggestions = suggest(transaction.donorName(), config);

        RegistrationStore.DonationStatus status;
        String note = "";
        String grantedTo = "";
        long passUntil = 0;

        if (!transaction.isSuccessfulDonation()) {
            status = RegistrationStore.DonationStatus.REJECTED;
            note = "транзакция не является успешным донатом";
        } else if (amountRubles < config.minimumDonationRubles()) {
            status = RegistrationStore.DonationStatus.PENDING_AMOUNT;
            note = "сумма ниже минимума (" + config.minimumDonationRubles() + " ₽)";
        } else if (!AccessManager.isValidMinecraftName(transaction.donorName().trim())) {
            status = RegistrationStore.DonationStatus.PENDING_NAME;
            note = "имя донатера не похоже на игровой ник";
        } else {
            Optional<RegistrationStore.PlayerAccount> granted = store.grantPassFromDonation(
                    transaction.donorName().trim(),
                    transaction.createdAt(),
                    nowMillis,
                    days * 86_400_000L,
                    config.strictCaseMatch());
            if (granted.isPresent()) {
                status = RegistrationStore.DonationStatus.GRANTED;
                grantedTo = granted.get().playerName;
                passUntil = granted.get().passUntil;
                store.addPayment(grantedTo, transaction.amount()
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValueExact(), nowMillis);
            } else {
                status = RegistrationStore.DonationStatus.PENDING_REVIEW;
                note = "регистр ника не совпадает (strict-case-match)";
            }
        }

        RegistrationStore.DonationRecord journal = new RegistrationStore.DonationRecord();
        journal.id = transaction.id();
        journal.donorName = transaction.donorName();
        journal.amountKopecks = transaction.amount()
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        journal.amountRaw = transaction.amount().toPlainString();
        journal.createdAt = transaction.createdAt();
        journal.detectedAt = nowMillis;
        journal.status = status.name();
        journal.note = note;
        journal.grantedTo = grantedTo;
        journal.days = status == RegistrationStore.DonationStatus.GRANTED ? days : 0;
        journal.suggestedName = suggestions.isEmpty() ? "" : suggestions.get(0);
        store.recordDonation(journal);
        store.markDonationProcessed(transaction.id());
        if (status == RegistrationStore.DonationStatus.GRANTED) {
            store.updateDonation(transaction.id(), status.name(), note, "", 0, grantedTo, days);
        }

        String donor = transaction.donorName();
        String amount = formatRubles(journal.amountKopecks);
        switch (status) {
            case GRANTED -> {
                logger.info("DonatePay pass granted to " + grantedTo + " (" + amount + " RUB, #"
                        + transaction.id() + ", " + days + " дн.).");
                audit.log(AuditLog.PASS_GRANT_DONATEPAY, "donatepay", grantedTo, "",
                        AuditLog.details("donation", transaction.id(), "amount", amount, "days", days));
                notifications.notify("pass.granted", "Проходка выдана автоматически",
                        "Игрок: " + grantedTo + "\nСумма: " + amount + " ₽\nСрок: " + days + " дн.",
                        "good");
                notifyOnline(grantedTo, passUntil, "DonatePay " + amount + "₽");
            }
            case PENDING_AMOUNT, PENDING_NAME, PENDING_REVIEW -> {
                logger.warning("DonatePay donation #" + transaction.id() + " from '" + donor
                        + "' (" + amount + " RUB) needs review: " + note
                        + (suggestions.isEmpty() ? "" : " — похоже на " + String.join(", ", suggestions)));
                audit.log(AuditLog.DONATION_PENDING, "donatepay", donor, "",
                        AuditLog.details("donation", transaction.id(), "amount", amount, "reason", note,
                                "suggestions", String.join(", ", suggestions)));
                notifications.notify("donate.pending", "Донат требует решения",
                        "ID: " + transaction.id() + "\nИмя: " + donor + "\nСумма: " + amount + " ₽\nПричина: " + note
                                + (suggestions.isEmpty() ? "" : "\nПохоже на: " + String.join(", ", suggestions))
                                + "\nРазбор: /pass pending",
                        "warn");
            }
            default -> {
                if (pollMode) {
                    logger.fine("DonatePay transaction #" + transaction.id() + " skipped: " + note);
                }
            }
        }
        return new Outcome(status, grantedTo, passUntil, days, note, journal.suggestedName);
    }

    /** Returns pending (unmatched) journal entries, newest first. */
    public List<RegistrationStore.DonationRecord> pending(int limit) {
        List<RegistrationStore.DonationRecord> result = new ArrayList<>();
        for (RegistrationStore.DonationRecord record : store.listDonations(null, 0)) {
            if (record.donationStatus().isPending()) {
                result.add(record);
                if (limit > 0 && result.size() >= limit) break;
            }
        }
        return result;
    }

    public int pendingCount() {
        return store.countDonations(RegistrationStore.DonationStatus.PENDING_NAME)
                + store.countDonations(RegistrationStore.DonationStatus.PENDING_AMOUNT)
                + store.countDonations(RegistrationStore.DonationStatus.PENDING_REVIEW);
    }

    /**
     * Manually approves a pending payment and grants the pass to the given player.
     *
     * @param days pass length in days; {@code <= 0} uses the tier matching the paid amount
     */
    public boolean approve(long donationId, String playerName, int days, String adminName, long nowMillis) {
        PluginConfig config = configSupplier.get();
        Optional<RegistrationStore.DonationRecord> existing = store.getDonation(donationId);
        if (existing.isEmpty()) return false;
        RegistrationStore.DonationRecord record = existing.get();
        int grantDays = days > 0 ? days : config.daysForAmount((int) (record.amountKopecks / 100L));
        long passUntil = store.grantPassManual(playerName, nowMillis, grantDays * 86_400_000L,
                "donatepay:#" + donationId).passUntil;
        store.addPayment(playerName, record.amountKopecks, nowMillis);
        store.updateDonation(donationId, RegistrationStore.DonationStatus.GRANTED.name(),
                record.note + (record.note.isBlank() ? "" : "; ") + "подтверждено администратором",
                adminName, nowMillis, playerName, grantDays);
        audit.log(AuditLog.DONATION_APPROVED, adminName, playerName, "",
                AuditLog.details("donation", donationId, "amount", formatRubles(record.amountKopecks),
                        "days", grantDays, "donorName", record.donorName));
        notifications.notify("pass.granted", "Донат подтверждён администратором",
                "ID: " + donationId + "\nДонатер: " + record.donorName + "\nВыдано: " + playerName
                        + "\nДней: " + grantDays + "\nСумма: " + formatRubles(record.amountKopecks) + " ₽",
                "good");
        notifyOnline(playerName, passUntil, "DonatePay #" + donationId + " (подтверждено админом)");
        return true;
    }

    /** Rejects a pending payment; the donation stays in the journal with the decision recorded. */
    public boolean reject(long donationId, String adminName, String reason, long nowMillis) {
        Optional<RegistrationStore.DonationRecord> existing = store.getDonation(donationId);
        if (existing.isEmpty()) return false;
        RegistrationStore.DonationRecord record = existing.get();
        store.updateDonation(donationId, RegistrationStore.DonationStatus.REJECTED.name(),
                (reason == null || reason.isBlank()) ? "отклонено администратором" : reason,
                adminName, nowMillis, "", 0);
        audit.log(AuditLog.DONATION_REJECTED, adminName, record.donorName, "",
                AuditLog.details("donation", donationId, "amount", formatRubles(record.amountKopecks),
                        "reason", reason));
        return true;
    }

    /**
     * Lets a player confirm that a pending payment belongs to them. Only allowed when the donor name
     * plausibly matches their nickname, so nobody can claim someone else's donation.
     */
    public String claim(long donationId, String playerName, String ipAddress, long nowMillis) {
        Optional<RegistrationStore.DonationRecord> existing = store.getDonation(donationId);
        if (existing.isEmpty()) {
            return "Платёж с ID " + donationId + " не найден в журнале.";
        }
        RegistrationStore.DonationRecord record = existing.get();
        if (!record.donationStatus().isPending()) {
            return "Этот платёж уже обработан (статус: " + record.status + ").";
        }
        if (!DonorNameMatcher.looksLikeDonor(record.donorName, playerName)) {
            return "Имя донатера в платеже («" + record.donorName
                    + "») не совпадает с вашим ником — обратитесь к администрации.";
        }
        approve(donationId, playerName, 0, playerName + " (самостоятельно)", nowMillis);
        audit.log(AuditLog.DONATION_CLAIMED, playerName, playerName, ipAddress,
                AuditLog.details("donation", donationId, "donorName", record.donorName));
        return "";
    }

    /** Player-facing payment hint: either "Nick 4821" or just the nickname. */
    public String paymentHint(String playerName) {
        PluginConfig config = configSupplier.get();
        if (!config.paymentCodeEnabled()) {
            return playerName;
        }
        String code = store.getOrCreatePaymentCode(playerName, () -> generateCode(config.paymentCodeDigits()));
        return code.isBlank() ? playerName : playerName + " " + code;
    }

    /** Public payment code of a player, if one has been generated. */
    public Optional<String> paymentCode(String playerName) {
        return store.getPaymentCode(playerName);
    }

    /** Human-readable suggestions of real players the donor name could mean. */
    public List<String> suggest(String donorName, PluginConfig config) {
        if (!config.donateFuzzyMatch() || donorName == null || donorName.isBlank()) return List.of();
        return store.suggestPlayersForDonor(donorName.trim(), 3);
    }

    private void notifyOnline(String playerName, long passUntil, String source) {
        if (notifier != null) {
            notifier.onPassGranted(playerName, passUntil, source);
        }
    }

    private static String generateCode(int digits) {
        int bound = (int) Math.pow(10, Math.clamp(digits, 3, 8));
        int value = java.util.concurrent.ThreadLocalRandom.current().nextInt(bound / 10, bound);
        return Integer.toString(value);
    }

    public static int toRubles(BigDecimal amount) {
        if (amount == null) return 0;
        return amount.setScale(0, RoundingMode.DOWN).intValue();
    }

    public static String formatRubles(long kopecks) {
        long rubles = kopecks / 100;
        long rest = Math.abs(kopecks % 100);
        return rest == 0 ? Long.toString(rubles)
                : rubles + "." + (rest < 10 ? "0" + rest : Long.toString(rest));
    }

    /** Formats the tier table shown to players. */
    public static List<String> describeTiers(PluginConfig config) {
        List<String> lines = new ArrayList<>();
        for (PluginConfig.PassTier tier : config.passTiers()) {
            String label = tier.label() == null || tier.label().isBlank() ? "" : " (" + tier.label() + ")";
            lines.add("&e" + tier.amountRubles() + " ₽ &7→ &b" + tier.days() + " дн." + label);
        }
        return lines;
    }

    /** Logs a mismatch that a strict-case configuration produced (helper for audit readability). */
    public static String describeDonor(String donorName) {
        return donorName == null ? "" : donorName.trim().toLowerCase(Locale.ROOT);
    }

    /** Returns the journal details of a payment as a string map for admin output. */
    public static Map<String, String> describeRecord(RegistrationStore.DonationRecord record) {
        return Map.of(
                "id", Long.toString(record.id),
                "donor", record.donorName,
                "amount", formatRubles(record.amountKopecks),
                "status", record.status,
                "suggested", record.suggestedName == null ? "" : record.suggestedName);
    }
}
