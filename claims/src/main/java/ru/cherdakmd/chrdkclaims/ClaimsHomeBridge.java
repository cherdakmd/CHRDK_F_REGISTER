package ru.cherdakmd.chrdkclaims;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import ru.cherdakmd.chrdkregister.api.HomeClaimPolicy;

/**
 * Регистрация моста «приваты → дома» отдельным классом: на старом ядре без
 * {@link HomeClaimPolicy} падает только этот класс, а сами приваты работают.
 */
final class ClaimsHomeBridge {

    private ClaimsHomeBridge() {
    }

    static void register(ClaimsPlugin plugin) {
        Bukkit.getServicesManager().register(HomeClaimPolicy.class,
                new ClaimsHomePolicy(plugin::store), plugin, ServicePriority.Normal);
    }
}
