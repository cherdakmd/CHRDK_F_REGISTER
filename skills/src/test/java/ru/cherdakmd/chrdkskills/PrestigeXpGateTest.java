package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Мягкая зона баланса престижа: база 2, аддитивка ранга, пол 25 % и общий стоп
 * с 8. Класс без Bukkit — таблица проверяется целиком, включая профиль со
 * скриншота (отрыв 2 = автоматическая амнистия).
 */
final class PrestigeXpGateTest {

    private static final Set<SkillType> ALL = EnumSet.allOf(SkillType.class);
    private static final List<Integer> RANKS = List.of(0, 1, 1, 2, 2, 3);
    private static final int BASE = SkillsConfig.DEFAULT_BALANCE_BASE_MAX_LEAD;
    private static final int CAP = SkillsConfig.DEFAULT_BALANCE_HARD_CAP;
    private static final double STEP = SkillsConfig.DEFAULT_BALANCE_SOFT_STEP;
    private static final double FLOOR = SkillsConfig.DEFAULT_BALANCE_MIN_XP_FACTOR;
    private static final double EPS = 1e-9;

    private static Map<SkillType, Integer> prestige(int mining, int others) {
        Map<SkillType, Integer> map = new EnumMap<>(SkillType.class);
        for (SkillType type : SkillType.values()) {
            map.put(type, type == SkillType.MINING ? mining : others);
        }
        return map;
    }

    private static double factorAt(int lead, int rankLevel) {
        return factorAt(lead, rankLevel, CAP, STEP, FLOOR);
    }

    private static double factorAt(int lead, int rankLevel, int cap,
                                   double step, double floor) {
        int allowance = PrestigeXpGate.allowance(BASE, RANKS, rankLevel, cap);
        return PrestigeXpGate.factor(prestige(lead, 0), ALL, SkillType.MINING,
                allowance, cap, step, floor);
    }

    @Test
    void rankTableGivesTwoThreeThreeFourFourFive() {
        int[] expected = {2, 3, 3, 4, 4, 5};
        for (int rank = 0; rank < expected.length; rank++) {
            assertEquals(expected[rank],
                    PrestigeXpGate.allowance(BASE, RANKS, rank, CAP),
                    "разрешённый отрыв L" + rank);
            assertEquals(expected[rank] - BASE,
                    PrestigeXpGate.rankBonus(RANKS, rank),
                    "ранговая аддитивка L" + rank);
        }
        assertEquals(5, PrestigeXpGate.allowance(BASE, RANKS, 99, CAP),
                "неизвестно большой ранг прижат к L5");
        assertEquals(2, PrestigeXpGate.allowance(BASE, RANKS, -7, CAP),
                "отрицательный ранг не даёт послабления");
        assertEquals(3, PrestigeXpGate.allowance(BASE, RANKS, 5, 3),
                "ранг не пробивает жёсткий потолок");
    }

    @Test
    void ordinaryPlayerAndTopRankFollowTheAgreedTables() {
        double[] ordinary = {1.0, 1.0, 1.0, 0.75, 0.50, 0.25, 0.25, 0.25, 0.0};
        double[] topRank = {1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.75, 0.50, 0.0};
        for (int lead = 0; lead <= 8; lead++) {
            assertEquals(ordinary[lead], factorAt(lead, 0), EPS,
                    "без ранга, отрыв " + lead);
            assertEquals(topRank[lead], factorAt(lead, 5), EPS,
                    "L5, отрыв " + lead);
        }
        assertEquals("0.75", PrestigeXpGate.factorText(0.75));
        assertEquals("0.50", PrestigeXpGate.factorText(0.50));
        assertEquals("0.25", PrestigeXpGate.factorText(0.25));
        assertEquals("0", PrestigeXpGate.factorText(0.0));
        assertEquals("1", PrestigeXpGate.factorText(1.0));
    }

    @Test
    void screenshotProfileWithLeadTwoIsAmnestiedImmediately() {
        Map<SkillType, Integer> skewed = prestige(2, 0);
        int allowance = PrestigeXpGate.allowance(BASE, RANKS, 0, CAP);
        assertEquals(2, allowance);
        assertEquals(0, PrestigeXpGate.minimum(skewed, ALL),
                "минимум по всем включённым навыкам = 0");
        assertEquals(2, PrestigeXpGate.lead(skewed, ALL, SkillType.MINING));
        assertEquals(1.0, PrestigeXpGate.factor(skewed, ALL, SkillType.MINING,
                allowance, CAP, STEP, FLOOR), EPS,
                "обновление JAR снимает старую блокировку без правок skills.json");
        assertEquals(1.0, PrestigeXpGate.factor(skewed, ALL, SkillType.MINING,
                allowance, CAP, STEP, FLOOR), EPS, "амнистия стабильна");
        assertEquals(3L, PrestigeXpGate.scaledXp(3L, 1.0),
                "полный XP не урезается");
    }

    @Test
    void hardCapStopsEveryRankAndFloorIsShared() {
        for (int rank = 0; rank < PrestigeXpGate.RANK_LEVELS; rank++) {
            assertEquals(0.0, factorAt(8, rank), EPS, "стоп с 8 у L" + rank);
            assertEquals(0.0, factorAt(9, rank), EPS, "стоп после 8 у L" + rank);
        }
        // Ранг расширяет только зону полного XP: при достаточно длинной мягкой
        // зоне пол 25 % одинаков и для L0, и для L5.
        assertEquals(0.25, factorAt(7, 0, 10, STEP, FLOOR), EPS);
        assertEquals(0.25, factorAt(9, 5, 10, STEP, FLOOR), EPS);
        assertEquals(0.75, factorAt(3, 0, 10, STEP, FLOOR), EPS);
        assertEquals(0.75, factorAt(6, 5, 10, STEP, FLOOR), EPS);
        assertEquals(1.0, factorAt(5, 0, 10, STEP, 1.0), EPS,
                "владелец может поднять пол до полного XP");
    }

    @Test
    void curvedRankListsNeverCrashOrGrantInfiniteAllowance() {
        assertEquals(0, PrestigeXpGate.rankBonus(null, 3));
        assertEquals(0, PrestigeXpGate.rankBonus(List.of(), 0));
        assertEquals(7, PrestigeXpGate.rankBonus(List.of(7, -2), 0));
        assertEquals(0, PrestigeXpGate.rankBonus(List.of(7, -2), 1));
        assertEquals(0, PrestigeXpGate.rankBonus(List.of(7, -2), 5));
        assertEquals(PrestigeMath.MAX_PER_SKILL,
                PrestigeXpGate.rankBonus(List.of(999), 0));
        assertEquals(2, PrestigeXpGate.allowance(BASE, null, 5, CAP));
        assertEquals(2, PrestigeXpGate.allowance(BASE, List.of(), 5, CAP));
        assertEquals(0.0, PrestigeXpGate.factor(prestige(3, 0), ALL, SkillType.MINING,
                99, 0, STEP, FLOOR), EPS, "нулевой потолок = полный стоп");
    }

    @Test
    void softZoneAlwaysLeavesAtLeastOneXpForARealAction() {
        assertEquals(0L, PrestigeXpGate.scaledXp(0L, 0.25));
        assertEquals(0L, PrestigeXpGate.scaledXp(-4L, 0.25));
        assertEquals(0L, PrestigeXpGate.scaledXp(100L, 0.0));
        assertEquals(1L, PrestigeXpGate.scaledXp(2L, 0.25),
                "2 XP за блок × 0.25 округляются в 1, а не в 0");
        assertEquals(1L, PrestigeXpGate.scaledXp(3L, 0.25));
        assertEquals(2L, PrestigeXpGate.scaledXp(8L, 0.25));
        assertEquals(75L, PrestigeXpGate.scaledXp(100L, 0.75));
        assertEquals(100L, PrestigeXpGate.scaledXp(100L, 1.0));
    }

    @Test
    void absentOrDisabledSkillsNeverHoldTheMinimumForever() {
        Map<SkillType, Integer> empty = Map.of();
        assertEquals(0, PrestigeXpGate.minimum(empty, ALL));
        assertEquals(0, PrestigeXpGate.lead(empty, ALL, SkillType.MINING));
        assertTrue(PrestigeXpGate.allows(empty, ALL, SkillType.MINING, 0));
        assertEquals(0, PrestigeXpGate.step(null, SkillType.MINING));
        assertEquals(0, PrestigeXpGate.step(empty, null));

        Map<SkillType, Integer> map = prestige(2, 2);
        map.put(SkillType.ENCHANTING, 0); // выключенный навык не сбрасывали
        Set<SkillType> tracked = EnumSet.allOf(SkillType.class);
        tracked.remove(SkillType.ENCHANTING);
        assertEquals(2, PrestigeXpGate.minimum(map, tracked));
        assertEquals(0, PrestigeXpGate.lead(map, tracked, SkillType.MINING));
        assertTrue(PrestigeXpGate.allows(map, tracked, SkillType.MINING, 0));
        assertEquals(0, PrestigeXpGate.minimum(map, Set.of()));
        assertTrue(PrestigeXpGate.allows(Map.of(), null, SkillType.MINING, 0));
    }

    @Test
    void stepsFromTheFileAreClampedAndLegacyAllowsStillWorks() {
        Map<SkillType, Integer> broken = new EnumMap<>(SkillType.class);
        broken.put(SkillType.MINING, 500);
        broken.put(SkillType.FISHING, -3);
        assertEquals(PrestigeMath.MAX_PER_SKILL, PrestigeXpGate.step(broken, SkillType.MINING));
        assertEquals(0, PrestigeXpGate.step(broken, SkillType.FISHING));
        assertEquals(0, PrestigeXpGate.minimum(broken, ALL));
        assertEquals(PrestigeMath.MAX_PER_SKILL,
                PrestigeXpGate.lead(broken, ALL, SkillType.MINING));

        Map<SkillType, Integer> twoAhead = prestige(3, 1);
        assertEquals(2, PrestigeXpGate.lead(twoAhead, ALL, SkillType.MINING));
        assertFalse(PrestigeXpGate.allows(twoAhead, ALL, SkillType.MINING, 0));
        assertFalse(PrestigeXpGate.allows(twoAhead, ALL, SkillType.MINING, 1));
        assertTrue(PrestigeXpGate.allows(twoAhead, ALL, SkillType.MINING, 2));
        assertTrue(PrestigeXpGate.allows(twoAhead, ALL, SkillType.FISHING, 0));
        assertEquals(0, PrestigeXpGate.clampLead(-5));
        assertEquals(PrestigeMath.MAX_PER_SKILL, PrestigeXpGate.clampLead(999));
        assertFalse(PrestigeXpGate.allows(twoAhead, ALL, SkillType.MINING, -5));
    }

    @Test
    void unknownSkillIsNeverBlocked() {
        assertTrue(PrestigeXpGate.allows(prestige(5, 0), ALL, null, 0));
        assertEquals(0, PrestigeXpGate.lead(prestige(5, 0), ALL, null));
        assertEquals(1.0, PrestigeXpGate.factor(prestige(8, 0), ALL, null,
                2, CAP, STEP, FLOOR), EPS);
    }
}
