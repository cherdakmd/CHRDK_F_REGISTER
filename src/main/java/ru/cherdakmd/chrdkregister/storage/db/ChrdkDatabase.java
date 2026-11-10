package ru.cherdakmd.chrdkregister.storage.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Единственный владелец JDBC-соединений. Пакет и классы драйвера доступны
 * только внутри {@code CHRDK_Core}; аддоны получают типизированные сервисы, а
 * не {@link Connection}. Используется собственный bounded pool на
 * {@link DriverManager}, чтобы не тащить HikariCP и SLF4J в первый RC.
 */
public final class ChrdkDatabase implements AutoCloseable {

    /** Снимок строки {@code chrdk_storage_file}. */
    public record FileRecord(byte[] content, byte[] sha256, long revision, long updatedAtMillis) {
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws Exception;
    }

    private final BlockingQueue<Connection> pool;
    private final ExecutorService io;
    private volatile boolean closed;
    private volatile boolean healthy;

    public ChrdkDatabase(ChrdkDatabaseConfig config) throws IOException {
        Objects.requireNonNull(config, "config");
        this.pool = new ArrayBlockingQueue<>(config.poolSize());
        AtomicInteger threads = new AtomicInteger();
        this.io = Executors.newFixedThreadPool(config.poolSize() + 1, task -> {
            Thread thread = new Thread(task, "CHRDK-DB-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (ClassNotFoundException ignored) {
                // JDBC 4 service loader найдёт зашейденный драйвер сам.
            }
            for (int i = 0; i < config.poolSize(); i++) {
                pool.add(createConnection(config));
            }
            migrate();
            this.healthy = true;
        } catch (IOException | RuntimeException error) {
            close();
            throw error;
        }
    }

    private static Connection createConnection(ChrdkDatabaseConfig config) throws SQLException {
        Connection connection = DriverManager.getConnection(config.jdbcUrl(), config.connectionProperties());
        connection.setAutoCommit(true);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        if (!connection.isValid(2)) {
            connection.close();
            throw new SQLException("MySQL connection is not valid");
        }
        return connection;
    }

    private <T> CompletableFuture<T> submit(SqlWork<T> work) {
        if (closed) return CompletableFuture.failedFuture(new IOException("CHRDK storage is closed"));
        return CompletableFuture.supplyAsync(() -> {
            Connection connection;
            try {
                connection = pool.take();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new CompletionException(new IOException("Interrupted while borrowing DB connection", error));
            }
            try {
                return work.run(connection);
            } catch (Exception error) {
                throw new CompletionException(error);
            } finally {
                release(connection);
            }
        }, io);
    }

    private <T> T execute(SqlWork<T> work) throws IOException {
        try {
            return submit(work).join();
        } catch (CompletionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof IOException ioError) throw ioError;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException("CHRDK storage unavailable", cause);
        }
    }

    private void release(Connection connection) {
        if (closed) {
            closeQuietly(connection);
            return;
        }
        try {
            if (!connection.isValid(2)) {
                closeQuietly(connection);
                return;
            }
            if (!pool.offer(connection)) closeQuietly(connection);
        } catch (SQLException error) {
            closeQuietly(connection);
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Лог не должен содержать JDBC URL или credentials.
        }
    }

    private void migrate() throws IOException {
        String sql = readSchema();
        List<String> statements = splitStatements(sql);
        execute(connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String query : statements) statement.execute(query);
            }
            return null;
        });
        verifySchemaChecksum();
    }

    private void verifySchemaChecksum() throws IOException {
        String expected = StorageSchema.DDL_CHECKSUM;
        String actual = execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT HEX(checksum) FROM chrdk_schema_migration WHERE version = 1")) {
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            }
        });
        if (actual == null) {
            throw new IOException("chrdk_schema_migration lacks version 1 row; manual intervention required");
        }
        if (!expected.equalsIgnoreCase(actual)) {
            throw new IOException("Database schema checksum mismatch: expected " + expected
                    + " but found " + actual + "; refusing to start with stale schema");
        }
    }

    private static String readSchema() throws IOException {
        try (InputStream stream = ChrdkDatabase.class.getResourceAsStream("/database-schema.sql")) {
            if (stream == null) throw new IOException("database-schema.sql отсутствует в core JAR");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static List<String> splitStatements(String sql) {
        StringBuilder clean = new StringBuilder();
        for (String line : sql.split("\\R")) {
            int comment = line.indexOf("--");
            clean.append(comment < 0 ? line : line.substring(0, comment)).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String part : clean.toString().split(";")) {
            String query = part.trim();
            if (!query.isEmpty()) out.add(query);
        }
        return out;
    }

    public boolean healthy() {
        return !closed && healthy;
    }

    public long schemaVersion() throws IOException {
        Long version = execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT meta_value FROM chrdk_meta WHERE meta_key = ?")) {
                statement.setBytes(1, bytes("schema_version"));
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) return 1L;
                    return Long.parseLong(new String(rows.getBytes(1), StandardCharsets.UTF_8).trim());
                }
            }
        });
        return version == null ? 1L : version;
    }

    /** SHA-256 hex текущего DDL, как сохранено в chrdk_schema_migration.version=1. */
    public String schemaChecksum() throws IOException {
        return execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT HEX(checksum) FROM chrdk_schema_migration WHERE version = 1")) {
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            }
        });
    }

    public FileRecord read(String namespace, String relativePath) throws IOException {
        return execute(connection -> readOn(connection, namespace, relativePath));
    }

    private static FileRecord readOn(Connection connection, String namespace, String relativePath)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT content, sha256, revision, updated_at FROM chrdk_storage_file "
                        + "WHERE namespace = ? AND relative_path = ?")) {
            statement.setBytes(1, bytes(namespace));
            statement.setBytes(2, bytes(relativePath));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                Timestamp updated = rows.getTimestamp("updated_at");
                return new FileRecord(
                        rows.getBytes("content"),
                        rows.getBytes("sha256"),
                        rows.getLong("revision"),
                        updated == null ? 0L : updated.getTime()
                );
            }
        }
    }

    public boolean exists(String namespace, String relativePath) throws IOException {
        Boolean found = execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM chrdk_storage_file WHERE namespace = ? AND relative_path = ? LIMIT 1")) {
                statement.setBytes(1, bytes(namespace));
                statement.setBytes(2, bytes(relativePath));
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next();
                }
            }
        });
        return Boolean.TRUE.equals(found);
    }

    public long write(String namespace, String relativePath, byte[] content) throws IOException {
        byte[] digest = sha256(content);
        Long revision = execute(connection -> writeOn(connection, namespace, relativePath, content, digest, false));
        return revision == null ? 1L : revision;
    }

    private static long writeOn(Connection connection, String namespace, String relativePath,
                                byte[] content, byte[] digest, boolean onlyIfAbsent) throws SQLException {
        String insert = onlyIfAbsent
                ? "INSERT INTO chrdk_storage_file(namespace, relative_path, content, sha256, revision) "
                + "VALUES (?, ?, ?, ?, 1)"
                : "INSERT INTO chrdk_storage_file(namespace, relative_path, content, sha256, revision) "
                + "VALUES (?, ?, ?, ?, 1) ON DUPLICATE KEY UPDATE content = VALUES(content), "
                + "sha256 = VALUES(sha256), revision = revision + 1, "
                + "updated_at = CURRENT_TIMESTAMP(6)";
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setBytes(1, bytes(namespace));
            statement.setBytes(2, bytes(relativePath));
            statement.setBytes(3, content);
            statement.setBytes(4, digest);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT revision FROM chrdk_storage_file WHERE namespace = ? AND relative_path = ?")) {
            statement.setBytes(1, bytes(namespace));
            statement.setBytes(2, bytes(relativePath));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 1L;
            }
        }
    }

    public boolean compareAndSet(String namespace, String relativePath, long expectedRevision,
                                 byte[] content) throws IOException {
        byte[] digest = sha256(content);
        Boolean result = execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE chrdk_storage_file SET content = ?, sha256 = ?, revision = revision + 1, "
                            + "updated_at = CURRENT_TIMESTAMP(6) "
                            + "WHERE namespace = ? AND relative_path = ? AND revision = ?")) {
                statement.setBytes(1, content);
                statement.setBytes(2, digest);
                statement.setBytes(3, bytes(namespace));
                statement.setBytes(4, bytes(relativePath));
                statement.setLong(5, expectedRevision);
                if (statement.executeUpdate() > 0) return true;
            }
            if (expectedRevision != 0L) return false;
            if (readOn(connection, namespace, relativePath) != null) return false;
            writeOn(connection, namespace, relativePath, content, digest, true);
            return true;
        });
        return Boolean.TRUE.equals(result);
    }

    public boolean delete(String namespace, String relativePath) throws IOException {
        Boolean removed = execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM chrdk_storage_file WHERE namespace = ? AND relative_path = ?")) {
                statement.setBytes(1, bytes(namespace));
                statement.setBytes(2, bytes(relativePath));
                return statement.executeUpdate() > 0;
            }
        });
        return Boolean.TRUE.equals(removed);
    }

    public List<String> list(String namespace, String prefix) throws IOException {
        List<String> found = execute(connection -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT relative_path FROM chrdk_storage_file "
                            + "WHERE namespace = ? AND relative_path LIKE CONCAT(?, '%')")) {
                statement.setBytes(1, bytes(namespace));
                statement.setBytes(2, bytes(prefix));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) out.add(new String(rows.getBytes(1), StandardCharsets.UTF_8));
                }
            }
            return out;
        });
        return found == null ? List.of() : found;
    }

    /** Транзакционный rename внутри одной схемы; file-level atomic replacement. */
    public void move(String namespace, String from, String to, boolean replaceExisting) throws IOException {
        execute(connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                FileRecord source = readOn(connection, namespace, from);
                if (source == null) throw new NoSuchFileException(from);
                boolean targetExists = readOn(connection, namespace, to) != null;
                if (targetExists && !replaceExisting) throw new FileAlreadyExistsException(to);
                writeOn(connection, namespace, to, source.content(), source.sha256(), false);
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM chrdk_storage_file WHERE namespace = ? AND relative_path = ?")) {
                    statement.setBytes(1, bytes(namespace));
                    statement.setBytes(2, bytes(from));
                    statement.executeUpdate();
                }
                connection.commit();
                return null;
            } catch (Exception error) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Первопричина сохраняется в error.
                }
                throw error;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        });
    }

    public static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public static String sha256Hex(byte[] content) {
        return HexFormat.of().formatHex(sha256(content));
    }

    private static byte[] bytes(String value) {
        return Objects.requireNonNull(value, "key").getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        healthy = false;
        Connection connection;
        while ((connection = pool.poll()) != null) closeQuietly(connection);
        io.shutdownNow();
    }
}
