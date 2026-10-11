package ru.cherdakmd.chrdkregister;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent storage for player passes, password hashes, TOTP 2FA secrets, IP sessions, and DonatePay cursor.
 */
public final class RegistrationStore {
    private static final int MAX_PROCESSED_DONATIONS = 5_000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;
    private StoreData data;

    public RegistrationStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("data.json");
        this.logger = logger;
        load();
    }

    /** Records a player's UUID and exact casing when they join the server. */
    public synchronized PlayerAccount recordPlayerSeen(UUID uuid, String playerName) {
        String key = canonicalName(playerName);
        PlayerAccount account = data.accounts.computeIfAbsent(key, ignored -> new PlayerAccount());
        account.canonicalName = key;
        account.playerName = playerName;
        if (uuid != null) {
            account.uuid = uuid.toString();
        }
        save();
        return copy(account);
    }

    public synchronized Optional<PlayerAccount> getAccount(String playerName) {
        if (playerName == null || playerName.isBlank()) return Optional.empty();
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        return account == null ? Optional.empty() : Optional.of(copy(account));
    }

    public synchronized boolean hasActivePass(String playerName, long nowMillis) {
        if (playerName == null || playerName.isBlank()) return false;
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        return account != null && account.hasActivePass(nowMillis);
    }

    public synchronized boolean isRegistered(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        return account != null && account.isRegistered();
    }

    public synchronized boolean isTotpEnabled(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        return account != null && account.isTotpEnabled();
    }

    /**
     * Grants or extends a pass manually by an administrator.
     */
    public synchronized PlayerAccount grantPassManual(String playerName, long nowMillis, long durationMillis) {
        String key = canonicalName(playerName);
        PlayerAccount account = data.accounts.computeIfAbsent(key, ignored -> new PlayerAccount());
        account.canonicalName = key;
        if (account.playerName == null || account.playerName.isBlank()) {
            account.playerName = playerName;
        }
        long baseTime = Math.max(nowMillis, account.passUntil);
        account.passUntil = baseTime + durationMillis;
        account.lastGrantSource = "manual";
        account.lastGrantAt = nowMillis;
        save();
        return copy(account);
    }

    /**
     * Grants or extends a pass from a DonatePay transaction.
     * If {@code strictCaseMatch} is enabled and the player has already been seen on the server,
     * the donor name must match the player's exact nickname case.
     */
    public synchronized Optional<PlayerAccount> grantPassFromDonation(
            String donorName,
            long transactionCreatedAtMillis,
            long nowMillis,
            long durationMillis,
            boolean strictCaseMatch
    ) {
        if (donorName == null || donorName.isBlank()) return Optional.empty();
        String key = canonicalName(donorName);
        PlayerAccount existing = data.accounts.get(key);
        if (strictCaseMatch && existing != null
                && existing.playerName != null && !existing.playerName.isBlank()
                && !existing.playerName.equals(donorName)) {
            return Optional.empty();
        }

        PlayerAccount account = data.accounts.computeIfAbsent(key, ignored -> new PlayerAccount());
        account.canonicalName = key;
        if (account.playerName == null || account.playerName.isBlank()) {
            account.playerName = donorName;
        }
        long start = Math.max(Math.max(nowMillis, transactionCreatedAtMillis), account.passUntil);
        account.passUntil = start + durationMillis;
        account.lastGrantSource = "donatepay";
        account.lastGrantAt = nowMillis;
        save();
        return Optional.of(copy(account));
    }

    /**
     * Revokes a player's active pass while keeping their registered password intact.
     */
    public synchronized boolean revokePass(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null || account.passUntil <= 0) return false;
        account.passUntil = 0;
        account.lastAuthIp = "";
        account.lastAuthAt = 0;
        save();
        return true;
    }

    /**
     * Registers a new password for a player and starts an authenticated IP session.
     */
    public synchronized boolean registerPassword(
            String playerName,
            UUID uuid,
            String rawPassword,
            String ipAddress,
            long nowMillis
    ) {
        if (playerName == null || playerName.isBlank() || rawPassword == null || rawPassword.isEmpty()) {
            return false;
        }
        String key = canonicalName(playerName);
        PlayerAccount account = data.accounts.computeIfAbsent(key, ignored -> new PlayerAccount());
        if (account.isRegistered()) {
            return false;
        }
        account.canonicalName = key;
        account.playerName = playerName;
        if (uuid != null) {
            account.uuid = uuid.toString();
        }
        account.passwordHash = PasswordHasher.hash(rawPassword);
        account.registeredAt = nowMillis;
        account.lastAuthIp = ipAddress == null ? "" : ipAddress;
        account.lastAuthAt = nowMillis;
        save();
        return true;
    }

    public synchronized boolean verifyPassword(String playerName, String rawPassword) {
        if (playerName == null || playerName.isBlank() || rawPassword == null || rawPassword.isEmpty()) {
            return false;
        }
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null || !account.isRegistered()) {
            return false;
        }
        return PasswordHasher.verify(rawPassword, account.passwordHash);
    }

    public synchronized boolean changePassword(String playerName, String oldPassword, String newPassword) {
        if (!verifyPassword(playerName, oldPassword)) {
            return false;
        }
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null) return false;
        account.passwordHash = PasswordHasher.hash(newPassword);
        save();
        return true;
    }

    public synchronized boolean resetPassword(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null || !account.isRegistered()) return false;
        account.passwordHash = "";
        account.registeredAt = 0;
        account.totpSecret = "";
        account.totpEnabledAt = 0;
        account.lastTotpStep = -1;
        account.lastAuthIp = "";
        account.lastAuthAt = 0;
        save();
        return true;
    }

    /**
     * Enables TOTP 2FA for a registered player after they confirm their first 6-digit code.
     */
    public synchronized boolean enableTotp(
            String playerName,
            String base32Secret,
            long verifiedStep,
            long nowMillis
    ) {
        if (playerName == null || playerName.isBlank() || base32Secret == null || base32Secret.isBlank()) {
            return false;
        }
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null || !account.isRegistered()) {
            return false;
        }
        account.totpSecret = base32Secret.trim();
        account.totpEnabledAt = nowMillis;
        account.lastTotpStep = verifiedStep;
        save();
        return true;
    }

    /**
     * Disables TOTP 2FA for a player.
     */
    public synchronized boolean disableTotp(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null || !account.isTotpEnabled()) return false;
        account.totpSecret = "";
        account.totpEnabledAt = 0;
        account.lastTotpStep = -1;
        save();
        return true;
    }

    /**
     * Verifies a 6-digit TOTP code against the player's active 2FA secret and records the used time step.
     */
    public synchronized boolean verifyTotp(
            String playerName,
            String code,
            long nowMillis,
            int windowSteps
    ) {
        if (playerName == null || playerName.isBlank()) return false;
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null || !account.isTotpEnabled()) return false;
        OptionalLong matchedStep = TotpService.verifyAndGetStep(
                account.totpSecret, code, nowMillis, windowSteps, account.lastTotpStep);
        if (matchedStep.isEmpty()) {
            return false;
        }
        account.lastTotpStep = matchedStep.getAsLong();
        save();
        return true;
    }

    public synchronized boolean hasValidIpSession(
            String playerName,
            String ipAddress,
            long nowMillis,
            long sessionDurationMillis
    ) {
        if (sessionDurationMillis <= 0 || playerName == null || playerName.isBlank()
                || ipAddress == null || ipAddress.isBlank()) {
            return false;
        }
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null || !account.isRegistered() || !account.hasActivePass(nowMillis)) {
            return false;
        }
        if (account.lastAuthIp == null || account.lastAuthIp.isBlank() || account.lastAuthAt <= 0) {
            return false;
        }
        if (!account.lastAuthIp.equals(ipAddress)) {
            return false;
        }
        long elapsed = nowMillis - account.lastAuthAt;
        return elapsed >= 0 && elapsed <= sessionDurationMillis;
    }

    public synchronized void touchSession(String playerName, UUID uuid, String ipAddress, long nowMillis) {
        if (playerName == null || playerName.isBlank()) return;
        String key = canonicalName(playerName);
        PlayerAccount account = data.accounts.get(key);
        if (account == null) return;
        account.playerName = playerName;
        if (uuid != null) {
            account.uuid = uuid.toString();
        }
        if (ipAddress != null && !ipAddress.isBlank()) {
            account.lastAuthIp = ipAddress;
        }
        account.lastAuthAt = nowMillis;
        save();
    }

    public synchronized void clearSession(String playerName) {
        if (playerName == null || playerName.isBlank()) return;
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null) return;
        account.lastAuthIp = "";
        account.lastAuthAt = 0;
        save();
    }

    /**
     * Adds a player to the ignore list of another player. Names are stored by canonical (lower-case) form.
     *
     * @return {@code true} when the entry was newly added
     */
    public synchronized boolean addIgnoredPlayer(String ownerName, String ignoredName) {
        if (ownerName == null || ownerName.isBlank() || ignoredName == null || ignoredName.isBlank()) return false;
        String key = canonicalName(ownerName);
        PlayerAccount account = data.accounts.computeIfAbsent(key, ignored -> new PlayerAccount());
        account.canonicalName = key;
        if (account.playerName == null || account.playerName.isBlank()) {
            account.playerName = ownerName;
        }
        if (account.ignoredPlayers == null) {
            account.ignoredPlayers = new LinkedHashSet<>();
        }
        if (!account.ignoredPlayers.add(canonicalName(ignoredName))) {
            return false;
        }
        save();
        return true;
    }

    /**
     * Removes a player from the ignore list of another player.
     *
     * @return {@code true} when the entry was present and got removed
     */
    public synchronized boolean removeIgnoredPlayer(String ownerName, String ignoredName) {
        if (ownerName == null || ownerName.isBlank() || ignoredName == null || ignoredName.isBlank()) return false;
        PlayerAccount account = data.accounts.get(canonicalName(ownerName));
        if (account == null || account.ignoredPlayers == null) return false;
        if (!account.ignoredPlayers.remove(canonicalName(ignoredName))) {
            return false;
        }
        save();
        return true;
    }

    public synchronized boolean isIgnoring(String ownerName, String ignoredName) {
        if (ownerName == null || ownerName.isBlank() || ignoredName == null || ignoredName.isBlank()) return false;
        PlayerAccount account = data.accounts.get(canonicalName(ownerName));
        return account != null
                && account.ignoredPlayers != null
                && account.ignoredPlayers.contains(canonicalName(ignoredName));
    }

    /** Returns the canonical names ignored by the given player. */
    public synchronized Set<String> getIgnoredPlayers(String ownerName) {
        if (ownerName == null || ownerName.isBlank()) return Set.of();
        PlayerAccount account = data.accounts.get(canonicalName(ownerName));
        if (account == null || account.ignoredPlayers == null || account.ignoredPlayers.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(account.ignoredPlayers);
    }

    /** Returns the ignore list with the exact nickname casing of known players when available. */
    public synchronized Set<String> getIgnoredDisplayNames(String ownerName) {
        Set<String> canonicalNames = getIgnoredPlayers(ownerName);
        if (canonicalNames.isEmpty()) return Set.of();
        Set<String> display = new LinkedHashSet<>();
        for (String canonical : canonicalNames) {
            PlayerAccount account = data.accounts.get(canonical);
            display.add(account != null && account.playerName != null && !account.playerName.isBlank()
                    ? account.playerName
                    : canonical);
        }
        return display;
    }

    public synchronized int getActivePassCount(long nowMillis) {
        int count = 0;
        for (PlayerAccount account : data.accounts.values()) {
            if (account.hasActivePass(nowMillis)) count++;
        }
        return count;
    }

    public synchronized int getRegisteredCount() {
        int count = 0;
        for (PlayerAccount account : data.accounts.values()) {
            if (account.isRegistered()) count++;
        }
        return count;
    }

    public synchronized int getTotpEnabledCount() {
        int count = 0;
        for (PlayerAccount account : data.accounts.values()) {
            if (account.isTotpEnabled()) count++;
        }
        return count;
    }

    public synchronized int getTotalAccountCount() {
        return data.accounts.size();
    }

    public synchronized List<String> getAllAccountNames() {
        List<String> names = new ArrayList<>(data.accounts.size());
        for (PlayerAccount account : data.accounts.values()) {
            if (account.playerName != null && !account.playerName.isBlank()) {
                names.add(account.playerName);
            }
        }
        return names;
    }

    public synchronized boolean isDonationCursorInitialized() {
        return data.donationCursorInitialized;
    }

    public synchronized long getDonationCursor() {
        return data.donationCursor;
    }

    public synchronized void initializeDonationCursor(long cursor) {
        if (data.donationCursorInitialized) return;
        data.donationCursor = Math.max(0, cursor);
        data.donationCursorInitialized = true;
        save();
    }

    public synchronized boolean isDonationProcessed(long id) {
        return data.processedDonationIds.contains(Long.toString(id));
    }

    public synchronized void markDonationProcessed(long id) {
        String key = Long.toString(id);
        data.processedDonationIds.remove(key);
        data.processedDonationIds.add(key);
        data.donationCursor = Math.max(data.donationCursor, id);
        while (data.processedDonationIds.size() > MAX_PROCESSED_DONATIONS) {
            String first = data.processedDonationIds.iterator().next();
            data.processedDonationIds.remove(first);
        }
        save();
    }

    private void load() {
        try {
            Files.createDirectories(file.getParent());
            if (Files.notExists(file)) {
                data = new StoreData();
                save();
                return;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                data = GSON.fromJson(reader, StoreData.class);
                if (data == null) data = new StoreData();
            }
            if (data.accounts == null) data.accounts = new LinkedHashMap<>();
            if (data.processedDonationIds == null) data.processedDonationIds = new LinkedHashSet<>();
            for (PlayerAccount account : data.accounts.values()) {
                if (account.ignoredPlayers == null) {
                    account.ignoredPlayers = new LinkedHashSet<>();
                }
            }
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "Could not load registration data from " + file.toAbsolutePath(), exception);
            try {
                if (Files.exists(file)) {
                    Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + Instant.now().toEpochMilli());
                    Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                    logger.severe("Unreadable data file backed up to " + backup.toAbsolutePath());
                }
            } catch (Exception backupException) {
                logger.log(Level.SEVERE, "Could not back up unreadable registration data", backupException);
            }
            data = new StoreData();
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception unsupportedAtomicMove) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem
            }
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "Could not save registration data to " + file.toAbsolutePath(), exception);
        }
    }

    public static String canonicalName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static PlayerAccount copy(PlayerAccount source) {
        PlayerAccount result = new PlayerAccount();
        result.uuid = source.uuid;
        result.playerName = source.playerName;
        result.canonicalName = source.canonicalName;
        result.passUntil = source.passUntil;
        result.lastGrantSource = source.lastGrantSource;
        result.lastGrantAt = source.lastGrantAt;
        result.passwordHash = source.passwordHash;
        result.registeredAt = source.registeredAt;
        result.totpSecret = source.totpSecret == null ? "" : source.totpSecret;
        result.totpEnabledAt = source.totpEnabledAt;
        result.lastTotpStep = source.lastTotpStep;
        result.lastAuthIp = source.lastAuthIp;
        result.lastAuthAt = source.lastAuthAt;
        result.ignoredPlayers = source.ignoredPlayers == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(source.ignoredPlayers);
        return result;
    }

    public static final class PlayerAccount {
        public String uuid = "";
        public String playerName = "";
        public String canonicalName = "";
        public long passUntil;
        public String lastGrantSource = "";
        public long lastGrantAt;
        public String passwordHash = "";
        public long registeredAt;
        public String totpSecret = "";
        public long totpEnabledAt;
        public long lastTotpStep = -1;
        public String lastAuthIp = "";
        public long lastAuthAt;
        public Set<String> ignoredPlayers = new LinkedHashSet<>();

        public boolean hasActivePass(long nowMillis) {
            return passUntil > nowMillis;
        }

        public boolean isRegistered() {
            return passwordHash != null && !passwordHash.isBlank();
        }

        public boolean isTotpEnabled() {
            return totpSecret != null && !totpSecret.isBlank();
        }
    }

    private static final class StoreData {
        Map<String, PlayerAccount> accounts = new LinkedHashMap<>();
        Set<String> processedDonationIds = new LinkedHashSet<>();
        long donationCursor;
        boolean donationCursorInitialized;
    }
}
