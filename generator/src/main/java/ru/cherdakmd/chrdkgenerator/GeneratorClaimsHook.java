package ru.cherdakmd.chrdkgenerator;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.cherdakmd.chrdkclaims.ClaimsPlugin;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Единственный класс модуля, который ссылается на бинарный API CHRDK_Claims.
 * Загружается JVM только после того, как вызывающий код убедился, что
 * софт-зависимость включена (иначе — {@code NoClassDefFoundError}).
 *
 * <p>Установка ядра разрешена только в своём привате (п.31, §3): приват
 * обязателен. Несовместимость или недоступное хранилище трактуются
 * fail-closed — установка запрещена.</p>
 */
final class GeneratorClaimsHook {

    static final String PLUGIN = "CHRDK_Claims";

    private GeneratorClaimsHook() {
    }

    /** {@code true} — чанк принадлежит игроку; {@code false} — чужой/бесхозный или Claims недоступны. */
    static boolean ownClaim(Logger logger, Player player, Location location) {
        try {
            Plugin found = Bukkit.getPluginManager().getPlugin(PLUGIN);
            if (!(found instanceof ClaimsPlugin claims) || !claims.isEnabled() || claims.store() == null) {
                return false;
            }
            var claim = claims.store().claimAt(location.getWorld().getName(),
                    location.getBlockX() >> 4, location.getBlockZ() >> 4);
            return claim != null && claim.owner != null
                    && claim.owner.equalsIgnoreCase(player.getName());
        } catch (LinkageError | RuntimeException error) {
            logger.log(Level.WARNING,
                    "[CHRDK_Generator] CHRDK_Claims несовместим: установка ядра запрещена", error);
            return false;
        }
    }

    /** {@code true} — CHRDK_Claims доступен для проверок. */
    static boolean available(Logger logger) {
        try {
            Plugin found = Bukkit.getPluginManager().getPlugin(PLUGIN);
            return found instanceof ClaimsPlugin claims && claims.isEnabled() && claims.store() != null;
        } catch (LinkageError | RuntimeException error) {
            logger.log(Level.WARNING, "[CHRDK_Generator] CHRDK_Claims несовместим", error);
            return false;
        }
    }
}
