package ru.cherdakmd.chrdkregister.api.storage;

import org.bukkit.plugin.Plugin;

/**
 * Единый сервис хранения CHRDK. Регистрируется только {@code CHRDK_Core} в
 * {@code ServicesManager}; аддоны получают его вызовом
 * {@code Bukkit.getServicesManager().load(ChrdkStorage.class)}.
 *
 * <p>Отдельный контракт намеренно не меняет
 * {@code ChrdkRegisterApi.API_VERSION}: модули, которым БД не нужна, остаются
 * совместимыми с API 42.</p>
 */
public interface ChrdkStorage extends AutoCloseable {

    int STORAGE_API_VERSION = 1;

    boolean healthy();

    StorageMode mode();

    long schemaVersion();

    /** Возвращает хранилище, чей namespace привязан к вызвавшему плагину. */
    DocumentStore documents(Plugin owner);

    @Override
    void close();
}
