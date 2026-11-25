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
 * Unconnected, single-flight purchase journal. A real adapter must derive and
 * revalidate native snapshots, register a dedicated bank lineage and use an
 * inventory witness. Issued IDs alone do not make bait consumption safe.
 */
public final class BaitPurchaseJournal implements AutoCloseable {
    private static final int APPLICATION_ID = 1111585859, MAX_SNAPSHOT = 4 * 1024 * 1024;
    private final Connection db;
    private final UUID lineage;
    private final byte[] key;
    private record Pending(RodUpgradeJournal.Charge charge, UUID baitId, String kind, String phase) { }

    public BaitPurchaseJournal(Path path, UUID lineage, byte[] key, boolean allowCreate) throws SQLException {
        this.lineage = Objects.requireNonNull(lineage);
        if (key == null || key.length != FishToken.KEY_BYTES) throw new IllegalArgumentException("bait signing key");
        this.key = key.clone();
        boolean fresh = false;
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (!allowCreate) throw new SQLException("bait journal missing; recovery required");
                Files.createFile(path); fresh = true;
            } else if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new SQLException("invalid bait journal file");
        } catch (java.io.IOException error) { throw new SQLException("bait journal creation", error); }
        db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (var s = db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000"); s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA synchronous=FULL");
            if (fresh) {
                db.setAutoCommit(false);
                try {
                    s.execute("CREATE TABLE metadata (id INTEGER PRIMARY KEY CHECK(id=1), lineage TEXT NOT NULL, cursor INTEGER NOT NULL CHECK(cursor>=0)) STRICT");
                    s.execute("CREATE TABLE issued (id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('AVAILABLE','USED'))) STRICT");
                    s.execute("CREATE TABLE pending (id INTEGER PRIMARY KEY CHECK(id=1), transaction_id TEXT NOT NULL, owner TEXT NOT NULL, sequence INTEGER NOT NULL, bait_id TEXT NOT NULL, kind TEXT NOT NULL, amount INTEGER NOT NULL, before_state TEXT NOT NULL, after_state TEXT NOT NULL, phase TEXT NOT NULL CHECK(phase IN ('PREPARED','PAID'))) STRICT");
                    s.execute("CREATE TABLE equipped (owner TEXT PRIMARY KEY NOT NULL, transaction_id TEXT NOT NULL UNIQUE, bait_id TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, before_state TEXT NOT NULL, after_state TEXT NOT NULL, phase TEXT NOT NULL CHECK(phase IN ('PREPARED','ARMED','RESERVED')), attempt_id TEXT) STRICT");
                    try (var i = db.prepareStatement("INSERT INTO metadata VALUES(1,?,0)")) { i.setString(1, lineage.toString()); i.executeUpdate(); }
                    s.execute("PRAGMA application_id=" + APPLICATION_ID); s.execute("PRAGMA user_version=2"); db.commit();
                } catch (SQLException error) { db.rollback(); throw error; }
                finally { db.setAutoCommit(true); }
            } else {
                try (var r = s.executeQuery("PRAGMA application_id")) { if (!r.next() || r.getInt(1) != APPLICATION_ID) throw new SQLException("wrong bait journal identity"); }
                int version;
                try (var r = s.executeQuery("PRAGMA user_version")) {
                    if (!r.next() || (r.getInt(1) != 1 && r.getInt(1) != 2)) throw new SQLException("unsupported bait journal");
                    version = r.getInt(1);
                }
                try (var r = s.executeQuery("PRAGMA quick_check")) { if (!r.next() || !"ok".equals(r.getString(1))) throw new SQLException("bait journal corrupt"); }
                s.executeQuery("SELECT id,kind,state FROM issued LIMIT 0").close();
                s.executeQuery("SELECT transaction_id,owner,sequence,bait_id,kind,amount,before_state,after_state,phase FROM pending LIMIT 0").close();
                if (version == 1) {
                    db.setAutoCommit(false);
                    try {
                        s.execute("CREATE TABLE IF NOT EXISTS equipped (owner TEXT PRIMARY KEY NOT NULL, transaction_id TEXT NOT NULL UNIQUE, bait_id TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, before_state TEXT NOT NULL, after_state TEXT NOT NULL, phase TEXT NOT NULL CHECK(phase IN ('PREPARED','ARMED','RESERVED')), attempt_id TEXT) STRICT");
                        s.executeQuery("SELECT owner,transaction_id,bait_id,kind,before_state,after_state,phase,attempt_id FROM equipped LIMIT 0").close();
                        s.execute("PRAGMA user_version=2"); db.commit();
                    } catch (SQLException error) { db.rollback(); throw error; }
                    finally { db.setAutoCommit(true); }
                }
                s.executeQuery("SELECT owner,transaction_id,bait_id,kind,before_state,after_state,phase,attempt_id FROM equipped LIMIT 0").close();
                cursor(); pending();
            }
        } catch (SQLException error) { db.close(); java.util.Arrays.fill(this.key, (byte) 0); throw error; }
    }
    private long cursor() throws SQLException {
        try (var s = db.createStatement(); var r = s.executeQuery("SELECT lineage,cursor FROM metadata WHERE id=1")) {
            if (!r.next() || !lineage.toString().equals(r.getString(1)) || r.getLong(2) < 0) throw new SQLException("bait lineage invalid");
            return r.getLong(2);
        }
    }
    public synchronized UUID pendingOwner() throws SQLException { var p = pending(); return p == null ? null : p.charge().owner(); }
    public synchronized boolean available(BaitToken.Token token) throws SQLException {
        if (!BaitToken.verify(key, token)) return false;
        try (var s = db.prepareStatement("SELECT kind,state FROM issued WHERE id=?")) {
            s.setString(1, token.id().toString());
            try (var r = s.executeQuery()) { return r.next() && token.kind().equals(r.getString(1)) && "AVAILABLE".equals(r.getString(2)); }
        }
    }
    public record Equipped(UUID owner, UUID transaction, UUID baitId, String kind, String before, String after,
                           String phase, UUID attempt) { }
    /** PREPARED/RESERVED entries require recovery or manual review; they never apply a bonus. */
    public synchronized Equipped equipped(UUID owner) throws SQLException {
        Objects.requireNonNull(owner);
        try (var s = db.prepareStatement("SELECT owner,transaction_id,bait_id,kind,before_state,after_state,phase,attempt_id FROM equipped WHERE owner=?")) {
            s.setString(1, owner.toString());
            try (var r = s.executeQuery()) {
                if (!r.next()) return null;
                try {
                    UUID who = uuid(r.getString(1)), tx = uuid(r.getString(2)), bait = uuid(r.getString(3));
                    String kind = r.getString(4), before = r.getString(5), after = r.getString(6), phase = r.getString(7), rawAttempt = r.getString(8);
                    UUID attempt = rawAttempt == null ? null : uuid(rawAttempt);
                    if (!owner.equals(who) || BaitCatalog.byId(kind) == null || before == null || after == null
                            || before.equals(after) || before.length() > MAX_SNAPSHOT || after.length() > MAX_SNAPSHOT
                            || !("PREPARED".equals(phase) || "ARMED".equals(phase) || "RESERVED".equals(phase))
                            || ("RESERVED".equals(phase) != (attempt != null))) throw new IllegalArgumentException("equipped bait");
                    return new Equipped(who, tx, bait, kind, before, after, phase, attempt);
                } catch (RuntimeException invalid) { throw new SQLException("invalid equipped bait", invalid); }
            }
        }
    }
    /**
     * Reserve a unique issued token before removing it from native inventory.
     * Caller must derive and verify snapshots from actual storage. No bait can be
     * reissued if transfer outcome is uncertain. Bank/source cursor must agree.
     */
    public synchronized RodUpgradeJournal.Result prepareEquip(UUID owner, UUID tx, BaitToken.Token token,
            String before, String after, RodUpgradeJournal.Bank bank) throws Exception {
        if (owner == null || tx == null || bank == null || !BaitToken.verify(key, token) || before == null || after == null
                || before.equals(after) || before.length() > MAX_SNAPSHOT || after.length() > MAX_SNAPSHOT)
            throw new IllegalArgumentException("bait equip intent");
        long remote = bank.cursor(lineage);
        if (remote < 0) return RodUpgradeJournal.Result.WAIT;
        if (remote != cursor() || pending() != null || equipped(owner) != null || !available(token)) return RodUpgradeJournal.Result.CONFLICT;
        db.setAutoCommit(false);
        try {
            try (var s = db.prepareStatement("UPDATE issued SET state='USED' WHERE id=? AND kind=? AND state='AVAILABLE'")) {
                s.setString(1, token.id().toString()); s.setString(2, token.kind());
                if (s.executeUpdate() != 1) throw new SQLException("bait identity conflict");
            }
            try (var s = db.prepareStatement("INSERT INTO equipped VALUES(?,?,?,?,?,?,'PREPARED',NULL)")) {
                s.setString(1, owner.toString()); s.setString(2, tx.toString()); s.setString(3, token.id().toString());
                s.setString(4, token.kind()); s.setString(5, before); s.setString(6, after); s.executeUpdate();
            }
            db.commit(); return RodUpgradeJournal.Result.WAIT;
        } catch (SQLException error) {
            db.rollback(); if (error.getErrorCode() == 19) return RodUpgradeJournal.Result.CONFLICT; throw error;
        } finally { db.setAutoCommit(true); }
    }
    public synchronized RodUpgradeJournal.Result recoverEquip(UUID owner, RodUpgradeJournal.Bank bank,
                                                               RodUpgradeJournal.Inventory inventory) throws Exception {
        Objects.requireNonNull(bank); Objects.requireNonNull(inventory);
        Equipped item = equipped(owner); long remote = bank.cursor(lineage);
        if (remote < 0) return RodUpgradeJournal.Result.WAIT;
        if (remote != cursor() || pending() != null) return RodUpgradeJournal.Result.CONFLICT;
        if (item == null || "ARMED".equals(item.phase())) return RodUpgradeJournal.Result.DONE;
        if (!"PREPARED".equals(item.phase())) return RodUpgradeJournal.Result.CONFLICT;
        var moved = inventory.recover(owner, item.transaction(), item.before(), item.after());
        if (moved == null || moved == RodUpgradeJournal.Transfer.CONFLICT) return RodUpgradeJournal.Result.CONFLICT;
        if (moved == RodUpgradeJournal.Transfer.WAIT) return RodUpgradeJournal.Result.WAIT;
        try (var s = db.prepareStatement("UPDATE equipped SET phase='ARMED' WHERE owner=? AND transaction_id=? AND phase='PREPARED'")) {
            s.setString(1, owner.toString()); s.setString(2, item.transaction().toString());
            return s.executeUpdate() == 1 ? RodUpgradeJournal.Result.DONE : RodUpgradeJournal.Result.CONFLICT;
        }
    }
    /** Reserve just before applying a bonus; crashes leave RESERVED quarantined, never reusable automatically. */
    public synchronized BaitCatalog.Bait reserveAttempt(UUID owner, UUID attempt, RodUpgradeJournal.Bank bank) throws Exception {
        if (owner == null || attempt == null || bank == null) throw new IllegalArgumentException("bait attempt");
        if (bank.cursor(lineage) != cursor() || pending() != null) return null;
        Equipped item = equipped(owner); if (item == null || !"ARMED".equals(item.phase())) return null;
        try (var s = db.prepareStatement("UPDATE equipped SET phase='RESERVED',attempt_id=? WHERE owner=? AND phase='ARMED'")) {
            s.setString(1, attempt.toString()); s.setString(2, owner.toString());
            return s.executeUpdate() == 1 ? BaitCatalog.byId(item.kind()) : null;
        }
    }
    /** Cancellation rearms the same bait; only a final successful event consumes it. */
    public synchronized boolean finishAttempt(UUID owner, UUID attempt, boolean success) throws SQLException {
        if (owner == null || attempt == null) throw new IllegalArgumentException("bait attempt");
        String sql = success ? "DELETE FROM equipped WHERE owner=? AND phase='RESERVED' AND attempt_id=?"
                : "UPDATE equipped SET phase='ARMED',attempt_id=NULL WHERE owner=? AND phase='RESERVED' AND attempt_id=?";
        try (var s = db.prepareStatement(sql)) {
            s.setString(1, owner.toString()); s.setString(2, attempt.toString()); return s.executeUpdate() == 1;
        }
    }

    /** Caller must verify the exact output item and snapshots from native inventory. */
    public synchronized RodUpgradeJournal.Result prepare(UUID owner, UUID transaction, BaitToken.Token token,
                                                          String before, String after, RodUpgradeJournal.Bank bank) throws Exception {
        if (owner == null || transaction == null || bank == null || !BaitToken.verify(key, token)
                || before == null || after == null || before.equals(after) || before.length() > MAX_SNAPSHOT || after.length() > MAX_SNAPSHOT)
            throw new IllegalArgumentException("bait purchase");
        long local = cursor(), remote = bank.cursor(lineage);
        if (remote < 0) return RodUpgradeJournal.Result.WAIT;
        if (local >= Long.MAX_VALUE - 1 || remote != local || pending() != null || available(token)) return RodUpgradeJournal.Result.CONFLICT;
        try (var query = db.prepareStatement("SELECT 1 FROM issued WHERE id=?")) {
            query.setString(1, token.id().toString()); try (var r = query.executeQuery()) { if (r.next()) return RodUpgradeJournal.Result.CONFLICT; }
        }
        try (var s = db.prepareStatement("INSERT INTO pending VALUES(1,?,?,?,?,?,?,?,?, 'PREPARED')")) {
            s.setString(1, transaction.toString()); s.setString(2, owner.toString()); s.setLong(3, local + 1);
            s.setString(4, token.id().toString()); s.setString(5, token.kind()); s.setLong(6, BaitCatalog.byId(token.kind()).priceMinor());
            s.setString(7, before); s.setString(8, after); s.executeUpdate();
        } catch (SQLException error) { if (error.getErrorCode() == 19) return RodUpgradeJournal.Result.CONFLICT; throw error; }
        return RodUpgradeJournal.Result.WAIT;
    }
    /** Cancels only while the bank authoritatively reports no debit. */
    public synchronized RodUpgradeJournal.Result cancelUnpaid(RodUpgradeJournal.Bank bank) throws Exception {
        Objects.requireNonNull(bank);
        Pending p = pending(); long local = cursor(), remote = bank.cursor(lineage);
        if (remote < 0) return RodUpgradeJournal.Result.WAIT;
        if (p == null) return remote == local ? RodUpgradeJournal.Result.DONE : RodUpgradeJournal.Result.CONFLICT;
        if (!p.phase().equals("PREPARED") || remote != local || p.charge().sequence() != local + 1)
            return RodUpgradeJournal.Result.CONFLICT;
        var status = bank.status(p.charge());
        if (status == null || status == RodUpgradeJournal.Status.UNAVAILABLE) return RodUpgradeJournal.Result.WAIT;
        if (status != RodUpgradeJournal.Status.MISSING) return RodUpgradeJournal.Result.CONFLICT;
        try (var s = db.prepareStatement("DELETE FROM pending WHERE id=1 AND transaction_id=? AND phase='PREPARED'")) {
            s.setString(1, p.charge().transaction().toString());
            return s.executeUpdate() == 1 ? RodUpgradeJournal.Result.DONE : RodUpgradeJournal.Result.CONFLICT;
        }
    }
    /** Only an immediately confirmed, newly prepared purchase may initiate a debit. */
    public synchronized RodUpgradeJournal.Result recover(RodUpgradeJournal.Bank bank, RodUpgradeJournal.Inventory inventory,
                                                          boolean allowNewCharge) throws Exception {
        Objects.requireNonNull(bank); Objects.requireNonNull(inventory);
        Pending p = pending(); long local = cursor(), remote = bank.cursor(lineage);
        if (remote < 0) return RodUpgradeJournal.Result.WAIT;
        if (p == null) return remote == local ? RodUpgradeJournal.Result.DONE : RodUpgradeJournal.Result.CONFLICT;
        if (p.charge().sequence() != local + 1 || (remote != local && remote != local + 1)) return RodUpgradeJournal.Result.CONFLICT;
        var status = bank.status(p.charge());
        if (status == null || status == RodUpgradeJournal.Status.UNAVAILABLE) return RodUpgradeJournal.Result.WAIT;
        if (status == RodUpgradeJournal.Status.CONFLICT || (status == RodUpgradeJournal.Status.COMMITTED && remote != local + 1)
                || (status == RodUpgradeJournal.Status.MISSING && remote != local)) return RodUpgradeJournal.Result.CONFLICT;
        if (p.phase().equals("PREPARED") && status == RodUpgradeJournal.Status.MISSING) {
            if (!allowNewCharge) return RodUpgradeJournal.Result.WAIT;
            bank.charge(p.charge()); status = bank.status(p.charge()); remote = bank.cursor(lineage);
            if (status == null || status == RodUpgradeJournal.Status.UNAVAILABLE || status == RodUpgradeJournal.Status.MISSING) return RodUpgradeJournal.Result.WAIT;
            if (status != RodUpgradeJournal.Status.COMMITTED || remote != local + 1) return RodUpgradeJournal.Result.CONFLICT;
        }
        if (status != RodUpgradeJournal.Status.COMMITTED) return RodUpgradeJournal.Result.CONFLICT;
        if (p.phase().equals("PREPARED")) try (var s = db.prepareStatement("UPDATE pending SET phase='PAID' WHERE id=1 AND phase='PREPARED'")) {
            if (s.executeUpdate() != 1) return RodUpgradeJournal.Result.CONFLICT;
        }
        var moved = inventory.recover(p.charge().owner(), p.charge().transaction(), p.charge().before(), p.charge().after());
        if (moved == null || moved == RodUpgradeJournal.Transfer.CONFLICT) return RodUpgradeJournal.Result.CONFLICT;
        if (moved == RodUpgradeJournal.Transfer.WAIT) return RodUpgradeJournal.Result.WAIT;
        db.setAutoCommit(false);
        try {
            try (var s = db.prepareStatement("INSERT INTO issued VALUES(?,?,'AVAILABLE')")) { s.setString(1, p.baitId().toString()); s.setString(2, p.kind()); s.executeUpdate(); }
            try (var s = db.prepareStatement("UPDATE metadata SET cursor=? WHERE id=1 AND cursor=?")) {
                s.setLong(1, p.charge().sequence()); s.setLong(2, local); if (s.executeUpdate() != 1) throw new SQLException("bait cursor conflict");
            }
            try (var s = db.prepareStatement("DELETE FROM pending WHERE id=1 AND transaction_id=? AND phase='PAID'")) {
                s.setString(1, p.charge().transaction().toString()); if (s.executeUpdate() != 1) throw new SQLException("bait pending conflict");
            }
            db.commit(); return RodUpgradeJournal.Result.DONE;
        } catch (SQLException error) { db.rollback(); throw error; }
        finally { db.setAutoCommit(true); }
    }
    private Pending pending() throws SQLException {
        try (var s = db.createStatement(); var r = s.executeQuery("SELECT transaction_id,owner,sequence,bait_id,kind,amount,before_state,after_state,phase FROM pending WHERE id=1")) {
            if (!r.next()) return null;
            try {
                UUID tx = uuid(r.getString(1)), owner = uuid(r.getString(2)), baitId = uuid(r.getString(4));
                String kind = r.getString(5), before = r.getString(7), after = r.getString(8), phase = r.getString(9);
                if (BaitCatalog.byId(kind) == null || !("PREPARED".equals(phase) || "PAID".equals(phase))) throw new IllegalArgumentException("pending bait");
                var charge = new RodUpgradeJournal.Charge(lineage, r.getLong(3), tx, owner, r.getLong(6), before, after);
                return new Pending(charge, baitId, kind, phase);
            } catch (RuntimeException invalid) { throw new SQLException("invalid bait pending", invalid); }
        }
    }
    private static UUID uuid(String raw) { UUID result = UUID.fromString(raw); if (!result.toString().equals(raw)) throw new IllegalArgumentException("UUID"); return result; }
    @Override public synchronized void close() throws SQLException {
        try { db.close(); } finally { java.util.Arrays.fill(key, (byte) 0); }
    }
}
