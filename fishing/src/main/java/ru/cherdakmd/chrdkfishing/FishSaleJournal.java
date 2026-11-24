package ru.cherdakmd.chrdkfishing;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Experimental sale journal, NOT wired into the server.
 *
 * A Bukkit adapter must derive before/after from native inventory itself, check
 * every signed token's presence and amount, and remove exactly those items. An
 * APPLIED result must mean playerdata and transaction witness were checkpointed
 * together, including on retry. Bank receipts must be durable and idempotent.
 * Backups/restores must include the bank, this database (including WAL) and
 * playerdata at a consistent point. Never replace a lost or corrupt journal.
 */
public final class FishSaleJournal implements AutoCloseable {
    private static final String PREFIX = "fishing:sale:";
    private static final int MAX_SNAPSHOT = 4 * 1024 * 1024;
    private static final long MAX_CREDIT = 1_000_000;
    private static final int APPLICATION_ID = 1179206483;
    private static final int SCHEMA_VERSION = 1;
    private static final String ACTIVE_INDEX = "CREATE UNIQUE INDEX active_sale_owner ON sales(owner) WHERE phase <> 'PAID'";
    private final Connection db;

    public enum TransferResult { APPLIED, WAIT, CONFLICT }
    public enum Result { DONE, WAIT, CONFLICT }
    public interface Inventory {
        /** Same transaction must not remove items twice. APPLIED means durable native witness. */
        TransferResult recover(UUID owner, UUID transaction, String before, String after) throws Exception;
    }
    public interface Bank {
        /** Immutable snapshot of all retained receipts for this owner, not just pending payments. */
        Map<String, Long> receipts(UUID owner) throws Exception;
        /** Idempotent on (owner, key), same key with a different amount must be rejected. */
        boolean accept(UUID owner, String key, long amount) throws Exception;
    }
    private record Sale(UUID id, UUID owner, String before, String after, long amount, String phase) { }

    /** Open an existing journal or create one for an explicitly verified new installation only. */
    public FishSaleJournal(Path path, boolean allowCreate) throws SQLException {
        Objects.requireNonNull(path, "path");
        boolean fresh = false;
        try {
            if (!java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (!allowCreate) throw new SQLException("sale journal missing; recovery required");
                java.nio.file.Files.createFile(path);
                fresh = true;
            } else if (!java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new SQLException("sale journal is not a regular file");
            }
        } catch (java.io.IOException error) { throw new SQLException("cannot create sale journal", error); }
        db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=FULL");
            if (fresh) {
                db.setAutoCommit(false);
                try {
                    s.execute("CREATE TABLE sales (id TEXT PRIMARY KEY, owner TEXT NOT NULL, before_state TEXT NOT NULL, after_state TEXT NOT NULL, amount INTEGER NOT NULL CHECK(amount BETWEEN 1 AND 1000000), phase TEXT NOT NULL CHECK(phase IN ('PREPARED','REMOVED','PAID'))) ");
                    s.execute(ACTIVE_INDEX);
                    s.execute("CREATE TABLE spent_fish (fish_id TEXT PRIMARY KEY, sale_id TEXT NOT NULL REFERENCES sales(id))");
                    s.execute("PRAGMA application_id=" + APPLICATION_ID);
                    s.execute("PRAGMA user_version=" + SCHEMA_VERSION);
                    db.commit();
                } catch (SQLException error) { db.rollback(); throw error; }
                finally { db.setAutoCommit(true); }
            } else {
                try (ResultSet rs = s.executeQuery("PRAGMA application_id")) {
                    if (!rs.next() || rs.getInt(1) != APPLICATION_ID) throw new SQLException("wrong sale journal identity");
                }
                try (ResultSet rs = s.executeQuery("PRAGMA user_version")) {
                    if (!rs.next() || rs.getInt(1) != SCHEMA_VERSION) throw new SQLException("unsupported sale journal schema");
                }
                try (ResultSet rs = s.executeQuery("PRAGMA quick_check")) {
                    if (!rs.next() || !"ok".equals(rs.getString(1))) throw new SQLException("sale journal corrupt");
                }
                try (ResultSet rs = s.executeQuery("SELECT sql FROM sqlite_master WHERE type='index' AND name='active_sale_owner'")) {
                    if (!rs.next() || !ACTIVE_INDEX.equals(rs.getString(1))) throw new SQLException("sale journal index mismatch");
                }
                s.executeQuery("SELECT id,owner,before_state,after_state,amount,phase FROM sales LIMIT 0").close();
                s.executeQuery("SELECT fish_id,sale_id FROM spent_fish LIMIT 0").close();
                try (ResultSet rs = s.executeQuery("PRAGMA foreign_key_check")) {
                    if (rs.next()) throw new SQLException("sale journal foreign key mismatch");
                }
                try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM sales s WHERE NOT EXISTS (SELECT 1 FROM spent_fish f WHERE f.sale_id=s.id)")) {
                    if (!rs.next() || rs.getLong(1) != 0) throw new SQLException("sale journal missing items");
                }
            }
        } catch (SQLException error) {
            db.close();
            throw error;
        }
    }

    public static String receiptKey(UUID id) { return PREFIX + Objects.requireNonNull(id); }

    /**
     * Persist one frozen transaction before touching native inventory.
     * Caller must verify that exactly these tokens occur once in BEFORE and that
     * AFTER removes only those items. This requires real item decoding, not lore.
     */
    public synchronized Result prepare(UUID owner, UUID transaction, String before, String after,
                                       List<FishToken.Token> fish, byte[] secret, Bank bank) throws Exception {
        if (owner == null || transaction == null || before == null || after == null || before.equals(after)
                || before.length() > MAX_SNAPSHOT || after.length() > MAX_SNAPSHOT || fish == null || fish.isEmpty()
                || fish.size() > 36 || bank == null) throw new IllegalArgumentException("sale intent");
        Set<UUID> ids = new HashSet<>();
        long amount = 0;
        for (var token : fish) {
            if (token == null || !FishToken.verify(secret, token) || !ids.add(token.id())) return Result.CONFLICT;
            amount = Math.addExact(amount, token.quotedPrice());
            if (amount > MAX_CREDIT) return Result.CONFLICT;
        }
        if (!historyMatches(owner, bank.receipts(owner))) return Result.CONFLICT;
        db.setAutoCommit(false);
        try {
            try (var s = db.prepareStatement("INSERT INTO sales(id,owner,before_state,after_state,amount,phase) VALUES(?,?,?,?,?,'PREPARED')")) {
                s.setString(1, transaction.toString()); s.setString(2, owner.toString()); s.setString(3, before);
                s.setString(4, after); s.setLong(5, amount); s.executeUpdate();
            }
            try (var s = db.prepareStatement("INSERT INTO spent_fish(fish_id,sale_id) VALUES(?,?)")) {
                for (UUID id : ids) { s.setString(1, id.toString()); s.setString(2, transaction.toString()); s.addBatch(); }
                s.executeBatch();
            }
            db.commit();
            return Result.WAIT;
        } catch (SQLException error) {
            db.rollback();
            if (error.getErrorCode() == 19) return Result.CONFLICT;
            throw error;
        } finally { db.setAutoCommit(true); }
    }

    /** Reentrant recovery; no payout happens before durable removal is witnessed. */
    public synchronized Result recover(UUID owner, UUID transaction, Inventory inventory, Bank bank) throws Exception {
        if (owner == null || transaction == null || inventory == null || bank == null) throw new IllegalArgumentException("sale recovery");
        Sale sale = find(transaction);
        if (sale == null || !sale.owner().equals(owner)) return Result.CONFLICT;
        if (!historyMatches(owner, bank.receipts(owner))) return Result.CONFLICT;
        if (sale.phase().equals("PAID")) return Result.DONE;
        if (sale.phase().equals("PREPARED")) {
            TransferResult moved = inventory.recover(owner, transaction, sale.before(), sale.after());
            if (moved == TransferResult.CONFLICT || moved == null) return Result.CONFLICT;
            if (moved != TransferResult.APPLIED) return Result.WAIT;
            if (!transition(transaction, "PREPARED", "REMOVED")) return Result.WAIT;
        }
        String key = receiptKey(transaction);
        if (!bank.accept(owner, key, sale.amount())) return Result.WAIT;
        Map<String, Long> receipts = bank.receipts(owner);
        if (receipts == null || !Long.valueOf(sale.amount()).equals(receipts.get(key)) || !historyMatches(owner, receipts)) return Result.CONFLICT;
        return transition(transaction, "REMOVED", "PAID") ? Result.DONE : Result.WAIT;
    }

    /** Find durable in-flight work after reconnect/restart; null means no active sale. */
    public synchronized UUID pending(UUID owner) throws SQLException {
        Objects.requireNonNull(owner, "owner");
        try (var query = db.prepareStatement("SELECT id FROM sales WHERE owner=? AND phase <> 'PAID'")) {
            query.setString(1, owner.toString());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return null;
                String raw = rows.getString(1);
                try {
                    UUID id = UUID.fromString(raw);
                    if (!id.toString().equals(raw) || rows.next()) throw new IllegalArgumentException("sale id");
                    return id;
                } catch (RuntimeException error) { throw new SQLException("invalid pending sale", error); }
            }
        }
    }

    private boolean historyMatches(UUID owner, Map<String, Long> receipts) throws SQLException {
        if (receipts == null) return false;
        Map<String, Sale> sales = new HashMap<>();
        try (var s = db.prepareStatement("SELECT id,owner,before_state,after_state,amount,phase FROM sales WHERE owner=?")) {
            s.setString(1, owner.toString());
            try (var rs = s.executeQuery()) { while (rs.next()) { Sale sale = read(rs); sales.put(receiptKey(sale.id()), sale); } }
        }
        for (var receipt : receipts.entrySet()) {
            if (receipt.getKey() == null) return false;
            if (!receipt.getKey().startsWith(PREFIX)) continue;
            Sale sale = sales.get(receipt.getKey());
            if (sale == null || sale.phase().equals("PREPARED") || !Long.valueOf(sale.amount()).equals(receipt.getValue())) return false;
        }
        for (var entry : sales.entrySet()) {
            if (entry.getValue().phase().equals("PAID") && !Long.valueOf(entry.getValue().amount()).equals(receipts.get(entry.getKey()))) return false;
        }
        return true;
    }
    private Sale find(UUID id) throws SQLException {
        try (var s = db.prepareStatement("SELECT id,owner,before_state,after_state,amount,phase FROM sales WHERE id=?")) {
            s.setString(1, id.toString());
            try (var rs = s.executeQuery()) { return rs.next() ? read(rs) : null; }
        }
    }
    private static Sale read(ResultSet rs) throws SQLException {
        try { return new Sale(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6)); }
        catch (RuntimeException invalid) { throw new SQLException("sale journal corrupt", invalid); }
    }
    private boolean transition(UUID id, String from, String to) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("UPDATE sales SET phase=? WHERE id=? AND phase=?")) {
            s.setString(1, to); s.setString(2, id.toString()); s.setString(3, from); return s.executeUpdate() == 1;
        }
    }
    @Override public synchronized void close() throws SQLException { db.close(); }
}
