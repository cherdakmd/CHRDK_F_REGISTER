package ru.cherdakmd.chrdkregister;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Persistent storage for player passes, password hashes, TOTP 2FA secrets, IP sessions, auth attempt
 * counters, the DonatePay transaction journal, chat mutes, and the DonatePay cursor.
 *
 * <p>The file {@code data.json} carries a schema version; older files are migrated on load. Writes are
 * debounced and can be offloaded to an async executor; when {@code asyncExecutor} is {@code null} every
 * mutation is written synchronously (used by unit tests).</p>
 */
public final class RegistrationStore {
    /** Current on-disk schema version. */
    public static final int SCHEMA_VERSION = 4;
    private static final int MAX_PROCESSED_DONATIONS = 5_000;
    private static final int MAX_JOURNAL_ENTRIES = 20_000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter BACKUP_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.of("Europe/Moscow"));

    /** Counter map selector for auth throttling. */
    public enum AttemptKind {
        ACCOUNT,
        IP
    }

    /** Lifecycle states of a DonatePay transaction inside the journal. */
    public enum DonationStatus {
        /** Pass granted automatically or after a manual decision. */
        GRANTED,
        /** Donor name does not look like a known/valid Minecraft nickname — needs a human decision. */
        PENDING_NAME,
        /** Donation amount is below the configured minimum. */
        PENDING_AMOUNT,
        /** Everything else that needs review (strict case mismatch, stale transaction, fuzzy match). */
        PENDING_REVIEW,
        /** Explicitly rejected by an administrator. */
        REJECTED;

        public static DonationStatus parse(String raw) {
            if (raw == null || raw.isBlank()) return PENDING_REVIEW;
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return PENDING_REVIEW;
            }
        }

        public boolean isPending() {
            return this == PENDING_NAME || this == PENDING_AMOUNT || this == PENDING_REVIEW;
        }
    }

    private final Path file;
    private final Path backupDirectory;
    private final Logger logger;
    private final Executor asyncExecutor;

    private final Object writeLock = new Object();
    private StoreData data;
    private boolean dirty;
    private final AtomicLong snapshotSequence = new AtomicLong();
    private final AtomicLong writtenSequence = new AtomicLong();

    public RegistrationStore(Path dataFolder, Logger logger) {
        this(dataFolder, logger, null);
    }

    public RegistrationStore(Path dataFolder, Logger logger, Executor asyncExecutor) {
        this.file = dataFolder.resolve("data.json");
        this.backupDirectory = dataFolder.resolve("backups");
        this.logger = logger;
        this.asyncExecutor = asyncExecutor;
        load();
    }

    /* ------------------------------------------------------------------ *
     *  Persistence: debounced snapshots, integrity sidecar, backups
     * ------------------------------------------------------------------ */

    /** Returns {@code true} when there are changes that are not on disk yet. */
    public synchronized boolean hasPendingChanges() {
        return dirty;
    }

    /** Writes pending changes; with an async executor the write happens off-thread. */
    public void flush() {
        String json;
        long sequence;
        synchronized (this) {
            if (!dirty) return;
            dirty = false;
            json = GSON.toJson(data);
            sequence = snapshotSequence.incrementAndGet();
        }
        if (asyncExecutor == null) {
            writeSnapshot(json, sequence);
        } else {
            asyncExecutor.execute(() -> writeSnapshot(json, sequence));
        }
    }

    /** Writes pending changes in the calling thread — used on plugin disable. */
    public void flushBlocking() {
        String json;
        long sequence;
        synchronized (this) {
            dirty = false;
            json = GSON.toJson(data);
            sequence = snapshotSequence.incrementAndGet();
        }
        writeSnapshot(json, sequence);
    }

    /**
     * Creates a gzipped backup of the current data file.
     *
     * @param reason short tag included in the file name (start, reload, manual, ...)
     * @param keepCount how many newest backups to keep; {@code <= 0} keeps everything
     * @return the created backup file, or empty when the data file does not exist yet
     */
    public Optional<Path> createBackup(String reason, int keepCount) {
        flushBlocking();
        synchronized (writeLock) {
            if (Files.notExists(file)) return Optional.empty();
            try {
                Files.createDirectories(backupDirectory);
                String safeReason = (reason == null || reason.isBlank())
                        ? "manual"
                        : reason.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
                Path target = backupDirectory.resolve(
                        "data-" + BACKUP_STAMP.format(Instant.now()) + "-" + safeReason + ".json.gz");
                try (InputStream in = Files.newInputStream(file);
                     GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(target))) {
                    in.transferTo(out);
                }
                pruneBackups(keepCount);
                logger.info("Backup created: " + target.toAbsolutePath());
                return Optional.of(target);
            } catch (Exception exception) {
                logger.log(Level.SEVERE, "Could not create a backup of " + file.toAbsolutePath(), exception);
                return Optional.empty();
            }
        }
    }

    /** Returns the known backups, newest first. */
    public List<Path> listBackups() {
        if (Files.notExists(backupDirectory)) return List.of();
        try (var stream = Files.list(backupDirectory)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".json.gz"))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Could not list backups in " + backupDirectory.toAbsolutePath(), exception);
            return List.of();
        }
    }

    /** Restores the store from a backup file (gzip or plain JSON). Returns {@code false} on failure. */
    public synchronized boolean restoreBackup(Path backup) {
        if (backup == null || Files.notExists(backup)) return false;
        try {
            Files.createDirectories(file.getParent());
            try (InputStream raw = Files.newInputStream(backup);
                 Reader reader = backup.getFileName().toString().endsWith(".gz")
                         ? new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8)
                         : new InputStreamReader(raw, StandardCharsets.UTF_8)) {
                StoreData restored = GSON.fromJson(reader, StoreData.class);
                if (restored == null) {
                    logger.severe("Backup " + backup + " is empty or unreadable.");
                    return false;
                }
                this.data = normalize(restored);
            }
            dirty = true;
            flushBlocking();
            logger.warning("Registration data restored from " + backup.toAbsolutePath());
            return true;
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "Could not restore data from " + backup.toAbsolutePath(), exception);
            return false;
        }
    }

    private void pruneBackups(int keepCount) {
        if (keepCount <= 0) return;
        List<Path> backups = listBackups();
        for (int index = keepCount; index < backups.size(); index++) {
            try {
                Files.deleteIfExists(backups.get(index));
            } catch (Exception exception) {
                logger.log(Level.WARNING, "Could not delete old backup " + backups.get(index), exception);
            }
        }
    }

    private void writeSnapshot(String json, long sequence) {
        synchronized (writeLock) {
            if (sequence < writtenSequence.get()) {
                // A newer snapshot has already been written; skip the stale one.
                return;
            }
            try {
                Files.createDirectories(file.getParent());
                Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    writer.write(json);
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
                writeIntegritySidecar(json);
                writtenSequence.set(sequence);
            } catch (Exception exception) {
                logger.log(Level.SEVERE, "Could not save registration data to " + file.toAbsolutePath(), exception);
            }
        }
    }

    private void writeIntegritySidecar(String json) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            Files.writeString(file.resolveSibling(file.getFileName() + ".sha256"), hex.toString(),
                    StandardCharsets.UTF_8);
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Could not write the data integrity file for " + file.toAbsolutePath(), exception);
        }
    }

    private boolean verifyIntegrity(String rawJson) {
        Path sidecar = file.resolveSibling(file.getFileName() + ".sha256");
        if (Files.notExists(sidecar)) return true;
        try {
            String expected = Files.readString(sidecar, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
            if (expected.isEmpty()) return true;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return expected.equals(hex.toString());
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Could not verify the integrity hash of " + file.toAbsolutePath(), exception);
            return true;
        }
    }

    private void load() {
        try {
            Files.createDirectories(file.getParent());
            if (Files.notExists(file)) {
                data = new StoreData();
                data.version = SCHEMA_VERSION;
                dirty = true;
                flushBlocking();
                return;
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (!verifyIntegrity(raw)) {
                logger.severe("Data file " + file.toAbsolutePath()
                        + " does not match its integrity hash (manual edit or disk corruption).");
                recoverFromCorruption("integrity");
                return;
            }
            StoreData loaded;
            try (Reader reader = new java.io.StringReader(raw)) {
                loaded = GSON.fromJson(reader, StoreData.class);
            }
            if (loaded == null) {
                logger.severe("Data file " + file.toAbsolutePath() + " is empty.");
                recoverFromCorruption("empty");
                return;
            }
            data = normalize(loaded);
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "Could not load registration data from " + file.toAbsolutePath(), exception);
            recoverFromCorruption("unreadable");
        }
    }

    private void recoverFromCorruption(String reason) {
        try {
            if (Files.exists(file)) {
                Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + Instant.now().toEpochMilli());
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                logger.severe("Unreadable data file backed up to " + backup.toAbsolutePath()
                        + " (reason: " + reason + ").");
            }
        } catch (Exception backupException) {
            logger.log(Level.SEVERE, "Could not back up the unreadable registration data", backupException);
        }
        List<Path> backups = listBackups();
        if (!backups.isEmpty() && restoreBackup(backups.get(0))) {
            logger.warning("Registration data recovered from the newest backup.");
            return;
        }
        logger.severe("No usable backup found — starting with empty registration data. "
                + "Check the backups directory before continuing.");
        data = new StoreData();
        data.version = SCHEMA_VERSION;
        dirty = true;
    }

    private StoreData normalize(StoreData loaded) {
        if (loaded.accounts == null) loaded.accounts = new LinkedHashMap<>();
        if (loaded.processedDonationIds == null) loaded.processedDonationIds = new LinkedHashSet<>();
        if (loaded.donationCursorsBySource == null) loaded.donationCursorsBySource = new LinkedHashMap<>();
        if (loaded.donations == null) loaded.donations = new ArrayList<>();
        if (loaded.accountAttempts == null) loaded.accountAttempts = new LinkedHashMap<>();
        if (loaded.ipAttempts == null) loaded.ipAttempts = new LinkedHashMap<>();
        if (loaded.paymentCodes == null) loaded.paymentCodes = new LinkedHashMap<>();
        if (loaded.trialUsedUuids == null) loaded.trialUsedUuids = new LinkedHashSet<>();
        for (PlayerAccount account : loaded.accounts.values()) {
            if (account.trialConsumed || account.isRegistered() || account.lastGrantAt > 0 || account.passUntil > 0) {
                account.trialConsumed = true;
                if (account.uuid != null && !account.uuid.isBlank()) loaded.trialUsedUuids.add(account.uuid);
            }
            if (account.ignoredPlayers == null) account.ignoredPlayers = new LinkedHashSet<>();
            if (account.sessions == null) account.sessions = new ArrayList<>();
            if (account.expiryWarningsSent == null) account.expiryWarningsSent = new LinkedHashSet<>();
        }
        int fromVersion = loaded.version <= 0 ? 1 : loaded.version;
        if (fromVersion < SCHEMA_VERSION) {
            migrate(loaded, fromVersion);
        }
        loaded.version = SCHEMA_VERSION;
        return loaded;
    }

    /** Applies structural migrations step by step, logging every applied step. */
    private void migrate(StoreData loaded, int fromVersion) {
        int version = fromVersion;
        if (version < 2) {
            // v2: IP auth sessions became a list of session entries; auth attempt counters were introduced.
            int migrated = 0;
            for (PlayerAccount account : loaded.accounts.values()) {
                if (account.lastAuthAt > 0 && account.lastAuthIp != null && !account.lastAuthIp.isBlank()
                        && account.sessions.isEmpty()) {
                    SessionEntry entry = new SessionEntry();
                    entry.ip = account.lastAuthIp;
                    entry.uuid = account.uuid == null ? "" : account.uuid;
                    entry.createdAt = account.lastAuthAt;
                    entry.lastSeenAt = account.lastAuthAt;
                    entry.source = "legacy";
                    account.sessions.add(entry);
                    migrated++;
                }
            }
            version = 2;
            logger.info("Storage migration v1 -> v2 applied (legacy IP sessions converted: " + migrated + ").");
        }
        if (version < 3) {
            // v3: DonatePay journal, payment codes, expiry-warning marks and chat mutes were introduced.
            version = 3;
            logger.info("Storage migration v2 -> v3 applied (DonatePay journal and expiry warnings added).");
        }
        if (version < 4) {
            logger.info("Storage migration v3 -> v4 applied (one-time registration trials; existing registered/paid accounts excluded).");
        }
    }

    /* ------------------------------------------------------------------ *
     *  Accounts, passes, passwords and TOTP 2FA
     * ------------------------------------------------------------------ */

    /** Records a player's UUID and exact casing when they join the server. */
    public synchronized PlayerAccount recordPlayerSeen(UUID uuid, String playerName) {
        String key = canonicalName(playerName);
        PlayerAccount account = data.accounts.computeIfAbsent(key, ignored -> new PlayerAccount());
        account.canonicalName = key;
        account.playerName = playerName;
        if (uuid != null) {
            account.uuid = uuid.toString();
        }
        if (account.trialConsumed || account.lastGrantAt > 0 || account.passUntil > 0 || account.isRegistered()) consumeTrial(account);
        markDirty();
        return copy(account);
    }

    private void consumeTrial(PlayerAccount account) {
        account.trialConsumed = true;
        if (account.uuid != null && !account.uuid.isBlank()) data.trialUsedUuids.add(account.uuid);
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
     * Агрегированные счётчики по всем аккаунтам — для метрик bStats и диагностики.
     * Только числа, без имён и персональных данных.
     */
    public record StatsSnapshot(int accounts, int registered, int activePass, int totpEnabled) {
    }

    /** Снимок счётчиков за один синхронизированный проход по аккаунтам. */
    public synchronized StatsSnapshot statsSnapshot(long nowMillis) {
        int registered = 0;
        int activePass = 0;
        int totpEnabled = 0;
        for (PlayerAccount account : data.accounts.values()) {
            if (account.isRegistered()) registered++;
            if (account.hasActivePass(nowMillis)) activePass++;
            if (account.isTotpEnabled()) totpEnabled++;
        }
        return new StatsSnapshot(data.accounts.size(), registered, activePass, totpEnabled);
    }

    /* ------------------------------------------------------------------ *
     *  Донатные ранги (1.4.167)
     * ------------------------------------------------------------------ */

    /** Ступень действующего ранга: 0 — нет, 1..5. Работает и для оффлайн-ников. */
    public synchronized int rankLevel(String playerName, long nowMillis) {
        if (playerName == null || playerName.isBlank()) {
            return 0;
        }
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        return account == null ? 0 : account.effectiveRank(nowMillis);
    }

    /** Момент окончания ранга; {@code 0} — ранга нет или он выдан навсегда. */
    public synchronized long rankUntil(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return 0L;
        }
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null || account.rankPermanent) {
            return 0L;
        }
        return Math.max(0L, account.passUntil);
    }

    /**
     * Выдаёт ранг до момента {@code untilMillis}.
     *
     * <p>Вызывающий сам считает срок: продление того же ранга складывает дни,
     * повышение — пересчитывает остаток по цене ({@link RankLadder#carryDays}).</p>
     */
    public synchronized PlayerAccount grantRankUntil(String playerName, long nowMillis, int level,
                                                     long untilMillis, String source) {
        PlayerAccount account = accountFor(playerName);
        account.rankLevel = RankLadder.clampLevel(level);
        account.rankPermanent = false;
        account.passUntil = Math.max(nowMillis, untilMillis);
        account.rankGrantedAt = nowMillis;
        consumeTrial(account);
        account.lastGrantSource = (source == null || source.isBlank()) ? "rank" : source;
        account.lastGrantAt = nowMillis;
        account.lastGrantDays = (int) Math.max(0, (account.passUntil - nowMillis) / 86_400_000L);
        clearExpiryWarnings(account);
        markDirtyNow();
        return copy(account);
    }

    /** Выдаёт ранг навсегда (миграция, ручная выдача, акции). */
    public synchronized PlayerAccount grantRankPermanent(String playerName, long nowMillis, int level,
                                                         String source) {
        PlayerAccount account = accountFor(playerName);
        account.rankLevel = RankLadder.clampLevel(level);
        account.rankPermanent = true;
        account.passUntil = 0L;
        account.rankGrantedAt = nowMillis;
        consumeTrial(account);
        account.lastGrantSource = (source == null || source.isBlank()) ? "rank-permanent" : source;
        account.lastGrantAt = nowMillis;
        account.lastGrantDays = 0;
        clearExpiryWarnings(account);
        markDirtyNow();
        return copy(account);
    }

    /**
     * Добавляет дни к действующему рангу — наградные дни квестов, батлпасса, стримов.
     * Без ранга открывает первую ступень. Постоянный ранг продлевать не нужно.
     *
     * @return срок после начисления; {@code 0} — дни не начислены (ранг навсегда)
     */
    public synchronized long extendRankDays(String playerName, long nowMillis, int days, String source) {
        if (days <= 0 || playerName == null || playerName.isBlank()) {
            return 0L;
        }
        PlayerAccount account = accountFor(playerName);
        if (account.rankPermanent) {
            return 0L;
        }
        if (account.rankLevel <= 0) {
            account.rankLevel = 1;
        }
        long base = Math.max(nowMillis, account.passUntil);
        account.passUntil = base + Math.max(1, days) * 86_400_000L;
        account.lastGrantSource = (source == null || source.isBlank()) ? "rank-days" : source;
        account.lastGrantAt = nowMillis;
        account.lastGrantDays = Math.max(1, days);
        clearExpiryWarnings(account);
        markDirtyNow();
        return account.passUntil;
    }

    /** Снимает ранг полностью (ручной отзыв, возврат). */
    public synchronized boolean revokeRank(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return false;
        }
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null) {
            return false;
        }
        boolean had = account.rankLevel > 0 || account.rankPermanent || account.passUntil > 0;
        account.rankLevel = 0;
        account.rankPermanent = false;
        account.passUntil = 0L;
        account.rankGrantedAt = 0L;
        clearExpiryWarnings(account);
        if (had) {
            markDirtyNow();
        }
        return had;
    }

    /**
     * Одноразовая миграция с проходки (1.4.167).
     *
     * <p>Кто платил — получает постоянный ранг по сумме платежей (I–III), кто играл
     * только на триале — первую ступень до конца оплаченного срока. Остальным ничего
     * не нужно: вход на сервер уже свободный.</p>
     *
     * @return сколько аккаунтов получило ранг
     */
    public synchronized int migrateRanks(long nowMillis) {
        if (data.rankSchemaVersion >= 1) {
            return 0;
        }
        int changed = 0;
        for (PlayerAccount account : data.accounts.values()) {
            if (account == null || account.rankLevel > 0 || account.rankPermanent) {
                continue;
            }
            int level = RankLadder.levelForLifetime(account.totalPaidKopecks);
            if (level > 0) {
                account.rankLevel = level;
                account.rankPermanent = true;
                account.passUntil = 0L;
                account.rankGrantedAt = nowMillis;
                account.lastGrantSource = "migration:permanent";
                changed++;
            } else if (account.passUntil > nowMillis) {
                account.rankLevel = 1;
                changed++;
            }
        }
        data.rankSchemaVersion = 1;
        if (changed > 0) {
            markDirtyNow();
        } else {
            markDirty();
        }
        return changed;
    }

    /** Общая подготовка аккаунта: канонический ключ и ник, если его ещё нет. */
    private PlayerAccount accountFor(String playerName) {
        String key = canonicalName(playerName);
        PlayerAccount account = data.accounts.computeIfAbsent(key, ignored -> new PlayerAccount());
        account.canonicalName = key;
        if (account.playerName == null || account.playerName.isBlank()) {
            account.playerName = playerName;
        }
        return account;
    }

    /** Grants or extends a pass manually with the default source tag. */
    public synchronized PlayerAccount grantPassManual(String playerName, long nowMillis, long durationMillis) {
        return grantPassManual(playerName, nowMillis, durationMillis, "manual");
    }

    /**
     * Grants or extends a pass manually, recording who/why it happened.
     *
     * @param source free-form tag shown in {@code /pass check} (manual, gift, admin:Nick, ...)
     */
    public synchronized PlayerAccount grantPassManual(
            String playerName,
            long nowMillis,
            long durationMillis,
            String source
    ) {
        String key = canonicalName(playerName);
        PlayerAccount account = data.accounts.computeIfAbsent(key, ignored -> new PlayerAccount());
        account.canonicalName = key;
        if (account.playerName == null || account.playerName.isBlank()) {
            account.playerName = playerName;
        }
        if (account.rankPermanent) {
            return copy(account); // ранг навсегда продлевать не нужно
        }
        long baseTime = Math.max(nowMillis, account.passUntil);
        account.passUntil = baseTime + durationMillis;
        consumeTrial(account);
        account.lastGrantSource = (source == null || source.isBlank()) ? "manual" : source;
        account.lastGrantAt = nowMillis;
        account.lastGrantDays = (int) Math.max(0, durationMillis / 86_400_000L);
        clearExpiryWarnings(account);
        markDirtyNow();
        return copy(account);
    }

    /**
     * Доводит проходку до горизонта {@code untilMillis} БЕЗ сложения: если текущий
     * срок уже дальше — ничего не меняем (анти-компаунд для подписочных перепроверок
     * Boosty: каждый день «поднимаем горизонт» до now+days, а не плюсуем).
     */
    public synchronized PlayerAccount renewPassUntil(
            String playerName,
            long nowMillis,
            long untilMillis,
            String source
    ) {
        String key = canonicalName(playerName);
        PlayerAccount account = data.accounts.computeIfAbsent(key, ignored -> new PlayerAccount());
        account.canonicalName = key;
        if (account.playerName == null || account.playerName.isBlank()) {
            account.playerName = playerName;
        }
        if (untilMillis <= account.passUntil) {
            return copy(account); // уже дальше — подписка просто подтверждена
        }
        account.passUntil = untilMillis;
        consumeTrial(account);
        account.lastGrantSource = (source == null || source.isBlank()) ? "subscription" : source;
        account.lastGrantAt = nowMillis;
        clearExpiryWarnings(account);
        markDirtyNow();
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
        return grantPassFromDonation(donorName, transactionCreatedAtMillis, nowMillis,
                durationMillis, strictCaseMatch, false);
    }

    /**
     * То же, но с пометкой тестовой оплаты ({@code /pass testpay}): проходка продлевается,
     * однако покупка не засчитывается — счётчик платежей и источник выдачи не меняются,
     * поэтому {@link PlayerAccount#isTrialOnly()} остаётся честным, а LuckPerms-группа
     * проходки не выдаётся.
     */
    public synchronized Optional<PlayerAccount> grantPassFromDonation(
            String donorName,
            long transactionCreatedAtMillis,
            long nowMillis,
            long durationMillis,
            boolean strictCaseMatch,
            boolean testPayment
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
        if (account.rankPermanent) {
            return Optional.of(copy(account)); // ранг навсегда уже есть
        }
        long start = Math.max(Math.max(nowMillis, transactionCreatedAtMillis), account.passUntil);
        account.passUntil = start + durationMillis;
        consumeTrial(account);
        if (!testPayment) {
            account.lastGrantSource = "donatepay";
            account.lastGrantAt = nowMillis;
            account.lastGrantDays = (int) Math.max(0, durationMillis / 86_400_000L);
            account.paymentsCount++;
        }
        clearExpiryWarnings(account);
        markDirtyNow();
        return Optional.of(copy(account));
    }

    /** Records the amount paid by a player (kopecks) for {@code /pass info} and statistics. */
    public synchronized void addPayment(String playerName, long amountKopecks, long nowMillis) {
        if (playerName == null || playerName.isBlank()) return;
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null) return;
        account.totalPaidKopecks += Math.max(0L, amountKopecks);
        account.lastPaymentAt = nowMillis;
        markDirty();
    }

    /* ------------------------------------------------------------------ *
     *  Каталог товаров (1.4.110)
     * ------------------------------------------------------------------ */

    /**
     * Выдаёт или продлевает товар каталога.
     *
     * <p>Срок складывается с текущим (повторная покупка не сгорает), но не выходит за
     * {@code now + maxTotalDays}. Возвращает момент окончания.</p>
     */
    public synchronized long grantEntitlement(
            String playerName,
            String productId,
            long nowMillis,
            int days,
            int maxTotalDays
    ) {
        if (playerName == null || playerName.isBlank() || productId == null || productId.isBlank()) {
            return 0L;
        }
        String key = canonicalName(playerName);
        PlayerAccount account = data.accounts.computeIfAbsent(key, ignored -> new PlayerAccount());
        account.canonicalName = key;
        if (account.playerName == null || account.playerName.isBlank()) {
            account.playerName = playerName;
        }
        if (account.entitlements == null) {
            account.entitlements = new LinkedHashMap<>();
        }
        long current = account.entitlements.getOrDefault(productId, 0L);
        long until = ShopEntitlements.extend(nowMillis, current, days, maxTotalDays);
        account.entitlements.put(productId, until);
        markDirtyNow();
        return until;
    }

    /** Момент окончания товара; {@code 0}, если товар никогда не покупался. */
    public synchronized long entitlementUntil(String playerName, String productId) {
        if (playerName == null || playerName.isBlank() || productId == null || productId.isBlank()) {
            return 0L;
        }
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null || account.entitlements == null) {
            return 0L;
        }
        return account.entitlements.getOrDefault(productId, 0L);
    }

    /** Копия карты «товар → срок» — для витрины и тестов. */
    public synchronized Map<String, Long> entitlementsOf(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return Map.of();
        }
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null || account.entitlements == null) {
            return Map.of();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : account.entitlements.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(result);
    }

    /** Снимает товар полностью (ручной отзыв, возврат). */
    public synchronized boolean revokeEntitlement(String playerName, String productId) {
        if (playerName == null || playerName.isBlank() || productId == null || productId.isBlank()) {
            return false;
        }
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null || account.entitlements == null) {
            return false;
        }
        boolean removed = account.entitlements.remove(productId) != null;
        if (removed) {
            markDirtyNow();
        }
        return removed;
    }

    /**
     * Убирает из аккаунтов истёкшие товары: файл не должен расти вечно.
     *
     * @return сколько записей удалено
     */
    public synchronized int cleanupEntitlements(long nowMillis) {
        int removed = 0;
        for (PlayerAccount account : data.accounts.values()) {
            if (account == null || account.entitlements == null || account.entitlements.isEmpty()) {
                continue;
            }
            int before = account.entitlements.size();
            account.entitlements.entrySet().removeIf(entry -> entry.getValue() == null
                    || entry.getValue() <= nowMillis);
            removed += before - account.entitlements.size();
        }
        if (removed > 0) {
            markDirtyNow();
        }
        return removed;
    }

    /**
     * Пишет продажу каталога в общий журнал донатов.
     *
     * <p>Id отрицательный и берётся из отдельного счётчика: положительные id принадлежат
     * DonatePay/EasyDonate, и путать их нельзя. Срок в записи — {@code 0}: журнал донатов
     * считает дни проходки, а товар продлевает не проходку.</p>
     */
    public synchronized DonationRecord recordShopSale(
            String playerName,
            String productId,
            int priceRubles,
            long nowMillis
    ) {
        return recordShopSale(playerName, productId, priceRubles, nowMillis, "");
    }

    /**
     * Продажа каталога с пометкой источника.
     *
     * @param extra хвост примечания: {@code gift:Ник} для подарка, пусто — обычная покупка.
     *              Хвост попадает в {@code note} после товара и не мешает фильтрам журнала
     */
    public synchronized DonationRecord recordShopSale(
            String playerName,
            String productId,
            int priceRubles,
            long nowMillis,
            String extra
    ) {
        DonationRecord record = new DonationRecord();
        record.id = --data.shopSaleSequence;
        record.source = DONATION_SOURCE_TRADEMC;
        record.donorName = playerName == null ? "" : playerName;
        record.amountKopecks = Math.max(0, priceRubles) * 100L;
        record.amountRaw = Integer.toString(Math.max(0, priceRubles));
        record.createdAt = nowMillis;
        record.detectedAt = nowMillis;
        record.status = DonationStatus.GRANTED.name();
        record.note = "shop:" + (productId == null ? "" : productId)
                + (extra == null || extra.isBlank() ? "" : ":" + extra.trim());
        record.grantedTo = record.donorName;
        record.decidedBy = "shop";
        record.decidedAt = nowMillis;
        record.days = 0;
        return recordDonation(record);
    }

    /** Revokes a player's active pass while keeping their registered password intact. */
    public synchronized boolean revokePass(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null || (account.passUntil <= 0 && account.rankLevel <= 0 && !account.rankPermanent)) {
            return false;
        }
        boolean hadRank = account.rankLevel > 0 || account.rankPermanent;
        account.passUntil = 0;
        if (hadRank) {
            account.rankLevel = 0;
            account.rankPermanent = false;
            account.rankGrantedAt = 0;
        }
        account.lastAuthIp = "";
        account.lastAuthAt = 0;
        if (account.sessions != null) account.sessions.clear();
        clearExpiryWarnings(account);
        markDirtyNow();
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
        return registerPassword(playerName, uuid, rawPassword, ipAddress, nowMillis, 0);
    }

    /** Register and consume eligibility atomically. Trial pass is created only for a fresh eligible account. */
    public synchronized boolean registerPassword(String playerName, UUID uuid, String rawPassword,
            String ipAddress, long nowMillis, int trialDays) {
        if (playerName == null || playerName.isBlank() || rawPassword == null || rawPassword.isEmpty()) {
            return false;
        }
        boolean grantTrial = trialDays > 0 && isTrialEligible(playerName, uuid);
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
        account.trialConsumed = true;
        if (uuid != null) data.trialUsedUuids.add(uuid.toString());
        if (grantTrial) {
            int days = Math.max(1, Math.min(365, trialDays));
            account.passUntil = Math.addExact(nowMillis, days * 86_400_000L);
            account.trialGrantedAt = nowMillis;
            account.trialUntil = account.passUntil;
            account.lastGrantSource = "trial";
            account.lastGrantAt = nowMillis;
            account.lastGrantDays = days;
            clearExpiryWarnings(account);
        }
        addSessionEntry(account, uuid, ipAddress, nowMillis, "register");
        markDirtyNow();
        return true;
    }

    /** Admission to registration, NOT admission to gameplay. Case-insensitive account plus UUID history. */
    public synchronized boolean isTrialEligible(String playerName, UUID uuid) {
        if (playerName == null || playerName.isBlank() || uuid == null || data.trialUsedUuids.contains(uuid.toString())) return false;
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        return account == null || (!account.trialConsumed && !account.isRegistered() && account.registeredAt == 0
                && account.passUntil == 0 && account.lastGrantAt == 0 && account.paymentsCount == 0);
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
        markDirtyNow();
        return true;
    }

    public synchronized boolean resetPassword(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null || !account.isRegistered()) return false;
        account.trialConsumed = true;
        if (account.uuid != null && !account.uuid.isBlank()) data.trialUsedUuids.add(account.uuid);
        account.passwordHash = "";
        account.registeredAt = 0;
        account.totpSecret = "";
        account.totpEnabledAt = 0;
        account.lastTotpStep = -1;
        account.lastAuthIp = "";
        account.lastAuthAt = 0;
        if (account.sessions != null) account.sessions.clear();
        markDirtyNow();
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
        markDirtyNow();
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
        markDirtyNow();
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
        markDirtyNow();
        return true;
    }

    /* ------------------------------------------------------------------ *
     *  Auth sessions
     * ------------------------------------------------------------------ */

    /**
     * Legacy session check without UUID binding — kept for backwards compatibility, prefer
     * {@link #hasValidIpSession(String, UUID, String, long, long, boolean)}.
     */
    public synchronized boolean hasValidIpSession(
            String playerName,
            String ipAddress,
            long nowMillis,
            long sessionDurationMillis
    ) {
        return hasValidIpSession(playerName, null, ipAddress, nowMillis, sessionDurationMillis, false);
    }

    /**
     * Returns {@code true} when an active session exists for the given IP.
     *
     * @param uuid when {@code requireSameUuid} is {@code true}, the session must belong to this player UUID
     */
    public synchronized boolean hasValidIpSession(
            String playerName,
            UUID uuid,
            String ipAddress,
            long nowMillis,
            long sessionDurationMillis,
            boolean requireSameUuid
    ) {
        if (sessionDurationMillis <= 0 || playerName == null || playerName.isBlank()
                || ipAddress == null || ipAddress.isBlank()) {
            return false;
        }
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null || !account.isRegistered() || !account.hasActivePass(nowMillis)) {
            return false;
        }
        Optional<SessionEntry> session = findFreshSession(account, ipAddress, nowMillis, sessionDurationMillis);
        if (session.isEmpty()) return false;
        if (requireSameUuid && uuid != null) {
            String stored = session.get().uuid;
            if (stored != null && !stored.isBlank() && !stored.equals(uuid.toString())) {
                return false;
            }
        }
        return true;
    }

    /** Creates or refreshes the session for the given IP, player UUID and start moment. */
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
        if (ipAddress != null && !ipAddress.isBlank()) {
            Optional<SessionEntry> existing = account.sessions.stream()
                    .filter(entry -> ipAddress.equals(entry.ip))
                    .findFirst();
            if (existing.isPresent()) {
                SessionEntry entry = existing.get();
                entry.lastSeenAt = nowMillis;
                if (uuid != null) entry.uuid = uuid.toString();
            } else {
                addSessionEntry(account, uuid, ipAddress, nowMillis, "login");
            }
        }
        markDirty();
    }

    /** Creates a session entry immediately (used on registration and login). */
    public synchronized void startSession(
            String playerName,
            UUID uuid,
            String ipAddress,
            long nowMillis,
            String source
    ) {
        if (playerName == null || playerName.isBlank()) return;
        String key = canonicalName(playerName);
        PlayerAccount account = data.accounts.get(key);
        if (account == null) return;
        addSessionEntry(account, uuid, ipAddress, nowMillis, source);
        account.lastAuthIp = ipAddress == null ? account.lastAuthIp : ipAddress;
        account.lastAuthAt = nowMillis;
        markDirty();
    }

    /** Clears every session of the player (legacy {@code /pass unregister} behaviour). */
    public synchronized void clearSession(String playerName) {
        clearSessions(playerName);
    }

    /** Clears every session of the player. */
    public synchronized boolean clearSessions(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null) return false;
        boolean had = !account.sessions.isEmpty() || !account.lastAuthIp.isEmpty();
        account.sessions.clear();
        account.lastAuthIp = "";
        account.lastAuthAt = 0;
        markDirtyNow();
        return had;
    }

    /** Clears only the session bound to the given IP. */
    public synchronized boolean clearSession(String playerName, String ipAddress) {
        if (playerName == null || playerName.isBlank() || ipAddress == null || ipAddress.isBlank()) return false;
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null) return false;
        boolean removed = account.sessions.removeIf(entry -> ipAddress.equals(entry.ip));
        if (removed) {
            if (ipAddress.equals(account.lastAuthIp)) {
                account.lastAuthIp = "";
                account.lastAuthAt = 0;
            }
            markDirtyNow();
        }
        return removed;
    }

    /** Returns the known sessions of the player, newest first. */
    public synchronized List<SessionEntry> listSessions(String playerName) {
        if (playerName == null || playerName.isBlank()) return List.of();
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null) return List.of();
        List<SessionEntry> sessions = new ArrayList<>(account.sessions.size());
        for (SessionEntry entry : account.sessions) {
            sessions.add(copySession(entry));
        }
        sessions.sort(Comparator.comparingLong((SessionEntry entry) -> entry.lastSeenAt).reversed());
        return sessions;
    }

    private void addSessionEntry(PlayerAccount account, UUID uuid, String ipAddress, long nowMillis, String source) {
        if (ipAddress == null || ipAddress.isBlank()) return;
        SessionEntry entry = account.sessions.stream()
                .filter(existing -> ipAddress.equals(existing.ip))
                .findFirst()
                .orElseGet(() -> {
                    SessionEntry created = new SessionEntry();
                    created.ip = ipAddress;
                    created.createdAt = nowMillis;
                    account.sessions.add(created);
                    return created;
                });
        if (uuid != null) entry.uuid = uuid.toString();
        entry.lastSeenAt = nowMillis;
        entry.source = source == null ? "" : source;
        if (account.sessions.size() > 10) {
            account.sessions.sort(Comparator.comparingLong(existing -> existing.lastSeenAt));
            while (account.sessions.size() > 10) {
                account.sessions.remove(0);
            }
        }
    }

    private Optional<SessionEntry> findFreshSession(
            PlayerAccount account,
            String ipAddress,
            long nowMillis,
            long sessionDurationMillis
    ) {
        for (SessionEntry entry : account.sessions) {
            if (!ipAddress.equals(entry.ip)) continue;
            long elapsed = nowMillis - entry.lastSeenAt;
            if (elapsed >= 0 && elapsed <= sessionDurationMillis) {
                return Optional.of(entry);
            }
        }
        // Fall back to the legacy single-session fields for data written by older versions.
        if (account.lastAuthIp != null && account.lastAuthIp.equals(ipAddress) && account.lastAuthAt > 0) {
            long elapsed = nowMillis - account.lastAuthAt;
            if (elapsed >= 0 && elapsed <= sessionDurationMillis) {
                SessionEntry legacy = new SessionEntry();
                legacy.ip = account.lastAuthIp;
                legacy.uuid = account.uuid == null ? "" : account.uuid;
                legacy.createdAt = account.lastAuthAt;
                legacy.lastSeenAt = account.lastAuthAt;
                legacy.source = "legacy";
                return Optional.of(legacy);
            }
        }
        return Optional.empty();
    }

    /* ------------------------------------------------------------------ *
     *  Auth attempt counters (brute-force protection)
     * ------------------------------------------------------------------ */

    public synchronized Optional<AttemptRecord> getAttempt(String key, AttemptKind kind) {
        if (key == null || key.isBlank()) return Optional.empty();
        Map<String, AttemptRecord> map = attempts(kind);
        AttemptRecord record = map.get(kind == AttemptKind.IP ? key : canonicalName(key));
        return record == null ? Optional.empty() : Optional.of(record.copy());
    }

    public synchronized void saveAttempt(String key, AttemptKind kind, AttemptRecord record) {
        if (key == null || key.isBlank() || record == null) return;
        Map<String, AttemptRecord> map = attempts(kind);
        map.put(kind == AttemptKind.IP ? key : canonicalName(key), record.copy());
        markDirty();
    }

    public synchronized void removeAttempt(String key, AttemptKind kind) {
        if (key == null || key.isBlank()) return;
        Map<String, AttemptRecord> map = attempts(kind);
        if (map.remove(kind == AttemptKind.IP ? key : canonicalName(key)) != null) {
            markDirty();
        }
    }

    /** Drops counters older than the given moment (housekeeping on startup). */
    public synchronized int pruneAttempts(long olderThanMillis) {
        int removed = 0;
        for (Map<String, AttemptRecord> map : List.of(data.accountAttempts, data.ipAttempts)) {
            List<String> keys = new ArrayList<>();
            for (Map.Entry<String, AttemptRecord> entry : map.entrySet()) {
                AttemptRecord record = entry.getValue();
                if (record != null
                        && record.blockedUntil < olderThanMillis
                        && record.windowStart < olderThanMillis
                        && record.count == 0) {
                    keys.add(entry.getKey());
                }
            }
            for (String key : keys) {
                map.remove(key);
                removed++;
            }
        }
        if (removed > 0) markDirty();
        return removed;
    }

    private Map<String, AttemptRecord> attempts(AttemptKind kind) {
        return kind == AttemptKind.IP ? data.ipAttempts : data.accountAttempts;
    }

    /* ------------------------------------------------------------------ *
     *  Ignore lists
     * ------------------------------------------------------------------ */

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
        markDirty();
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
        markDirty();
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

    /* ------------------------------------------------------------------ *
     *  Chat mutes
     * ------------------------------------------------------------------ */

    /** Applies or extends a chat mute. */
    public synchronized MuteEntry setMute(String playerName, long untilMillis, String reason, String author) {
        if (playerName == null || playerName.isBlank()) return null;
        String key = canonicalName(playerName);
        PlayerAccount account = data.accounts.computeIfAbsent(key, ignored -> new PlayerAccount());
        account.canonicalName = key;
        if (account.playerName == null || account.playerName.isBlank()) {
            account.playerName = playerName;
        }
        MuteEntry mute = new MuteEntry();
        mute.until = Math.max(untilMillis, System.currentTimeMillis());
        mute.reason = reason == null ? "" : reason;
        mute.author = author == null ? "" : author;
        mute.createdAt = System.currentTimeMillis();
        account.mute = mute;
        markDirty();
        return copyMute(mute);
    }

    public synchronized boolean clearMute(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null || account.mute == null) return false;
        account.mute = null;
        markDirty();
        return true;
    }

    /** Returns the active mute of the player, if any. */
    public synchronized Optional<MuteEntry> getMute(String playerName, long nowMillis) {
        if (playerName == null || playerName.isBlank()) return Optional.empty();
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null || account.mute == null) return Optional.empty();
        if (account.mute.until <= nowMillis) {
            account.mute = null;
            markDirty();
            return Optional.empty();
        }
        return Optional.of(copyMute(account.mute));
    }

    /** Returns all currently active mutes (name + entry), including expired entries for admin review. */
    public synchronized List<MuteEntry> listMutes(long nowMillis, boolean onlyActive) {
        List<MuteEntry> result = new ArrayList<>();
        for (PlayerAccount account : data.accounts.values()) {
            if (account.mute == null) continue;
            if (onlyActive && account.mute.until <= nowMillis) continue;
            MuteEntry entry = copyMute(account.mute);
            entry.playerName = account.playerName;
            result.add(entry);
        }
        result.sort(Comparator.comparingLong(entry -> entry.until));
        return result;
    }

    /** Идентификатор первоисточника донатов (исторический, без префиксов). */
    public static final String DONATION_SOURCE_DONATEPAY = "donatepay";
    /** Источник журнала: покупки магазина TradeMC, приходящие RCON-командой {@code /pass shoppay}. */
    public static final String DONATION_SOURCE_TRADEMC = "trademc";

    /* ------------------------------------------------------------------ *
     *  DonatePay journal, payment codes and counters
     * ------------------------------------------------------------------ */

    public synchronized boolean isDonationCursorInitialized() {
        return data.donationCursorInitialized;
    }

    /** Инициализирован ли курсор для источника (donatepay — по старому полю). */
    public synchronized boolean isDonationCursorInitialized(String source) {
        if (RegistrationStore.DONATION_SOURCE_DONATEPAY.equals(source)) {
            return isDonationCursorInitialized();
        }
        return data.donationCursorsBySource.containsKey(source);
    }

    /** Курсор опроса по источнику. */
    public synchronized long getDonationCursor(String source) {
        if (RegistrationStore.DONATION_SOURCE_DONATEPAY.equals(source)) {
            return getDonationCursor();
        }
        return data.donationCursorsBySource.getOrDefault(source, 0L);
    }

    /** Одноразовая инициализация курсора источника. */
    public synchronized void initializeDonationCursor(String source, long cursor) {
        if (RegistrationStore.DONATION_SOURCE_DONATEPAY.equals(source)) {
            initializeDonationCursor(cursor);
            return;
        }
        if (data.donationCursorsBySource.containsKey(source)) return;
        data.donationCursorsBySource.put(source, Math.max(0, cursor));
        markDirtyNow();
    }

    /** Обработан ли платёж данного источника (старые записи без префикса = donatepay). */
    public synchronized boolean isDonationProcessed(String source, long id) {
        if (RegistrationStore.DONATION_SOURCE_DONATEPAY.equals(source)) {
            return isDonationProcessed(id);
        }
        return data.processedDonationIds.contains(source + ":" + id);
    }

    /** Пометить платёж обработанным; курсор источника поднимается не ниже id. */
    public synchronized void markDonationProcessed(String source, long id) {
        if (RegistrationStore.DONATION_SOURCE_DONATEPAY.equals(source)) {
            markDonationProcessed(id);
            return;
        }
        String key = source + ":" + id;
        data.processedDonationIds.remove(key);
        data.processedDonationIds.add(key);
        data.donationCursorsBySource.put(source,
                Math.max(data.donationCursorsBySource.getOrDefault(source, 0L), id));
        while (data.processedDonationIds.size() > MAX_PROCESSED_DONATIONS) {
            String first = data.processedDonationIds.iterator().next();
            data.processedDonationIds.remove(first);
        }
        markDirty();
    }

    public synchronized long getDonationCursor() {
        return data.donationCursor;
    }

    public synchronized void initializeDonationCursor(long cursor) {
        if (data.donationCursorInitialized) return;
        data.donationCursor = Math.max(0, cursor);
        data.donationCursorInitialized = true;
        markDirtyNow();
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
        markDirty();
    }

    /**
     * Stores a DonatePay transaction in the journal. Existing entries are never overwritten, so a repeated
     * poll cannot resurface a decision an administrator already made.
     *
     * @return the stored record (either the freshly inserted one or the pre-existing entry)
     */
    public synchronized DonationRecord recordDonation(DonationRecord record) {
        if (record == null) return null;
        Optional<DonationRecord> existing = getDonationInternal(record.id);
        if (existing.isPresent()) return copyDonation(existing.get());
        data.donations.add(record.copy());
        if (data.donations.size() > MAX_JOURNAL_ENTRIES) {
            // Режем по времени, а не по id: у продаж каталога id отрицательные,
            // и сортировка по id выбрасывала бы их первыми, хотя они самые свежие.
            data.donations.sort(Comparator.comparingLong(entry -> entry.createdAt));
            while (data.donations.size() > MAX_JOURNAL_ENTRIES) {
                data.donations.remove(0);
            }
        }
        markDirtyNow();
        return copyDonation(record);
    }

    public synchronized Optional<DonationRecord> getDonation(long id) {
        return getDonationInternal(id).map(RegistrationStore::copyDonation);
    }

    private Optional<DonationRecord> getDonationInternal(long id) {
        for (DonationRecord record : data.donations) {
            if (record != null && record.id == id) return Optional.of(record);
        }
        return Optional.empty();
    }

    /** Updates the decision fields of a journal entry. */
    public synchronized boolean updateDonation(
            long id,
            String status,
            String note,
            String decidedBy,
            long decidedAt,
            String grantedTo,
            int days
    ) {
        Optional<DonationRecord> existing = getDonationInternal(id);
        if (existing.isEmpty()) return false;
        DonationRecord record = existing.get();
        if (status != null && !status.isBlank()) record.status = status;
        if (note != null) record.note = note;
        if (decidedBy != null) record.decidedBy = decidedBy;
        if (decidedAt > 0) record.decidedAt = decidedAt;
        if (grantedTo != null) record.grantedTo = grantedTo;
        if (days > 0) record.days = days;
        markDirtyNow();
        return true;
    }

    /** Lists journal entries, optionally filtered by status. Newest first. */
    public synchronized List<DonationRecord> listDonations(String statusFilter, int limit) {
        List<DonationRecord> result = new ArrayList<>();
        DonationStatus filter = statusFilter == null || statusFilter.isBlank()
                ? null
                : DonationStatus.parse(statusFilter);
        for (DonationRecord record : data.donations) {
            if (record == null) continue;
            if (filter != null && DonationStatus.parse(record.status) != filter) continue;
            result.add(copyDonation(record));
        }
        result.sort(Comparator.comparingLong((DonationRecord record) -> record.createdAt).reversed());
        if (limit > 0 && result.size() > limit) {
            return new ArrayList<>(result.subList(0, limit));
        }
        return result;
    }

    /**
     * Донаты конкретного игрока (для GUI {@code /pass donate}): запись относится к игроку,
     * если совпал {@code grantedTo} ИЛИ имя донатора по форме (без учёта регистра).
     * Сортировка — от новых к старым, {@code limit} обрезает сверху.
     */
    public synchronized List<DonationRecord> listDonationsOf(String playerName, int limit) {
        if (playerName == null || playerName.isBlank()) {
            return List.of();
        }
        List<DonationRecord> result = new ArrayList<>();
        for (DonationRecord record : data.donations) {
            if (record == null) continue;
            boolean mine = (record.grantedTo != null && record.grantedTo.equalsIgnoreCase(playerName))
                    || (record.donorName != null && record.donorName.trim().equalsIgnoreCase(playerName));
            if (mine) {
                result.add(copyDonation(record));
            }
        }
        result.sort(Comparator.comparingLong((DonationRecord record) -> record.createdAt).reversed());
        if (limit > 0 && result.size() > limit) {
            return new ArrayList<>(result.subList(0, limit));
        }
        return result;
    }

    /** Counts journal entries with the given status ({@code null} counts everything except rejections). */
    public synchronized int countDonations(DonationStatus status) {
        int count = 0;
        for (DonationRecord record : data.donations) {
            if (record == null) continue;
            DonationStatus recordStatus = DonationStatus.parse(record.status);
            if (status == null) {
                if (recordStatus != DonationStatus.REJECTED) count++;
            } else if (recordStatus == status) {
                count++;
            }
        }
        return count;
    }

    /** Sums granted donations (kopecks) in the given time window. */
    public synchronized long revenueKopecks(long sinceMillis, long untilMillis) {
        long total = 0;
        for (DonationRecord record : data.donations) {
            if (record == null) continue;
            if (DonationStatus.parse(record.status) != DonationStatus.GRANTED) continue;
            long stamp = record.decidedAt > 0 ? record.decidedAt : record.createdAt;
            if (stamp < sinceMillis || stamp > untilMillis) continue;
            total += Math.max(0L, record.amountKopecks);
        }
        return total;
    }

    /** Returns the payment code of the player, creating one when absent. */
    public synchronized String getOrCreatePaymentCode(String playerName, java.util.function.Supplier<String> generator) {
        if (playerName == null || playerName.isBlank()) return "";
        String key = canonicalName(playerName);
        String existing = data.paymentCodes.get(key);
        if (existing != null && !existing.isBlank()) return existing;
        String created = generator == null ? "" : generator.get();
        if (created == null || created.isBlank()) return "";
        data.paymentCodes.put(key, created);
        markDirty();
        return created;
    }

    public synchronized Optional<String> getPaymentCode(String playerName) {
        if (playerName == null || playerName.isBlank()) return Optional.empty();
        String code = data.paymentCodes.get(canonicalName(playerName));
        return code == null || code.isBlank() ? Optional.empty() : Optional.of(code);
    }

    /** Finds the player whose payment code matches the given donor name, if any. */
    public synchronized Optional<String> findPlayerByPaymentCode(String donorName) {
        if (donorName == null || donorName.isBlank()) return Optional.empty();
        String normalized = DonorNameMatcher.normalize(donorName);
        for (Map.Entry<String, String> entry : data.paymentCodes.entrySet()) {
            String code = entry.getValue();
            if (code == null || code.isBlank()) continue;
            if (normalized.contains(code.toLowerCase(Locale.ROOT))) {
                PlayerAccount account = data.accounts.get(entry.getKey());
                return Optional.of(account != null && account.playerName != null && !account.playerName.isBlank()
                        ? account.playerName
                        : entry.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * Suggests known players that plausibly correspond to the donor name (case, separators, RU/EN layout,
     * one-character typos). Never grants anything — only helps an administrator to decide.
     */
    public synchronized List<String> suggestPlayersForDonor(String donorName, int limit) {
        List<String> suggestions = new ArrayList<>();
        if (donorName == null || donorName.isBlank()) return suggestions;
        String normalized = DonorNameMatcher.normalize(donorName);
        for (PlayerAccount account : data.accounts.values()) {
            if (account.playerName == null || account.playerName.isBlank()) continue;
            if (!account.isRegistered() && !account.hasActivePass(System.currentTimeMillis())) {
                // Only suggest players we actually know something about.
                if (account.uuid == null || account.uuid.isBlank()) continue;
            }
            String candidate = DonorNameMatcher.normalize(account.playerName);
            int distance = DonorNameMatcher.levenshtein(normalized, candidate);
            if (distance <= 1 || DonorNameMatcher.layoutEquivalent(normalized, candidate)) {
                suggestions.add(account.playerName);
                if (suggestions.size() >= Math.max(1, limit)) break;
            }
        }
        return suggestions;
    }

    /* ------------------------------------------------------------------ *
     *  Expiry warnings
     * ------------------------------------------------------------------ */

    public synchronized boolean wasExpiryWarningSent(String playerName, long warnMinutes) {
        if (playerName == null || playerName.isBlank()) return false;
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        return account != null && account.expiryWarningsSent.contains(warnMinutes);
    }

    public synchronized void markExpiryWarningSent(String playerName, long warnMinutes) {
        if (playerName == null || playerName.isBlank()) return;
        PlayerAccount account = data.accounts.get(canonicalName(playerName));
        if (account == null) return;
        if (account.expiryWarningsSent.add(warnMinutes)) {
            markDirty();
        }
    }

    private void clearExpiryWarnings(PlayerAccount account) {
        if (account.expiryWarningsSent != null && !account.expiryWarningsSent.isEmpty()) {
            account.expiryWarningsSent.clear();
        }
    }

    /* ------------------------------------------------------------------ *
     *  Aggregates used by /pass status, /pass stats and /pass doctor
     * ------------------------------------------------------------------ */

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

    /** Returns a snapshot of every known account (used by statistics and diagnostics). */
    public synchronized List<PlayerAccount> getAllAccounts() {
        List<PlayerAccount> accounts = new ArrayList<>(data.accounts.size());
        for (PlayerAccount account : data.accounts.values()) {
            accounts.add(copy(account));
        }
        return accounts;
    }

    /** Returns players that recently authenticated from the given IP (multi-account detection). */
    public synchronized List<String> accountsSharingIp(String ipAddress, long nowMillis, long windowMillis) {
        if (ipAddress == null || ipAddress.isBlank()) return List.of();
        List<String> names = new ArrayList<>();
        for (PlayerAccount account : data.accounts.values()) {
            if (account.playerName == null || account.playerName.isBlank()) continue;
            for (SessionEntry entry : account.sessions) {
                if (!ipAddress.equals(entry.ip)) continue;
                long elapsed = nowMillis - entry.lastSeenAt;
                if (elapsed >= 0 && elapsed <= windowMillis) {
                    names.add(account.playerName);
                    break;
                }
            }
        }
        return names;
    }

    /** Returns the number of accounts that have a registered password. */
    public synchronized int getRegisteredAccountsWithFreshSessions(long nowMillis, long windowMillis) {
        int count = 0;
        for (PlayerAccount account : data.accounts.values()) {
            for (SessionEntry entry : account.sessions) {
                long elapsed = nowMillis - entry.lastSeenAt;
                if (elapsed >= 0 && elapsed <= windowMillis) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /* ------------------------------------------------------------------ *
     *  Internal helpers
     * ------------------------------------------------------------------ */

    private void markDirty() {
        if (asyncExecutor == null) {
            markDirtyNow();
        } else {
            dirty = true;
        }
    }

    private void markDirtyNow() {
        if (asyncExecutor == null) {
            flushBlocking();
        } else {
            synchronized (this) {
                dirty = false;
                long sequence = snapshotSequence.incrementAndGet();
                String json = GSON.toJson(data);
                asyncExecutor.execute(() -> writeSnapshot(json, sequence));
            }
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
        result.trialConsumed = source.trialConsumed;
        result.trialGrantedAt = source.trialGrantedAt;
        result.trialUntil = source.trialUntil;
        result.lastGrantSource = source.lastGrantSource;
        result.lastGrantAt = source.lastGrantAt;
        result.lastGrantDays = source.lastGrantDays;
        result.passwordHash = source.passwordHash;
        result.registeredAt = source.registeredAt;
        result.totpSecret = source.totpSecret == null ? "" : source.totpSecret;
        result.totpEnabledAt = source.totpEnabledAt;
        result.lastTotpStep = source.lastTotpStep;
        result.lastAuthIp = source.lastAuthIp;
        result.lastAuthAt = source.lastAuthAt;
        result.totalPaidKopecks = source.totalPaidKopecks;
        result.paymentsCount = source.paymentsCount;
        result.lastPaymentAt = source.lastPaymentAt;
        result.ignoredPlayers = source.ignoredPlayers == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(source.ignoredPlayers);
        result.sessions = new ArrayList<>();
        if (source.sessions != null) {
            for (SessionEntry entry : source.sessions) {
                result.sessions.add(copySession(entry));
            }
        }
        result.expiryWarningsSent = source.expiryWarningsSent == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(source.expiryWarningsSent);
        result.entitlements = source.entitlements == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(source.entitlements);
        result.mute = source.mute == null ? null : copyMute(source.mute);
        return result;
    }

    private static SessionEntry copySession(SessionEntry source) {
        SessionEntry copy = new SessionEntry();
        copy.ip = source.ip;
        copy.uuid = source.uuid;
        copy.createdAt = source.createdAt;
        copy.lastSeenAt = source.lastSeenAt;
        copy.source = source.source;
        return copy;
    }

    private static MuteEntry copyMute(MuteEntry source) {
        MuteEntry copy = new MuteEntry();
        copy.until = source.until;
        copy.reason = source.reason;
        copy.author = source.author;
        copy.createdAt = source.createdAt;
        copy.playerName = source.playerName;
        return copy;
    }

    private static DonationRecord copyDonation(DonationRecord source) {
        DonationRecord copy = new DonationRecord();
        copy.id = source.id;
        copy.source = source.source;
        copy.donorName = source.donorName;
        copy.amountKopecks = source.amountKopecks;
        copy.amountRaw = source.amountRaw;
        copy.createdAt = source.createdAt;
        copy.status = source.status;
        copy.note = source.note;
        copy.decidedBy = source.decidedBy;
        copy.decidedAt = source.decidedAt;
        copy.grantedTo = source.grantedTo;
        copy.days = source.days;
        copy.suggestedName = source.suggestedName;
        copy.detectedAt = source.detectedAt;
        return copy;
    }

    /* ------------------------------------------------------------------ *
     *  Persisted model
     * ------------------------------------------------------------------ */

    /** One authenticating IP session of an account. */
    public static final class SessionEntry {
        public String ip = "";
        public String uuid = "";
        public long createdAt;
        public long lastSeenAt;
        public String source = "";

        public long ageMillis(long nowMillis) {
            return Math.max(0L, nowMillis - lastSeenAt);
        }
    }

    /** Failed-auth counter with a sliding window and an optional block deadline. */
    public static final class AttemptRecord {
        public int count;
        public long windowStart;
        public long blockedUntil;
        public String lastIp = "";
        public int totalFailures;

        public AttemptRecord copy() {
            AttemptRecord copy = new AttemptRecord();
            copy.count = count;
            copy.windowStart = windowStart;
            copy.blockedUntil = blockedUntil;
            copy.lastIp = lastIp;
            copy.totalFailures = totalFailures;
            return copy;
        }
    }

    /** Chat mute of a player. */
    public static final class MuteEntry {
        public long until;
        public String reason = "";
        public String author = "";
        public long createdAt;
        public String playerName = "";

        public long remainingMillis(long nowMillis) {
            return Math.max(0L, until - nowMillis);
        }
    }

    /** One DonatePay transaction from the journal. */
    public static final class DonationRecord {
        public long id;
        public String donorName = "";
        /** Источник доната: donatepay (пусто/отсутствует у старых записей) или easydonate. */
        public String source = DONATION_SOURCE_DONATEPAY;
        public long amountKopecks;
        public String amountRaw = "";
        public long createdAt;
        public String status = DonationStatus.PENDING_REVIEW.name();
        public String note = "";
        public String decidedBy = "";
        public long decidedAt;
        public String grantedTo = "";
        public int days;
        public String suggestedName = "";
        public long detectedAt;

        public DonationStatus donationStatus() {
            return DonationStatus.parse(status);
        }

        public DonationRecord copy() {
            return copyDonation(this);
        }

        public String amountRubles() {
            long rubles = amountKopecks / 100;
            long kopecks = Math.abs(amountKopecks % 100);
            return kopecks == 0 ? Long.toString(rubles) : rubles + "." + (kopecks < 10 ? "0" + kopecks : kopecks);
        }
    }

    /** An account of a player: pass, credentials, 2FA, sessions, ignore list and mute. */
    public static final class PlayerAccount {
        public String uuid = "";
        public String playerName = "";
        public String canonicalName = "";
        public long passUntil;
        /** Ступень донатного ранга: 0 — без ранга, 1..5 (1.4.167). */
        public int rankLevel;
        /** Ранг выдан навсегда: миграция с проходки или ручная выдача. */
        public boolean rankPermanent;
        /** Когда выдан текущий ранг (мс). */
        public long rankGrantedAt;
        public boolean trialConsumed;
        public long trialGrantedAt;
        public long trialUntil;
        public String lastGrantSource = "";
        public long lastGrantAt;
        public int lastGrantDays;
        public String passwordHash = "";
        public long registeredAt;
        public String totpSecret = "";
        public long totpEnabledAt;
        public long lastTotpStep = -1;
        public String lastAuthIp = "";
        public long lastAuthAt;
        public Set<String> ignoredPlayers = new LinkedHashSet<>();
        public List<SessionEntry> sessions = new ArrayList<>();
        public Set<Long> expiryWarningsSent = new LinkedHashSet<>();
        public MuteEntry mute;
        public long totalPaidKopecks;
        public int paymentsCount;
        public long lastPaymentAt;
        /**
         * Купленные товары каталога: id товара → момент окончания (мс).
         *
         * <p>Поле появилось в 1.4.110; у старых файлов его нет, поэтому все методы
         * {@link RegistrationStore} проверяют карту на {@code null}.</p>
         */
        public Map<String, Long> entitlements = new LinkedHashMap<>();

        public boolean isTrialOnly() {
            return trialGrantedAt > 0 && "trial".equals(lastGrantSource) && paymentsCount == 0;
        }

        /**
         * Ступень действующего ранга: 0 — ранга нет, 1..5 — лестница донатных рангов.
         *
         * <p>Аккаунты, созданные до 1.4.167, хранят только {@code passUntil}; для них
         * действующий срок считается первой ступенью — миграция поднимет их до
         * постоянного ранга, но даже без неё игрок не теряет оплаченное.</p>
         */
        public int effectiveRank(long nowMillis) {
            if (rankPermanent) {
                return Math.max(1, rankLevel);
            }
            if (passUntil > nowMillis) {
                return Math.max(1, rankLevel);
            }
            return 0;
        }

        public boolean hasActivePass(long nowMillis) {
            return effectiveRank(nowMillis) > 0;
        }

        public boolean isRegistered() {
            return passwordHash != null && !passwordHash.isBlank();
        }

        public boolean isTotpEnabled() {
            return totpSecret != null && !totpSecret.isBlank();
        }
    }

    private static final class StoreData {
        int version;
        /** Версия схемы рангов: 1 — миграция с проходки выполнена (1.4.167). */
        int rankSchemaVersion;
        Map<String, PlayerAccount> accounts = new LinkedHashMap<>();
        Set<String> trialUsedUuids = new LinkedHashSet<>();
        Set<String> processedDonationIds = new LinkedHashSet<>();
        long donationCursor;
        boolean donationCursorInitialized;
        /** Курсоры опроса по источникам (donatepay живёт в старых полях — обратная совместимость). */
        Map<String, Long> donationCursorsBySource = new LinkedHashMap<>();
        Map<String, AttemptRecord> accountAttempts = new LinkedHashMap<>();
        Map<String, AttemptRecord> ipAttempts = new LinkedHashMap<>();
        List<DonationRecord> donations = new ArrayList<>();
        Map<String, String> paymentCodes = new LinkedHashMap<>();
        /** Сквозной счётчик продаж каталога: id в журнале отрицательные, чтобы не путать с донатами. */
        long shopSaleSequence;
    }
}
