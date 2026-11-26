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
 * Experimental shared sale/cooking journal, wired only into the dev bootstrap.
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
    private static final int SCHEMA_VERSION = 5;
    private static final String FIND_INDEX = "CREATE UNIQUE INDEX active_find_owner ON find_rewards(owner) WHERE phase <> 'DONE'";
    private static final String COOK_INDEX = "CREATE UNIQUE INDEX active_cook_owner ON cooked_fish(owner) WHERE phase <> 'DONE'";
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
                    createCookingSchema(s);
                    createOrderSchema(s);
                    createFindSchema(s);
                    createVanillaSchema(s);
                    s.execute("PRAGMA application_id=" + APPLICATION_ID);
                    s.execute("PRAGMA user_version=" + SCHEMA_VERSION);
                    db.commit();
                } catch (SQLException error) { db.rollback(); throw error; }
                finally { db.setAutoCommit(true); }
            } else {
                try (ResultSet rs = s.executeQuery("PRAGMA application_id")) {
                    if (!rs.next() || rs.getInt(1) != APPLICATION_ID) throw new SQLException("wrong sale journal identity");
                }
                int version;
                try (ResultSet rs = s.executeQuery("PRAGMA user_version")) {
                    if (!rs.next()) throw new SQLException("missing sale journal version");
                    version = rs.getInt(1);
                    if (version < 1 || version > SCHEMA_VERSION) throw new SQLException("unsupported sale journal schema");
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
                if (version < 5) try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM sales s WHERE NOT EXISTS (SELECT 1 FROM spent_fish f WHERE f.sale_id=s.id)")) {
                    if (!rs.next() || rs.getLong(1) != 0) throw new SQLException("sale journal missing items");
                }
                if (version < SCHEMA_VERSION) {
                    db.setAutoCommit(false);
                    try {
                        if (version == 1) createCookingSchema(s);
                        if (version < 3) createOrderSchema(s);
                        if (version < 4) createFindSchema(s);
                        if (version < 5) createVanillaSchema(s);
                        s.execute("PRAGMA user_version=" + SCHEMA_VERSION); db.commit();
                    }
                    catch (SQLException error) { db.rollback(); throw error; }
                    finally { db.setAutoCommit(true); }
                }
                try (ResultSet rs = s.executeQuery("SELECT sql FROM sqlite_master WHERE type='index' AND name='active_cook_owner'")) {
                    if (!rs.next() || !COOK_INDEX.equals(rs.getString(1))) throw new SQLException("cooking journal index mismatch");
                }
                s.executeQuery("SELECT fish_id,id,owner,before_state,after_state,phase FROM cooked_fish LIMIT 0").close();
                s.executeQuery("SELECT owner,order_day,order_slot,sale_id FROM order_claims LIMIT 0").close();
                try (var rs = s.executeQuery("SELECT sql FROM sqlite_master WHERE type='index' AND name='active_find_owner'")) {
                    if (!rs.next() || !FIND_INDEX.equals(rs.getString(1))) throw new SQLException("find journal index mismatch");
                }
                s.executeQuery("SELECT fish_id,id,owner,before_state,after_state,phase FROM find_rewards LIMIT 0").close();
                s.executeQuery("SELECT sale_id,slot,kind,quantity FROM vanilla_fish LIMIT 0").close();
                try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM sales s WHERE s.vanilla_count != (SELECT COALESCE(SUM(v.quantity),0) FROM vanilla_fish v WHERE v.sale_id=s.id) OR s.signed_count != (SELECT COUNT(*) FROM spent_fish f WHERE f.sale_id=s.id) OR (s.vanilla_count=0 AND s.signed_count=0)")) {
                    if (!rs.next() || rs.getLong(1) != 0) throw new SQLException("sale journal missing items");
                }

                try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM cooked_fish c JOIN spent_fish f ON f.fish_id=c.fish_id")) {
                    if (!rs.next() || rs.getLong(1) != 0) throw new SQLException("fish both sold and cooked");
                }
            }
        } catch (SQLException error) {
            db.close();
            throw error;
        }
    }

    private static void createVanillaSchema(Statement s) throws SQLException {
        s.execute("ALTER TABLE sales ADD COLUMN vanilla_count INTEGER NOT NULL DEFAULT 0 CHECK(vanilla_count BETWEEN 0 AND 2304)");
        s.execute("ALTER TABLE sales ADD COLUMN signed_count INTEGER NOT NULL DEFAULT 0 CHECK(signed_count BETWEEN 0 AND 36)");
        s.execute("UPDATE sales SET signed_count=(SELECT COUNT(*) FROM spent_fish f WHERE f.sale_id=sales.id)");
        s.execute("CREATE TABLE vanilla_fish (sale_id TEXT NOT NULL REFERENCES sales(id), slot INTEGER NOT NULL CHECK(slot BETWEEN 0 AND 35), kind TEXT NOT NULL CHECK(kind IN ('COD','SALMON','TROPICAL_FISH','PUFFERFISH')), quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 64), PRIMARY KEY(sale_id,slot)) STRICT");
    }
    /** Plain raw fish only; item metadata is rejected by the native planner. */
    public record VanillaFish(int slot, String kind, int quantity) {
        public VanillaFish {
            if (slot < 0 || slot >= 36 || !Set.of("COD", "SALMON", "TROPICAL_FISH", "PUFFERFISH").contains(kind)
                    || quantity < 1 || quantity > 64) throw new IllegalArgumentException("vanilla fish");
        }
    }
    public static final long VANILLA_PRICE_MINOR = 10;

    private static void createCookingSchema(Statement s) throws SQLException {
        s.execute("CREATE TABLE cooked_fish (fish_id TEXT PRIMARY KEY NOT NULL, id TEXT NOT NULL UNIQUE, owner TEXT NOT NULL, before_state TEXT NOT NULL, after_state TEXT NOT NULL, phase TEXT NOT NULL CHECK(phase IN ('PREPARED','DONE'))) STRICT");
        s.execute(COOK_INDEX);
    }

    private static void createOrderSchema(Statement s) throws SQLException {
        s.execute("CREATE TABLE order_claims (owner TEXT NOT NULL, order_day INTEGER NOT NULL CHECK(order_day>=0), order_slot INTEGER NOT NULL CHECK(order_slot BETWEEN 0 AND 2), sale_id TEXT NOT NULL UNIQUE REFERENCES sales(id), PRIMARY KEY(owner,order_day,order_slot)) STRICT");
    }
    private static void createFindSchema(Statement s) throws SQLException {
        s.execute("CREATE TABLE find_rewards (fish_id TEXT PRIMARY KEY NOT NULL, id TEXT NOT NULL UNIQUE, owner TEXT NOT NULL, before_state TEXT NOT NULL, after_state TEXT NOT NULL, phase TEXT NOT NULL CHECK(phase IN ('PREPARED','DONE'))) STRICT");
        s.execute(FIND_INDEX);
    }
    /** A submitted order is reserved immediately, including when its payout needs recovery. */
    public synchronized boolean orderClaimed(UUID owner, long day, int slot) throws SQLException {
        if (owner == null || day < 0 || slot < 0 || slot >= 3) throw new IllegalArgumentException("order identity");
        try (var s = db.prepareStatement("SELECT 1 FROM order_claims WHERE owner=? AND order_day=? AND order_slot=?")) {
            s.setString(1, owner.toString()); s.setLong(2, day); s.setInt(3, slot);
            try (var rs = s.executeQuery()) { return rs.next(); }
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
        return prepareSale(owner, transaction, before, after, fish, List.of(), secret, bank, null);
    }
    /** Caller must validate exact plain vanilla stacks and both native inventory snapshots. */
    public synchronized Result prepareMixed(UUID owner, UUID transaction, String before, String after,
            List<FishToken.Token> fish, List<VanillaFish> vanilla, byte[] secret, Bank bank) throws Exception {
        return prepareSale(owner, transaction, before, after, fish, vanilla, secret, bank, null);
    }
    /** One verified fish matching today's published contract, paid via the existing sale receipt. */
    public synchronized Result prepareOrder(UUID owner, UUID transaction, String before, String after,
            FishToken.Token fish, byte[] secret, Bank bank, FishingOrders.Order order) throws Exception {
        if (order == null || order.day() != FishingOrders.utcDay()
                || !FishingOrders.forDay(order.day()).get(order.slot()).equals(order) || !order.matches(fish)) return Result.CONFLICT;
        return prepareSale(owner, transaction, before, after, List.of(fish), List.of(), secret, bank, order);
    }
    private Result prepareSale(UUID owner, UUID transaction, String before, String after,
            List<FishToken.Token> fish, List<VanillaFish> vanilla, byte[] secret, Bank bank, FishingOrders.Order order) throws Exception {
        if (owner == null || transaction == null || before == null || after == null || before.equals(after)
                || before.length() > MAX_SNAPSHOT || after.length() > MAX_SNAPSHOT || fish == null || vanilla == null
                || (fish.isEmpty() && vanilla.isEmpty()) || fish.size() + vanilla.size() > 36 || bank == null) throw new IllegalArgumentException("sale intent");
        Set<UUID> ids = new HashSet<>();
        long amount = 0;
        for (var token : fish) {
            if (token == null || !FishToken.verify(secret, token) || !ids.add(token.id())) return Result.CONFLICT;
            amount = Math.addExact(amount, token.quotedPrice());
            if (amount > MAX_CREDIT) return Result.CONFLICT;
        }
        int vanillaCount = 0; Set<Integer> slots = new HashSet<>();
        for (var stack : vanilla) {
            if (stack == null || !slots.add(stack.slot())) return Result.CONFLICT;
            vanillaCount = Math.addExact(vanillaCount, stack.quantity());
        }
        amount = Math.addExact(amount, Math.multiplyExact((long) vanillaCount, VANILLA_PRICE_MINOR));
        if (amount > MAX_CREDIT) return Result.CONFLICT;
        if (order != null) amount = order.rewardMinor();
        if (!historyMatches(owner, bank.receipts(owner)) || pendingCook(owner) != null || pendingFind(owner) != null) return Result.CONFLICT;
        for (UUID id : ids) if (isCooked(id)) return Result.CONFLICT;
        db.setAutoCommit(false);
        try {
            try (var s = db.prepareStatement("INSERT INTO sales(id,owner,before_state,after_state,amount,phase,vanilla_count,signed_count) VALUES(?,?,?,?,?,'PREPARED',?,?)")) {
                s.setString(1, transaction.toString()); s.setString(2, owner.toString()); s.setString(3, before);
                s.setString(4, after); s.setLong(5, amount); s.setInt(6, vanillaCount); s.setInt(7, ids.size()); s.executeUpdate();
            }
            try (var s = db.prepareStatement("INSERT INTO spent_fish(fish_id,sale_id) VALUES(?,?)")) {
                for (UUID id : ids) { s.setString(1, id.toString()); s.setString(2, transaction.toString()); s.addBatch(); }
                s.executeBatch();
            }
            try (var s = db.prepareStatement("INSERT INTO vanilla_fish(sale_id,slot,kind,quantity) VALUES(?,?,?,?)")) {
                for (var stack : vanilla) {
                    s.setString(1, transaction.toString()); s.setInt(2, stack.slot()); s.setString(3, stack.kind());
                    s.setInt(4, stack.quantity()); s.addBatch();
                }
                s.executeBatch();
            }
            if (order != null) try (var s = db.prepareStatement("INSERT INTO order_claims(owner,order_day,order_slot,sale_id) VALUES(?,?,?,?)")) {
                s.setString(1, owner.toString()); s.setLong(2, order.day()); s.setInt(3, order.slot()); s.setString(4, transaction.toString());
                s.executeUpdate();
            }
            db.commit();
            return Result.WAIT;
        } catch (SQLException error) {
            db.rollback();
            if (error.getErrorCode() == 19) return Result.CONFLICT;
            throw error;
        } finally { db.setAutoCommit(true); }
    }

    /** Persist a cooking intent and reserve the fish against both sale and cooking. */
    public synchronized Result prepareCook(UUID owner, UUID transaction, String before, String after,
                                           FishToken.Token fish, byte[] secret) throws SQLException {
        if (owner == null || transaction == null || before == null || after == null || before.equals(after)
                || before.length() > MAX_SNAPSHOT || after.length() > MAX_SNAPSHOT || !FishToken.verify(secret, fish))
            throw new IllegalArgumentException("cook intent");
        if (pending(owner) != null || pendingCook(owner) != null || pendingFind(owner) != null || isSold(fish.id()) || isCooked(fish.id())) return Result.CONFLICT;
        try (var s = db.prepareStatement("INSERT INTO cooked_fish(fish_id,id,owner,before_state,after_state,phase) VALUES(?,?,?,?,?,'PREPARED')")) {
            s.setString(1, fish.id().toString()); s.setString(2, transaction.toString()); s.setString(3, owner.toString());
            s.setString(4, before); s.setString(5, after); s.executeUpdate(); return Result.WAIT;
        } catch (SQLException error) { if (error.getErrorCode() == 19) return Result.CONFLICT; throw error; }
    }
    private boolean isSold(UUID id) throws SQLException {
        try (var s = db.prepareStatement("SELECT 1 FROM spent_fish WHERE fish_id=?")) {
            s.setString(1, id.toString()); try (var rs = s.executeQuery()) { return rs.next(); }
        }
    }
    private boolean isCooked(UUID id) throws SQLException {
        try (var s = db.prepareStatement("SELECT 1 FROM cooked_fish WHERE fish_id=?")) {
            s.setString(1, id.toString()); try (var rs = s.executeQuery()) { return rs.next(); }
        }
    }
    public synchronized UUID pendingCook(UUID owner) throws SQLException {
        Objects.requireNonNull(owner);
        try (var s = db.prepareStatement("SELECT id FROM cooked_fish WHERE owner=? AND phase='PREPARED'")) {
            s.setString(1, owner.toString());
            try (var rs = s.executeQuery()) {
                if (!rs.next()) return null;
                try { String raw = rs.getString(1); UUID id = UUID.fromString(raw);
                    if (!id.toString().equals(raw) || rs.next()) throw new IllegalArgumentException("cooking id"); return id;
                } catch (RuntimeException invalid) { throw new SQLException("cooking journal invalid", invalid); }
            }
        }
    }
    /** Delivery and consumption must be one witnessed native inventory checkpoint. */
    public synchronized Result recoverCook(UUID owner, UUID transaction, Inventory inventory) throws Exception {
        if (owner == null || transaction == null || inventory == null) throw new IllegalArgumentException("cook recovery");
        String before, after, phase;
        try (var s = db.prepareStatement("SELECT before_state,after_state,phase FROM cooked_fish WHERE id=? AND owner=?")) {
            s.setString(1, transaction.toString()); s.setString(2, owner.toString());
            try (var rs = s.executeQuery()) {
                if (!rs.next()) return Result.CONFLICT;
                before = rs.getString(1); after = rs.getString(2); phase = rs.getString(3);
            }
        }
        if (before == null || after == null || before.equals(after) || before.length() > MAX_SNAPSHOT || after.length() > MAX_SNAPSHOT)
            return Result.CONFLICT;
        if ("DONE".equals(phase)) return Result.DONE;
        if (!"PREPARED".equals(phase)) return Result.CONFLICT;
        TransferResult transfer = inventory.recover(owner, transaction, before, after);
        if (transfer == null || transfer == TransferResult.CONFLICT) return Result.CONFLICT;
        if (transfer == TransferResult.WAIT) return Result.WAIT;
        try (var s = db.prepareStatement("UPDATE cooked_fish SET phase='DONE' WHERE id=? AND owner=? AND phase='PREPARED'")) {
            s.setString(1, transaction.toString()); s.setString(2, owner.toString());
            return s.executeUpdate() == 1 ? Result.DONE : Result.CONFLICT;
        }
    }

    public synchronized UUID pendingFind(UUID owner) throws SQLException {
        Objects.requireNonNull(owner);
        try (var s = db.prepareStatement("SELECT id FROM find_rewards WHERE owner=? AND phase='PREPARED'")) {
            s.setString(1, owner.toString());
            try (var rs = s.executeQuery()) {
                if (!rs.next()) return null;
                try { String raw = rs.getString(1); UUID id = UUID.fromString(raw);
                    if (!id.toString().equals(raw) || rs.next()) throw new IllegalArgumentException("find id"); return id;
                } catch (RuntimeException invalid) { throw new SQLException("find journal invalid", invalid); }
            }
        }
    }
    /** Caller must verify the catch event and derive the exact reward from native storage. */
    public synchronized Result prepareFind(UUID owner, UUID transaction, String before, String after,
                                           FishToken.Token fish, byte[] secret) throws SQLException {
        if (owner == null || transaction == null || before == null || after == null || before.equals(after)
                || before.length() > MAX_SNAPSHOT || after.length() > MAX_SNAPSHOT || !FishToken.verify(secret, fish))
            throw new IllegalArgumentException("find intent");
        if (pending(owner) != null || pendingCook(owner) != null || pendingFind(owner) != null) return Result.CONFLICT;
        try (var s = db.prepareStatement("INSERT INTO find_rewards(fish_id,id,owner,before_state,after_state,phase) VALUES(?,?,?,?,?,'PREPARED')")) {
            s.setString(1, fish.id().toString()); s.setString(2, transaction.toString()); s.setString(3, owner.toString());
            s.setString(4, before); s.setString(5, after); s.executeUpdate(); return Result.WAIT;
        } catch (SQLException error) { if (error.getErrorCode() == 19) return Result.CONFLICT; throw error; }
    }
    public synchronized Result recoverFind(UUID owner, UUID transaction, Inventory inventory) throws Exception {
        if (owner == null || transaction == null || inventory == null) throw new IllegalArgumentException("find recovery");
        String before, after, phase;
        try (var s = db.prepareStatement("SELECT before_state,after_state,phase FROM find_rewards WHERE id=? AND owner=?")) {
            s.setString(1, transaction.toString()); s.setString(2, owner.toString());
            try (var rs = s.executeQuery()) {
                if (!rs.next()) return Result.CONFLICT;
                before = rs.getString(1); after = rs.getString(2); phase = rs.getString(3);
            }
        }
        if (before == null || after == null || before.equals(after) || before.length() > MAX_SNAPSHOT || after.length() > MAX_SNAPSHOT)
            return Result.CONFLICT;
        if ("DONE".equals(phase)) return Result.DONE;
        if (!"PREPARED".equals(phase)) return Result.CONFLICT;
        TransferResult transfer = inventory.recover(owner, transaction, before, after);
        if (transfer == null || transfer == TransferResult.CONFLICT) return Result.CONFLICT;
        if (transfer == TransferResult.WAIT) return Result.WAIT;
        try (var s = db.prepareStatement("UPDATE find_rewards SET phase='DONE' WHERE id=? AND owner=? AND phase='PREPARED'")) {
            s.setString(1, transaction.toString()); s.setString(2, owner.toString());
            return s.executeUpdate() == 1 ? Result.DONE : Result.CONFLICT;
        }
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

    /** Full source reconciliation; includes owners absent from an older restored journal. */
    public synchronized boolean historyMatchesAll(Map<UUID, Map<String, Long>> receipts) throws SQLException {
        if (receipts == null) return false;
        Set<UUID> owners = new HashSet<>();
        try (var s = db.createStatement(); var rows = s.executeQuery("SELECT DISTINCT owner FROM sales")) {
            while (rows.next()) {
                String raw = rows.getString(1);
                try {
                    UUID owner = UUID.fromString(raw);
                    if (!owner.toString().equals(raw)) return false;
                    owners.add(owner);
                } catch (RuntimeException invalid) { return false; }
            }
        }
        for (UUID owner : receipts.keySet()) if (owner == null) return false;
        owners.addAll(receipts.keySet());
        for (UUID owner : owners) {
            var entries = receipts.get(owner);
            if (entries == null) {
                if (receipts.containsKey(owner)) return false;
                entries = Map.of();
            }
            if (!historyMatches(owner, entries)) return false;
        }
        return true;
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
