package ru.cherdakmd.chrdkdisasters;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkskills.ChrdkSkillsApi;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Мягкая интеграция с CHRDK_Skills: опыт в навык «defense» за пережитую
 * катастрофу. Бинарный тип API резолвится только когда аддон включён, поэтому
 * при его отсутствии disasters работает полностью автономно.
 */
final class SkillsHook {

    static final String PLUGIN = "CHRDK_Skills";

    private SkillsHook() {
    }

    static int award(Logger logger, Player player, String skillId, long units) {
        if (player == null || units <= 0L
                || !Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) {
            return 0;
        }
        try {
            var registration = Bukkit.getServicesManager()
                    .getRegistration(ChrdkSkillsApi.class);
            if (registration == null) {
                return 0;
            }
            return registration.getProvider().award(player, skillId, units);
        } catch (LinkageError | RuntimeException error) {
            logger.log(Level.WARNING,
                    "[CHRDK_Disasters] CHRDK_Skills несовместим: опыт пропущен", error);
            return 0;
        }
    }
}
