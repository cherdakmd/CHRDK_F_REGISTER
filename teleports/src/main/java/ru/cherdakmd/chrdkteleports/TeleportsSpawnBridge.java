package ru.cherdakmd.chrdkteleports;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import ru.cherdakmd.chrdkregister.api.SpawnHomeIndex;

/**
 * Регистрация моста «дома → ядро» отдельным классом: если на сервере старое ядро
 * без {@link SpawnHomeIndex}, падает только этот класс, а модуль продолжает работать.
 */
final class TeleportsSpawnBridge {

    private TeleportsSpawnBridge() {
    }

    static void register(TeleportsPlugin plugin) {
        Bukkit.getServicesManager().register(SpawnHomeIndex.class,
                new TeleportsSpawnIndex(plugin), plugin, ServicePriority.Normal);
    }
}
