package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Две шкалы престижа, звания и потолки. Чистая математика без Bukkit:
 * конфиг может крутить множители, но не может продать бесконечную силу.
 */
class PrestigeMathTest {

    private static Map<SkillType, Integer> all(int steps) {
        Map<SkillType, Integer> out = new EnumMap<>(SkillType.class);
        for (SkillType skill : SkillType.values()) {
            out.put(skill, steps);
        }
        return out;
    }

    @Test
    void totalIsSumOfPerSkillStepsAndClamped() {
        assertEquals(0, PrestigeMath.total(Map.of()));
        assertEquals(3, PrestigeMath.total(Map.of(SkillType.MINING, 3)));
        assertEquals(100, PrestigeMath.total(all(10)));
        assertEquals(100, PrestigeMath.total(all(999)), "потолок сервера");
        assertEquals(10, PrestigeMath.total(Map.of(SkillType.MINING, 999)),
                "потолок одного навыка");
    }

    @Test
    void tiersAreOrderedAndFinite() {
        assertEquals("Странник", PrestigeMath.tierFor(0).title());
        assertEquals("Подмастерье", PrestigeMath.tierFor(5).title());
        assertEquals("Ветеран", PrestigeMath.tierFor(15).title());
        assertEquals("Мастер", PrestigeMath.tierFor(30).title());
        assertEquals("Легенда сервера", PrestigeMath.tierFor(60).title());
        assertEquals("Миф сервера", PrestigeMath.tierFor(100).title());
        assertEquals(5, PrestigeMath.nextTier(4).threshold());
        assertNull(PrestigeMath.nextTier(100));
        assertEquals("Мастер", PrestigeMath.tierAt(30).title());
        assertNull(PrestigeMath.tierAt(31));
        List<Integer> thresholds = PrestigeMath.rewardThresholds();
        assertEquals(List.of(5, 15, 30, 60, 100), thresholds);
        assertTrue(thresholds.stream().allMatch(t -> PrestigeMath.tierAt(t) != null));
    }

    @Test
    void progressIsBounded() {
        assertEquals(0, PrestigeMath.progressPercent(0));
        assertEquals(100, PrestigeMath.progressPercent(100));
        assertEquals(50, PrestigeMath.progressPercent(10));
        assertTrue(PrestigeMath.progressPercent(31) > 0);
        assertTrue(PrestigeMath.progressPercent(31) < 100);
    }

    @Test
    void xpFactorHasHardCeiling() {
        assertEquals(1.0, PrestigeMath.xpFactor(0, 0.03), 1e-9);
        assertEquals(1.03, PrestigeMath.xpFactor(1, 0.03), 1e-9);
        assertTrue(PrestigeMath.xpFactor(10, 0.03) <= PrestigeMath.MAX_XP_FACTOR + 1e-9);
        assertEquals(PrestigeMath.MAX_XP_FACTOR,
                PrestigeMath.xpFactor(999, 99.0), 1e-9);
        assertFalse(PrestigeMath.xpFactor(5, -1.0) < 1.0,
                "отрицательная крутилка не замедляет опыт");
    }

    @Test
    void perkBonusIsBoundedAndSkillSpecific() {
        assertEquals(SkillPerk.Kind.BREAK_SPEED, PrestigeMath.perkKind(SkillType.MINING));
        assertEquals(SkillPerk.Kind.ARMOR, PrestigeMath.perkKind(SkillType.DEFENSE));
        assertEquals(SkillPerk.Kind.MELEE_DAMAGE, PrestigeMath.perkKind(SkillType.COMBAT));
        assertEquals(0.0, PrestigeMath.perkValue(SkillType.MINING, 0, 0.01), 1e-9);
        assertTrue(PrestigeMath.perkValue(SkillType.MINING, 3, 0.01) > 0.0);
        assertEquals(PrestigeMath.MAX_PERCENT_PERK,
                PrestigeMath.perkValue(SkillType.MINING, 999, 99.0), 1e-9);
        assertEquals(PrestigeMath.MAX_ABSOLUTE_PERK,
                PrestigeMath.perkValue(SkillType.DEFENSE, 999, 99.0), 1e-9,
                "броня считается в абсолютных единицах");
    }

    @Test
    void diamondRewardsAreCapped() {
        assertEquals(4, PrestigeMath.stepDiamonds(4));
        assertEquals(PrestigeMath.MAX_STEP_DIAMONDS,
                PrestigeMath.stepDiamonds(9999));
        assertEquals(128, PrestigeMath.tierDiamonds(
                PrestigeMath.tierAt(100)));
        assertEquals(0, PrestigeMath.tierDiamonds(null));
        assertEquals(PrestigeMath.MAX_TIER_DIAMONDS,
                PrestigeMath.tierDiamonds(new PrestigeMath.Tier(1, "x", "x", 99999)));
    }

    @Test
    void romanIsBounded() {
        assertEquals("—", PrestigeMath.roman(0));
        assertEquals("I", PrestigeMath.roman(1));
        assertEquals("X", PrestigeMath.roman(10));
        assertEquals("X", PrestigeMath.roman(999));
    }
}
