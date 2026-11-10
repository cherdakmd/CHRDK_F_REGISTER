package ru.cherdakmd.chrdkregister.storage;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import ru.cherdakmd.chrdkregister.api.storage.ChrdkStorage;
import ru.cherdakmd.chrdkregister.api.storage.StorageMode;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Точка входа для всех аддонов: вместо {@code plugin.getDataFolder().toPath()}
 * они получают путь, который в {@code MYSQL}-режиме маршрутизирует runtime
 * JSON/YAML в {@code chrdk_storage_file}, а конфиги, сообщения, логи и ресурсы
 * оставляет на обычном диске. Ни один аддон не знает JDBC URL или SQL.
 */
public final class ChrdkStoragePaths {

    private ChrdkStoragePaths() {
    }

    public static Path dataFolder(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        Path legacy = plugin.getDataFolder().toPath();
        ChrdkStorage storage = Bukkit.getServicesManager().load(ChrdkStorage.class);
        if (storage == null || storage.mode() != StorageMode.MYSQL) return legacy;
        return ChrdkStorageFileSystem.instance().root(plugin.getName(), legacy);
    }
}
