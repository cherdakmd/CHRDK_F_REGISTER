package ru.cherdakmd.chrdkregister.storage.db;

import org.bukkit.plugin.Plugin;
import ru.cherdakmd.chrdkregister.api.storage.ChrdkStorage;
import ru.cherdakmd.chrdkregister.api.storage.DocumentStore;
import ru.cherdakmd.chrdkregister.api.storage.StorageMode;
import ru.cherdakmd.chrdkregister.storage.ChrdkStorageFileSystem;

import java.io.IOException;
import java.util.Objects;

/** Реализация сервиса хранения, регистрируемая только {@code CHRDK_Core}. */
public final class ChrdkStorageImpl implements ChrdkStorage {

    private final StorageMode mode;
    private final ChrdkDatabase database;

    private ChrdkStorageImpl(StorageMode mode, ChrdkDatabase database) {
        this.mode = mode;
        this.database = database;
    }

    public static ChrdkStorageImpl open(Plugin plugin, ChrdkDatabaseConfig config) throws IOException {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(config, "config");
        StorageMode mode = config.mode();
        if (mode == StorageMode.FILE || mode == StorageMode.FILE_READONLY) {
            ChrdkStorageFileSystem.instance().configure(mode, null);
            return new ChrdkStorageImpl(mode, null);
        }
        ChrdkDatabase database = new ChrdkDatabase(config);
        try {
            ChrdkStorageFileSystem.instance().configure(mode, database);
            return new ChrdkStorageImpl(mode, database);
        } catch (RuntimeException error) {
            database.close();
            throw error;
        }
    }

    @Override
    public boolean healthy() {
        return mode != StorageMode.MYSQL || (database != null && database.healthy());
    }

    @Override
    public StorageMode mode() {
        return mode;
    }

    @Override
    public long schemaVersion() {
        if (database == null) return 1L;
        try {
            return database.schemaVersion();
        } catch (IOException error) {
            throw new IllegalStateException("Не удалось прочитать версию схемы CHRDK", error);
        }
    }

    @Override
    public DocumentStore documents(Plugin owner) {
        Objects.requireNonNull(owner, "owner");
        if (mode != StorageMode.MYSQL || database == null) {
            throw new UnsupportedOperationException(
                    "DocumentStore доступен только при backend=MYSQL; включите migration cutover");
        }
        return new JdbcDocumentStore(database, owner.getName());
    }

    @Override
    public void close() {
        if (database != null) database.close();
        ChrdkStorageFileSystem.instance().close();
    }
}
