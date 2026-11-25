package ru.cherdakmd.chrdkfishing;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Cumulative album milestone payouts; a durable source checkpoint always precedes the bank. */
public final class AlbumRewardJournal implements AutoCloseable {
    private static final int APPLICATION_ID = 1178686034;
    private final Connection db;
    private final UUID lineage;
    private static final int[] REQUIRED = { 5, 10, 20, 30 };
    // Provisional fixed cumulative rewards in hundredths of a diamond.
    private static final long[] TOTALS = { 0, 50, 150, 350, 850 };
    public record Progress(int paidStage, int targetStage, long confirmed, long target) { }
    public enum Result { DONE, WAIT, CONFLICT }
    public interface Bank {
        /** Complete UUID-to-minor receipt snapshot for this lineage; null means unavailable. */
        Map<UUID, Long> receipts(UUID lineage) throws Exception;
        /** Idempotent cumulative credit: false/exception may still have partially or fully committed. */
        boolean credit(UUID lineage, UUID owner, long total) throws Exception;
    }
    public AlbumRewardJournal(Path path, UUID lineage, boolean allowCreate) throws SQLException {
        this.lineage = Objects.requireNonNull(lineage); boolean fresh = false;
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (!allowCreate) throw new SQLException("album reward journal missing");
                Files.createFile(path); fresh = true;
            } else if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new SQLException("invalid reward journal");
        } catch (java.io.IOException error) { throw new SQLException("reward journal creation", error); }
        db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (var s = db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000"); s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA synchronous=FULL");
            if (fresh) {
                db.setAutoCommit(false);
                try {
                    s.execute("CREATE TABLE metadata (id INTEGER PRIMARY KEY CHECK(id=1), lineage TEXT NOT NULL) STRICT");
                    s.execute("CREATE TABLE rewards (owner TEXT PRIMARY KEY NOT NULL, paid_stage INTEGER NOT NULL CHECK(paid_stage BETWEEN 0 AND 4), target_stage INTEGER NOT NULL CHECK(target_stage BETWEEN 0 AND 4), confirmed INTEGER NOT NULL CHECK(confirmed>=0), target INTEGER NOT NULL CHECK(target>=0)) STRICT");
                    try (var i = db.prepareStatement("INSERT INTO metadata VALUES(1,?)")) { i.setString(1, lineage.toString()); i.executeUpdate(); }
                    s.execute("PRAGMA application_id=" + APPLICATION_ID); s.execute("PRAGMA user_version=1"); db.commit();
                } catch (SQLException error) { db.rollback(); throw error; }
                finally { db.setAutoCommit(true); }
            } else {
                try (var r = s.executeQuery("PRAGMA application_id")) { if (!r.next() || r.getInt(1) != APPLICATION_ID) throw new SQLException("wrong album reward identity"); }
                try (var r = s.executeQuery("PRAGMA user_version")) { if (!r.next() || r.getInt(1) != 1) throw new SQLException("unsupported reward schema"); }
                try (var r = s.executeQuery("PRAGMA quick_check")) { if (!r.next() || !"ok".equals(r.getString(1))) throw new SQLException("reward journal corrupt"); }
                try (var r = s.executeQuery("SELECT lineage FROM metadata WHERE id=1")) { if (!r.next() || !lineage.toString().equals(r.getString(1))) throw new SQLException("reward lineage mismatch"); }
                s.executeQuery("SELECT owner,paid_stage,target_stage,confirmed,target FROM rewards LIMIT 0").close();
            }
        } catch (SQLException error) { db.close(); throw error; }
    }
    public static int eligibleStage(int species) {
        if (species < 0) throw new IllegalArgumentException("species count");
        int stage = 0; for (int required : REQUIRED) if (species >= required) stage++; return stage;
    }
    public static int requiredFor(int stage) { if (stage < 1 || stage > 4) throw new IllegalArgumentException("stage"); return REQUIRED[stage - 1]; }
    public static long cumulativeFor(int stage) { if (stage < 0 || stage > 4) throw new IllegalArgumentException("stage"); return TOTALS[stage]; }
    public synchronized Progress progress(UUID owner) throws SQLException {
        Objects.requireNonNull(owner);
        try (var s = db.prepareStatement("SELECT paid_stage,target_stage,confirmed,target FROM rewards WHERE owner=?")) {
            s.setString(1, owner.toString());
            try (var r = s.executeQuery()) {
                if (!r.next()) return new Progress(0, 0, 0, 0);
                var p = new Progress(r.getInt(1), r.getInt(2), r.getLong(3), r.getLong(4));
                validate(p); return p;
            }
        }
    }
    private static void validate(Progress p) throws SQLException {
        if (p.paidStage() < 0 || p.targetStage() < p.paidStage() || p.targetStage() > 4
                || p.confirmed() < 0 || p.target() < p.confirmed() || p.target() > 1_000_000
                || (p.paidStage() == p.targetStage()) != (p.confirmed() == p.target())) throw new SQLException("invalid album reward state");
    }
    private Map<UUID, Progress> all() throws SQLException {
        Map<UUID, Progress> out = new HashMap<>();
        try (var s = db.createStatement(); var r = s.executeQuery("SELECT owner,paid_stage,target_stage,confirmed,target FROM rewards")) {
            while (r.next()) {
                try {
                    String raw = r.getString(1); UUID id = UUID.fromString(raw);
                    if (!id.toString().equals(raw)) throw new IllegalArgumentException("owner");
                    var p = new Progress(r.getInt(2), r.getInt(3), r.getLong(4), r.getLong(5)); validate(p); out.put(id, p);
                } catch (RuntimeException invalid) { throw new SQLException("invalid album reward owner", invalid); }
            }
        }
        return out;
    }
    /** Compares every owner's bank receipt: old backups must not permit replaying claims. */
    public synchronized boolean reconciled(Bank bank) throws Exception {
        var receipts = bank.receipts(lineage); if (receipts == null) return false;
        var rows = all();
        for (var entry : receipts.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0) return false;
            var p = rows.get(entry.getKey());
            if (p == null || entry.getValue() < p.confirmed() || entry.getValue() > p.target()) return false;
        }
        for (var entry : rows.entrySet()) {
            Long actual = receipts.get(entry.getKey()); long value = actual == null ? 0 : actual;
            if (value < entry.getValue().confirmed() || value > entry.getValue().target()) return false;
        }
        return true;
    }
    /**
     * Initialize an empty journal from an authoritative bank snapshot, without paying anyone.
     * This cannot discover claims not yet recorded by the bank: a separate audit of the
     * old journal for unsettled claims is mandatory before switching generations.
     * Only the known exact cumulative schedule can be reconstructed; unknown amounts fail.
     */
    public synchronized void bootstrapFromBank(Bank bank) throws Exception {
        Objects.requireNonNull(bank);
        var receipts = bank.receipts(lineage);
        if (receipts == null) throw new SQLException("album bank unavailable");
        Map<UUID, Integer> stages = new HashMap<>();
        for (var row : receipts.entrySet()) {
            if (row.getKey() == null || row.getValue() == null) throw new SQLException("invalid album receipt");
            int stage = -1;
            for (int i = 1; i < TOTALS.length; i++) if (TOTALS[i] == row.getValue()) stage = i;
            if (stage < 0) throw new SQLException("unrecognized album receipt amount");
            stages.put(row.getKey(), stage);
        }
        db.setAutoCommit(false);
        try {
            try (var s = db.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM rewards")) {
                if (!r.next() || r.getLong(1) != 0) throw new SQLException("reward journal not empty");
            }
            try (var s = db.prepareStatement("INSERT INTO rewards(owner,paid_stage,target_stage,confirmed,target) VALUES(?,?,?,?,?)")) {
                for (var row : stages.entrySet()) {
                    int stage = row.getValue(); long total = TOTALS[stage];
                    s.setString(1, row.getKey().toString()); s.setInt(2, stage); s.setInt(3, stage);
                    s.setLong(4, total); s.setLong(5, total); s.addBatch();
                }
                s.executeBatch();
            }
            db.commit();
        } catch (Exception error) {
            db.rollback(); throw error;
        } finally { db.setAutoCommit(true); }
        if (!reconciled(bank)) throw new SQLException("album bank changed during bootstrap");
    }
    /** A clean generation must not adopt unresolved financial reward claims. */
    public synchronized boolean hasUnsettled() throws SQLException {
        for (var p : all().values()) if (p.paidStage() != p.targetStage()) return true;
        return false;
    }
    /** Caller must obtain unique validated album records for this owner; cannot trust client counts. */
    public synchronized Result claim(UUID owner, int uniqueSpecies, Bank bank) throws Exception {
        if (owner == null || bank == null) throw new IllegalArgumentException("claim");
        if (!reconciled(bank)) return Result.CONFLICT;
        Progress old = progress(owner);
        if (old.targetStage() != old.paidStage()) return recover(owner, bank);
        int next = eligibleStage(uniqueSpecies);
        if (next <= old.paidStage()) return Result.DONE;
        long reward = cumulativeFor(next);
        if (reward <= old.confirmed()) return Result.CONFLICT;
        try (var s = db.prepareStatement("INSERT INTO rewards(owner,paid_stage,target_stage,confirmed,target) VALUES(?,?,?,?,?) ON CONFLICT(owner) DO UPDATE SET target_stage=excluded.target_stage,target=excluded.target WHERE rewards.paid_stage=rewards.target_stage AND rewards.confirmed=rewards.target")) {
            s.setString(1, owner.toString()); s.setInt(2, old.paidStage()); s.setInt(3, next);
            s.setLong(4, old.confirmed()); s.setLong(5, reward);
            if (s.executeUpdate() != 1) return Result.CONFLICT;
        }
        return recover(owner, bank);
    }
    public synchronized Result recover(UUID owner, Bank bank) throws Exception {
        if (owner == null || bank == null) throw new IllegalArgumentException("reward recovery");
        if (!reconciled(bank)) return Result.CONFLICT;
        Progress p = progress(owner);
        if (p.paidStage() == p.targetStage()) return Result.DONE;
        if (!bank.credit(lineage, owner, p.target())) return Result.WAIT;
        var receipts = bank.receipts(lineage);
        if (receipts == null || !Long.valueOf(p.target()).equals(receipts.get(owner)) || !reconciled(bank)) return Result.CONFLICT;
        try (var s = db.prepareStatement("UPDATE rewards SET paid_stage=target_stage,confirmed=target WHERE owner=? AND target_stage=? AND target=?")) {
            s.setString(1, owner.toString()); s.setInt(2, p.targetStage()); s.setLong(3, p.target());
            return s.executeUpdate() == 1 ? Result.DONE : Result.CONFLICT;
        }
    }
    @Override public synchronized void close() throws SQLException { db.close(); }
}
