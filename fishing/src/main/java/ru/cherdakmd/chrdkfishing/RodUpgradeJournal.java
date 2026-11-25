package ru.cherdakmd.chrdkfishing;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * Unconnected upgrade journal. It is NOT an authorization to debit a real bank.
 * A future adapter must derive both snapshots from native inventory, verify the
 * exact resource plan and implement the durable bank and inventory contracts.
 * The bank cursor is global to this source and must survive all later charges.
 */
public final class RodUpgradeJournal implements AutoCloseable {
    private static final int APPLICATION_ID = 1380930634;
    private static final int MAX_SNAPSHOT = 4 * 1024 * 1024;
    private final Connection db;
    private final UUID lineage;
    private final byte[] secret;
    public enum Status { MISSING, COMMITTED, UNAVAILABLE, CONFLICT }
    public enum Transfer { APPLIED, WAIT, CONFLICT }
    public enum Result { DONE, WAIT, CONFLICT }
    public record Charge(UUID lineage, long sequence, UUID transaction, UUID owner, long minor, String before, String after) {
        public Charge {
            if (lineage == null || sequence < 1 || sequence == Long.MAX_VALUE || transaction == null || owner == null
                    || minor < 1 || minor > 1_000_000 || before == null || after == null || before.equals(after)
                    || before.length() > MAX_SNAPSHOT || after.length() > MAX_SNAPSHOT) throw new IllegalArgumentException("charge");
        }
    }
    public interface Bank {
        /** Last committed source sequence, zero when registered but unused, -1 when unavailable. */
        long cursor(UUID lineage) throws Exception;
        /** COMMITTED only for this exact source, sequence, transaction, owner and amount.
         * MISSING is authoritative: no earlier charge call is still in flight. */
        Status status(Charge charge) throws Exception;
        /** Idempotent; a failed or throwing call can still have committed and must be rechecked. */
        void charge(Charge charge) throws Exception;
    }
    public interface Inventory {
        /** APPLIED requires storage and transaction witness to be persisted together. */
        Transfer recover(UUID owner, UUID transaction, String before, String after) throws Exception;
    }
    private record Pending(Charge charge, UUID rod, int previous, int next, String before, String after, String phase) { }

    public RodUpgradeJournal(Path path, UUID lineage, byte[] secret, boolean allowCreate) throws SQLException {
        this.lineage = Objects.requireNonNull(lineage);
        if (secret == null || secret.length != FishToken.KEY_BYTES) throw new IllegalArgumentException("rod key");
        this.secret = secret.clone();
        boolean fresh = false;
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (!allowCreate) throw new SQLException("rod journal missing; recovery required");
                Files.createFile(path); fresh = true;
            } else if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new SQLException("invalid rod journal file");
        } catch (java.io.IOException error) { throw new SQLException("rod journal creation", error); }
        db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (var s = db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000"); s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA synchronous=FULL");
            if (fresh) {
                db.setAutoCommit(false);
                try {
                    s.execute("CREATE TABLE metadata (id INTEGER PRIMARY KEY CHECK(id=1), lineage TEXT NOT NULL, cursor INTEGER NOT NULL CHECK(cursor>=0)) STRICT");
                    s.execute("CREATE TABLE rods (id TEXT PRIMARY KEY NOT NULL, level INTEGER NOT NULL CHECK(level BETWEEN 2 AND 10)) STRICT");
                    s.execute("CREATE TABLE pending (id INTEGER PRIMARY KEY CHECK(id=1), transaction_id TEXT NOT NULL, owner TEXT NOT NULL, sequence INTEGER NOT NULL, rod TEXT NOT NULL, previous INTEGER NOT NULL, next_level INTEGER NOT NULL, amount INTEGER NOT NULL, before_state TEXT NOT NULL, after_state TEXT NOT NULL, phase TEXT NOT NULL CHECK(phase IN ('PREPARED','PAID'))) STRICT");
                    try (var insert = db.prepareStatement("INSERT INTO metadata VALUES(1,?,0)")) { insert.setString(1, lineage.toString()); insert.executeUpdate(); }
                    s.execute("PRAGMA application_id=" + APPLICATION_ID); s.execute("PRAGMA user_version=1"); db.commit();
                } catch (SQLException error) { db.rollback(); throw error; }
                finally { db.setAutoCommit(true); }
            } else {
                try (var rs = s.executeQuery("PRAGMA application_id")) { if (!rs.next() || rs.getInt(1) != APPLICATION_ID) throw new SQLException("wrong rod journal identity"); }
                try (var rs = s.executeQuery("PRAGMA user_version")) { if (!rs.next() || rs.getInt(1) != 1) throw new SQLException("unsupported rod journal"); }
                try (var rs = s.executeQuery("PRAGMA quick_check")) { if (!rs.next() || !"ok".equals(rs.getString(1))) throw new SQLException("rod journal corrupt"); }
                s.executeQuery("SELECT id,level FROM rods LIMIT 0").close();
                s.executeQuery("SELECT transaction_id,owner,sequence,rod,previous,next_level,amount,before_state,after_state,phase FROM pending LIMIT 0").close();
                cursor(); pending();
            }
        } catch (SQLException error) { db.close(); java.util.Arrays.fill(this.secret, (byte) 0); throw error; }
    }
    private long cursor() throws SQLException {
        try (var s = db.createStatement(); var rs = s.executeQuery("SELECT lineage,cursor FROM metadata WHERE id=1")) {
            if (!rs.next() || !lineage.toString().equals(rs.getString(1)) || rs.getLong(2) < 0) throw new SQLException("rod lineage invalid");
            long cursor = rs.getLong(2); if (rs.next()) throw new SQLException("duplicate metadata"); return cursor;
        }
    }
    /** Owner of the sole in-flight transaction, if any. */
    public synchronized UUID pendingOwner() throws SQLException {
        Pending p = pending(); return p == null ? null : p.charge().owner();
    }
    /** Check source cursor before applying gameplay bonuses, including after an inconsistent restore. */
    public synchronized boolean ready(Bank bank) throws Exception {
        return pending() == null && bank.cursor(lineage) == cursor();
    }
    /** Returns true only for the latest recorded signed generation; clones of the same generation remain indistinguishable. */
    public synchronized boolean current(RodToken.Token token) throws SQLException {
        if (!RodToken.verify(secret, token)) return false;
        return level(token.id()) == token.level();
    }
    private int level(UUID id) throws SQLException {
        try (var s = db.prepareStatement("SELECT level FROM rods WHERE id=?")) {
            s.setString(1, id.toString());
            try (var rs = s.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }
    /** Persist intent first. Caller must have derived/validated the two snapshots and the quoted price. */
    public synchronized Result prepare(UUID owner, UUID transaction, RodToken.Token previous, RodToken.Token next,
                                       long minor, String before, String after, Bank bank) throws Exception {
        if (owner == null || transaction == null || bank == null || !RodToken.verify(secret, next)
                || before == null || after == null || before.equals(after) || before.length() > MAX_SNAPSHOT || after.length() > MAX_SNAPSHOT
                || minor < 1 || minor > 1_000_000) throw new IllegalArgumentException("rod upgrade intent");
        long cursor = cursor();
        if (cursor >= Long.MAX_VALUE - 1 || pending() != null || bank.cursor(lineage) != cursor) return Result.CONFLICT;
        int old = previous == null ? 1 : previous.level();
        if (previous == null ? level(next.id()) != 0 : !RodToken.verify(secret, previous) || !previous.id().equals(next.id()) || level(previous.id()) != old)
            return Result.CONFLICT;
        if (next.level() != old + 1) return Result.CONFLICT;
        try (var s = db.prepareStatement("INSERT INTO pending VALUES(1,?,?,?,?,?,?,?,?,?,'PREPARED')")) {
            s.setString(1, transaction.toString()); s.setString(2, owner.toString()); s.setLong(3, cursor + 1);
            s.setString(4, next.id().toString()); s.setInt(5, old); s.setInt(6, next.level()); s.setLong(7, minor);
            s.setString(8, before); s.setString(9, after); s.executeUpdate();
        }
        return Result.WAIT;
    }
    /** Cancel only after the bank authoritatively proves the debit never happened. */
    public synchronized Result cancelUnpaid(Bank bank) throws Exception {
        Objects.requireNonNull(bank);
        Pending p = pending();
        if (p == null) return bank.cursor(lineage) == cursor() ? Result.DONE : Result.CONFLICT;
        if (!p.phase().equals("PREPARED")) return Result.CONFLICT;
        long local = cursor(), remote = bank.cursor(lineage);
        if (remote < 0) return Result.WAIT;
        if (remote != local || p.charge().sequence() != local + 1) return Result.CONFLICT;
        Status status = bank.status(p.charge());
        if (status == null || status == Status.UNAVAILABLE) return Result.WAIT;
        if (status != Status.MISSING) return Result.CONFLICT;
        try (var s = db.prepareStatement("DELETE FROM pending WHERE id=1 AND transaction_id=? AND phase='PREPARED'")) {
            s.setString(1, p.charge().transaction().toString());
            return s.executeUpdate() == 1 ? Result.DONE : Result.CONFLICT;
        }
    }
    public synchronized Result recover(Bank bank, Inventory inventory) throws Exception {
        return recover(bank, inventory, true);
    }
    /** Existing intents must not be charged implicitly during background recovery. */
    public synchronized Result recover(Bank bank, Inventory inventory, boolean allowNewCharge) throws Exception {
        Objects.requireNonNull(bank); Objects.requireNonNull(inventory);
        Pending p = pending(); long local = cursor(), remote = bank.cursor(lineage);
        if (remote < 0) return Result.WAIT;
        if (p == null) return remote == local ? Result.DONE : Result.CONFLICT;
        if (p.charge().sequence() != local + 1 || (remote != local && remote != local + 1)) return Result.CONFLICT;
        Status status = bank.status(p.charge());
        if (status == null || status == Status.UNAVAILABLE) return Result.WAIT;
        if (status == Status.CONFLICT || (status == Status.COMMITTED && remote != local + 1)
                || (status == Status.MISSING && remote != local)) return Result.CONFLICT;
        if (p.phase().equals("PREPARED") && status == Status.MISSING) {
            if (!allowNewCharge) return Result.WAIT;
            bank.charge(p.charge());
            status = bank.status(p.charge()); remote = bank.cursor(lineage);
            if (status == Status.UNAVAILABLE || status == null || status == Status.MISSING) return Result.WAIT;
            if (status != Status.COMMITTED || remote != local + 1) return Result.CONFLICT;
        }
        if (status != Status.COMMITTED) return Result.CONFLICT;
        if (p.phase().equals("PREPARED")) {
            try (var s = db.prepareStatement("UPDATE pending SET phase='PAID' WHERE id=1 AND phase='PREPARED'")) {
                if (s.executeUpdate() != 1) return Result.CONFLICT;
            }
        }
        Transfer transfer = inventory.recover(p.charge().owner(), p.charge().transaction(), p.before(), p.after());
        if (transfer == null || transfer == Transfer.CONFLICT) return Result.CONFLICT;
        if (transfer == Transfer.WAIT) return Result.WAIT;
        // Update rod generation and source sequence atomically, only after durable transfer.
        db.setAutoCommit(false);
        try {
            if (p.previous() == 1) {
                try (var s = db.prepareStatement("INSERT INTO rods VALUES(?,?)")) { s.setString(1, p.rod().toString()); s.setInt(2, p.next()); s.executeUpdate(); }
            } else {
                try (var s = db.prepareStatement("UPDATE rods SET level=? WHERE id=? AND level=?")) {
                    s.setInt(1, p.next()); s.setString(2, p.rod().toString()); s.setInt(3, p.previous());
                    if (s.executeUpdate() != 1) throw new SQLException("rod identity conflict");
                }
            }
            try (var s = db.prepareStatement("UPDATE metadata SET cursor=? WHERE id=1 AND cursor=?")) {
                s.setLong(1, p.charge().sequence()); s.setLong(2, local); if (s.executeUpdate() != 1) throw new SQLException("rod cursor conflict");
            }
            try (var s = db.prepareStatement("DELETE FROM pending WHERE id=1 AND transaction_id=? AND phase='PAID'")) {
                s.setString(1, p.charge().transaction().toString()); if (s.executeUpdate() != 1) throw new SQLException("rod pending conflict");
            }
            db.commit(); return Result.DONE;
        } catch (SQLException error) { db.rollback(); throw error; }
        finally { db.setAutoCommit(true); }
    }
    private Pending pending() throws SQLException {
        try (var s = db.createStatement(); var rs = s.executeQuery("SELECT transaction_id,owner,sequence,rod,previous,next_level,amount,before_state,after_state,phase FROM pending WHERE id=1")) {
            if (!rs.next()) return null;
            try {
                UUID transaction = parse(rs.getString(1)), owner = parse(rs.getString(2)), rod = parse(rs.getString(4));
                int previous = rs.getInt(5), next = rs.getInt(6); String before = rs.getString(8), after = rs.getString(9), phase = rs.getString(10);
                var charge = new Charge(lineage, rs.getLong(3), transaction, owner, rs.getLong(7), before, after);
                if (previous < 1 || next != previous + 1 || next > 10 || before == null || after == null || before.equals(after)
                        || before.length() > MAX_SNAPSHOT || after.length() > MAX_SNAPSHOT || !("PREPARED".equals(phase) || "PAID".equals(phase)))
                    throw new IllegalArgumentException("pending state");
                return new Pending(charge, rod, previous, next, before, after, phase);
            } catch (RuntimeException invalid) { throw new SQLException("invalid rod pending", invalid); }
        }
    }
    private static UUID parse(String raw) {
        UUID id = UUID.fromString(raw); if (!id.toString().equals(raw)) throw new IllegalArgumentException("UUID"); return id;
    }
    @Override public synchronized void close() throws SQLException {
        try { db.close(); } finally { java.util.Arrays.fill(secret, (byte) 0); }
    }
}
