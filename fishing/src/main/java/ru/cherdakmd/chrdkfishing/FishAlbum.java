package ru.cherdakmd.chrdkfishing;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Experimental persistent personal records. A caller must invoke record only
 * after an authorised final catch event; this class cannot prove an event fired.
 * Fish identity is global and is credited to the first recorded owner only.
 * Database, key, bank and playerdata require a consistent backup. Restoring an
 * old album can lose records; it must never be treated as sale or bank evidence.
 */
public final class FishAlbum implements AutoCloseable {
    private static final int APPLICATION_ID = 1178684482;
    private static final int SCHEMA = 1;
    private static final String OWNER_INDEX = "CREATE INDEX catches_by_owner ON catches(owner)";
    private final Connection db;
    private final byte[] key;
    public enum Result { RECORDED, ALREADY_RECORDED, CONFLICT }
    public record Record(long count, int longestCm, FishingModel.Size bestSize) { }

    public FishAlbum(Path path, boolean allowCreate, byte[] key) throws SQLException {
        Objects.requireNonNull(path, "path");
        if (key == null || key.length != FishToken.KEY_BYTES) throw new IllegalArgumentException("album signing key");
        this.key = key.clone();
        boolean fresh = false;
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (!allowCreate) throw new SQLException("album missing; restore required");
                Files.createFile(path); fresh = true;
            } else if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new SQLException("album is not a regular file");
        } catch (java.io.IOException failure) { throw new SQLException("album create failed", failure); }
        db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000"); s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA synchronous=FULL");
            if (fresh) {
                db.setAutoCommit(false);
                try {
                    s.execute("CREATE TABLE catches (id TEXT PRIMARY KEY NOT NULL, owner TEXT NOT NULL, species TEXT NOT NULL, size TEXT NOT NULL, length_cm INTEGER NOT NULL, quoted_price INTEGER NOT NULL, signature TEXT NOT NULL, caught_at INTEGER NOT NULL CHECK(caught_at>=0)) STRICT");
                    s.execute(OWNER_INDEX); s.execute("PRAGMA application_id=" + APPLICATION_ID); s.execute("PRAGMA user_version=" + SCHEMA);
                    db.commit();
                } catch (SQLException error) { db.rollback(); throw error; }
                finally { db.setAutoCommit(true); }
            } else {
                try (var rs = s.executeQuery("PRAGMA application_id")) { if (!rs.next() || rs.getInt(1) != APPLICATION_ID) throw new SQLException("wrong album identity"); }
                try (var rs = s.executeQuery("PRAGMA user_version")) { if (!rs.next() || rs.getInt(1) != SCHEMA) throw new SQLException("unsupported album schema"); }
                try (var rs = s.executeQuery("PRAGMA quick_check")) { if (!rs.next() || !"ok".equals(rs.getString(1))) throw new SQLException("album corrupted"); }
                try (var rs = s.executeQuery("SELECT sql FROM sqlite_master WHERE type='index' AND name='catches_by_owner'")) {
                    if (!rs.next() || !OWNER_INDEX.equals(rs.getString(1))) throw new SQLException("album index mismatch");
                }
                s.executeQuery("SELECT id,owner,species,size,length_cm,quoted_price,signature,caught_at FROM catches LIMIT 0").close();
            }
        } catch (SQLException error) { db.close(); java.util.Arrays.fill(this.key, (byte) 0); throw error; }
    }

    /** No item is consumed; repeated processing of the same valid catch is idempotent. */
    public synchronized Result record(UUID owner, FishToken.Token token, long caughtAt) throws SQLException {
        if (owner == null || caughtAt < 0) throw new IllegalArgumentException("catch owner/time");
        if (!FishToken.verify(key, token)) return Result.CONFLICT;
        Existing existing = find(token.id());
        if (existing != null) return existing.owner().equals(owner) && existing.token().equals(token)
                ? Result.ALREADY_RECORDED : Result.CONFLICT;
        try (var insert = db.prepareStatement("INSERT INTO catches (id,owner,species,size,length_cm,quoted_price,signature,caught_at) SELECT ?,?,?,?,?,?,?,? WHERE (SELECT COUNT(*) FROM catches)<1000000")) {
            insert.setString(1, token.id().toString()); insert.setString(2, owner.toString());
            insert.setString(3, token.fish().speciesId()); insert.setString(4, token.fish().size().name());
            insert.setInt(5, token.fish().lengthCm()); insert.setLong(6, token.quotedPrice()); insert.setString(7, token.mac()); insert.setLong(8, caughtAt);
            if (insert.executeUpdate() != 1) throw new SQLException("album capacity reached");
            return Result.RECORDED;
        } catch (SQLException error) {
            if (error.getErrorCode() != 19) throw error;
            Existing winner = find(token.id());
            return winner != null && winner.owner().equals(owner) && winner.token().equals(token)
                    ? Result.ALREADY_RECORDED : Result.CONFLICT;
        }
    }
    /** Checked entries only: malformed, unknown or forged records fail the whole read, never disappear silently. */
    public synchronized Map<String, Record> records(UUID owner) throws SQLException {
        Objects.requireNonNull(owner, "owner");
        Map<String, Record> out = new HashMap<>();
        try (var query = db.prepareStatement("SELECT id,owner,species,size,length_cm,quoted_price,signature,caught_at FROM catches WHERE owner=?")) {
            query.setString(1, owner.toString());
            try (var rs = query.executeQuery()) {
                while (rs.next()) {
                    Existing entry = read(rs);
                    if (!entry.owner().equals(owner)) throw new SQLException("album owner mismatch");
                    var fish = entry.token().fish();
                    Record old = out.get(fish.speciesId());
                    long count = old == null ? 1 : Math.addExact(old.count(), 1);
                    int max = old == null ? fish.lengthCm() : Math.max(old.longestCm(), fish.lengthCm());
                    FishingModel.Size best = old == null || fish.size().ordinal() > old.bestSize().ordinal() ? fish.size() : old.bestSize();
                    out.put(fish.speciesId(), new Record(count, max, best));
                }
            }
        }
        return Map.copyOf(out);
    }
    private record Existing(UUID owner, FishToken.Token token) { }
    private Existing find(UUID id) throws SQLException {
        try (var query = db.prepareStatement("SELECT id,owner,species,size,length_cm,quoted_price,signature,caught_at FROM catches WHERE id=?")) {
            query.setString(1, id.toString());
            try (var rs = query.executeQuery()) { return rs.next() ? read(rs) : null; }
        }
    }
    private Existing read(ResultSet rs) throws SQLException {
        try {
            String rawId = rs.getString(1), rawOwner = rs.getString(2);
            UUID id = UUID.fromString(rawId), owner = UUID.fromString(rawOwner);
            if (!id.toString().equals(rawId) || !owner.toString().equals(rawOwner) || rs.getLong(8) < 0) throw new IllegalArgumentException("album identity");
            var fish = new FishingModel.Catch(rs.getString(3), FishingModel.Size.valueOf(rs.getString(4)), rs.getInt(5));
            var token = new FishToken.Token(id, fish, rs.getLong(6), rs.getString(7));
            if (!FishToken.verify(key, token)) throw new IllegalArgumentException("album signature");
            return new Existing(owner, token);
        } catch (RuntimeException error) { throw new SQLException("album entry invalid", error); }
    }
    @Override public synchronized void close() throws SQLException {
        try { db.close(); } finally { java.util.Arrays.fill(key, (byte) 0); }
    }
}
