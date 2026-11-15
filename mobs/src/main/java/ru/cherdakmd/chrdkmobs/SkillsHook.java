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

    /**
     * Фиксированный опыт навыка за использование лута ({@code addXp} без
     * множителей). Нет навыков или аддон несовместим — {@code false}.
     */
    static boolean addXp(Logger log, Player player, String skillId, long xp) {
        if (player == null || !Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) {
            return false;
        }
        try {
            var registration = Bukkit.getServicesManager().getRegistration(ChrdkSkillsApi.class);
            if (registration == null) {
                return false;
            }
            return registration.getProvider().addXp(player, skillId, xp);
        } catch (LinkageError | RuntimeException error) {
            log.log(Level.WARNING,
                    "[CHRDK_Mobs] CHRDK_Skills несовместим: опыт лута не начислен", error);
            return false;
        }
    }

    /**
     * Компенсация пробивания стойкостью: столько добавляется к потолку
     * снижения урона игрока. Нет навыков — 0 (пробивание работает в полную).
     */
    static double mitigationBonus(Logger log, Player player) {
        if (player == null || !Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) {
            return 0.0;
        }
        try {
            var registration = Bukkit.getServicesManager().getRegistration(ChrdkSkillsApi.class);
            if (registration == null) {
                return 0.0;
            }
            return ru.cherdakmd.chrdkskills.AttributeMath.mitigationBonus(
                    registration.getProvider().stamina(player));
        } catch (LinkageError | RuntimeException error) {
            log.log(Level.WARNING,
                    "[CHRDK_Mobs] CHRDK_Skills несовместим: стойкость не учтена", error);
            return 0.0;
        }
    }

    /**
     * Давление тира: штраф игрока за недобор атрибутов зоны тира (доля).
     * Нет навыков — 0 (модуль работает автономно).
     */
    static double pressure(Logger log, Player player, int tier) {
        if (player == null || !Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) {
            return 0.0;
        }
        try {
            var registration = Bukkit.getServicesManager().getRegistration(ChrdkSkillsApi.class);
            if (registration == null) {
                return 0.0;
            }
            return ru.cherdakmd.chrdkskills.AttributeMath.pressure(
                    tier, registration.getProvider().attributeSum(player));
        } catch (LinkageError | RuntimeException error) {
            log.log(Level.WARNING,
                    "[CHRDK_Mobs] CHRDK_Skills несовместим: давление тира не учтено", error);
            return 0.0;
        }
    }
}
