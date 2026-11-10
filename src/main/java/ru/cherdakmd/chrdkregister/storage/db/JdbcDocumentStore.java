package ru.cherdakmd.chrdkregister.storage.db;

import ru.cherdakmd.chrdkregister.api.storage.DocumentSnapshot;
import ru.cherdakmd.chrdkregister.api.storage.DocumentStore;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Реализация {@link DocumentStore} поверх {@code chrdk_storage_file}. JDBC и
 * SQL остаются в {@link ChrdkDatabase}; этот класс знает только namespace,
 * ключ, payload и revision.
 */
public final class JdbcDocumentStore implements DocumentStore {

    private static final String PREFIX = "documents/";

    private final ChrdkDatabase database;
    private final String namespace;

    public JdbcDocumentStore(ChrdkDatabase database, String namespace) {
        this.database = Objects.requireNonNull(database, "database");
        if (namespace == null || !namespace.matches("[A-Za-z0-9_]{1,96}")) {
            throw new IllegalArgumentException("storage namespace");
        }
        this.namespace = namespace;
    }

    @Override
    public CompletableFuture<Optional<DocumentSnapshot>> read(String key) {
        try {
            String path = path(key);
            ChrdkDatabase.FileRecord record = database.read(namespace, path);
            if (record == null) return CompletableFuture.completedFuture(Optional.empty());
            DocumentSnapshot snapshot = new DocumentSnapshot(
                    namespace,
                    key,
                    record.revision(),
                    new String(record.content(), StandardCharsets.UTF_8),
                    ChrdkDatabase.sha256Hex(record.content()),
                    record.updatedAtMillis()
            );
            return CompletableFuture.completedFuture(Optional.of(snapshot));
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    @Override
    public CompletableFuture<Long> write(String key, String payload) {
        try {
            String path = path(key);
            byte[] content = Objects.requireNonNull(payload, "payload").getBytes(StandardCharsets.UTF_8);
            return CompletableFuture.completedFuture(database.write(namespace, path, content));
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    @Override
    public CompletableFuture<Boolean> compareAndSet(String key, long expectedRevision, String payload) {
        try {
            String path = path(key);
            byte[] content = Objects.requireNonNull(payload, "payload").getBytes(StandardCharsets.UTF_8);
            return CompletableFuture.completedFuture(
                    database.compareAndSet(namespace, path, expectedRevision, content));
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    @Override
    public CompletableFuture<Boolean> delete(String key) {
        try {
            return CompletableFuture.completedFuture(database.delete(namespace, path(key)));
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private static String path(String key) {
        if (key == null || !key.matches("[a-z0-9][a-z0-9:_-]{0,119}")) {
            throw new IllegalArgumentException("document key");
        }
        return PREFIX + key + ".json";
    }
}
