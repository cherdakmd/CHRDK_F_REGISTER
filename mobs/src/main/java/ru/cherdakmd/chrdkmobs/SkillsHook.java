package ru.cherdakmd.chrdkmobs;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkskills.ChrdkSkillsApi;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Мягкая интеграция с CHRDK_Skills: очки прогресса игрока для привязки
 * мощности мобов к прокачке. Бинарный тип API резолвится только когда аддон
 * включён, поэтому без навыков модуль работает полностью автономно.
 */
final class SkillsHook {

    private static final String PLUGIN = "CHRDK_Skills";

    private SkillsHook() { }

    /**
     * Очки прогресса: суммарный уровень навыков + престиж × вес. Нет навыков,
     * игрока или аддон несовместим — 0 (моб остаётся без надбавки).
     */
    static int progressMetric(Logger log, Player player, int prestigeWeight) {
        if (player == null || !Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) {
            return 0;
        }
        try {
            var registration = Bukkit.getServicesManager().getRegistration(ChrdkSkillsApi.class);
            if (registration == null) {
                return 0;
            }
            ChrdkSkillsApi api = registration.getProvider();
            return MobTierMath.progressMetric(
                    api.totalLevel(player), api.totalPrestige(player), prestigeWeight);
        } catch (LinkageError | RuntimeException error) {
            log.log(Level.WARNING,
                    "[CHRDK_Mobs] CHRDK_Skills несовместим: прокачка не учтена", error);
            return 0;
        }
    }
}
