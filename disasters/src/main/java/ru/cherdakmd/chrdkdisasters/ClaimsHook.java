package ru.cherdakmd.chrdkdisasters;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import ru.cherdakmd.chrdkclaims.ClaimsPlugin;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Единственный класс модуля, который ссылается на бинарный API CHRDK_Claims.
 * Загружается JVM только когда {@code DisasterProtection} уже убедился, что
 * софт-зависимость включена, — иначе при отсутствии плагина верификация
 * {@code DisasterProtection} могла бы бросить {@code NoClassDefFoundError}.
 *
 * <p>Несовместимость или недоступное хранилище трактуются fail-closed:
 * изменения блоков запрещаются, приваты не остаются без защиты.</p>
 */
final class ClaimsHook {

    static final String PLUGIN = "CHRDK_Claims";

    private ClaimsHook() {
    }

    static boolean hasClaim(Logger logger, String world, int chunkX, int chunkZ) {
        try {
            Plugin found = Bukkit.getPluginManager().getPlugin(PLUGIN);
            if (found instanceof ClaimsPlugin claims && claims.isEnabled()
                    && claims.store() != null) {
                return claims.store().claimAt(world, chunkX, chunkZ) != null;
            }
            logger.warning("[CHRDK_Disasters] CHRDK_Claims найден, но хранилище "
                    + "приватов недоступно: изменения блоков запрещены");
            return true;
        } catch (LinkageError | RuntimeException error) {
            logger.log(Level.WARNING, "[CHRDK_Disasters] CHRDK_Claims несовместим: "
                    + "изменения блоков запрещены", error);
            return true;
        }
    }
}
