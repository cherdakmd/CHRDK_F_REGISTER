package ru.cherdakmd.chrdkclaims;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import ru.cherdakmd.chrdkregister.api.SpawnClaimIndex;

/**
 * Регистрация моста «приваты → ядро» отдельным классом: если на сервере старое ядро
 * без {@link SpawnClaimIndex}, падает только этот класс, а модуль продолжает работать.
 */
final class ClaimsSpawnBridge {

    private ClaimsSpawnBridge() {
    }

    static void register(ClaimsPlugin plugin) {
        Bukkit.getServicesManager().register(SpawnClaimIndex.class,
                new ClaimsSpawnIndex(plugin), plugin, ServicePriority.Normal);
    }
}
