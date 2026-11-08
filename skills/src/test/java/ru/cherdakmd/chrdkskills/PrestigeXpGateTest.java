package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.5.2: баланс прокачки престижа — самый дешёвый навык (майнинг) не может
 * уходить вперёд по ступеням, пока отстают остальные.
 */
final class PrestigeXpGateTest {

    private static final Set<SkillType> ALL = EnumSet.allOf(SkillType.class);

    private static Map<SkillType, Integer> prestige(int mining, int others) {
        Map<SkillType, Integer> map = new EnumMap<>(SkillType.class);
        for (SkillType type : SkillType.values()) {
            map.put(type, type == SkillType.MINING ? mining : others);
        }
        return map;
    }

    @Test
    void absentPrestigeMeansZeroAndNewPlayersAreFreeToLevel() {
        Map<SkillType, Integer> empty = Map.of();
        assertEquals(0, PrestigeXpGate.minimum(empty, ALL));
        assertEquals(0, PrestigeXpGate.lead(empty, ALL, SkillType.MINING));
        assertTrue(PrestigeXpGate.allows(empty, ALL, SkillType.MINING, 0));
        assertEquals(0, PrestigeXpGate.step(null, SkillType.MINING));
        assertEquals(0, PrestigeXpGate.step(empty, null));
    }

    @Test
    void strictParityBlocksOnlyTheSkillThatRanAhead() {
        Map<SkillType, Integer> oneAhead = prestige(1, 0);
        assertEquals(0, PrestigeXpGate.minimum(oneAhead, ALL));
        assertEquals(1, PrestigeXpGate.lead(oneAhead, ALL, SkillType.MINING));
        assertFalse(PrestigeXpGate.allows(oneAhead, ALL, SkillType.MINING, 0),
                "майнинг ушёл вперёд — опыт заблокирован");
        assertTrue(PrestigeXpGate.allows(oneAhead, ALL, SkillType.FISHING, 0),
                "отстающие навыки продолжают качаться");
        assertTrue(PrestigeXpGate.allows(oneAhead, ALL, SkillType.WOODCUTTING, 0));
    }

    @Test
    void catchingUpRestoresExperience() {
        Map<SkillType, Integer> aligned = prestige(2, 2);
        assertEquals(2, PrestigeXpGate.minimum(aligned, ALL));
        assertEquals(0, PrestigeXpGate.lead(aligned, ALL, SkillType.MINING));
        for (SkillType type : SkillType.values()) {
            assertTrue(PrestigeXpGate.allows(aligned, ALL, type, 0));
        }
    }

    @Test
    void maxLeadSoftensTheRuleButNeverBecomesNegativeOrInfinite() {
        Map<SkillType, Integer> twoAhead = prestige(3, 1);
        assertEquals(2, PrestigeXpGate.lead(twoAhead, ALL, SkillType.MINING));
        assertFalse(PrestigeXpGate.allows(twoAhead, ALL, SkillType.MINING, 0));
        assertFalse(PrestigeXpGate.allows(twoAhead, ALL, SkillType.MINING, 1));
        assertTrue(PrestigeXpGate.allows(twoAhead, ALL, SkillType.MINING, 2));
        assertTrue(PrestigeXpGate.allows(twoAhead, ALL, SkillType.MINING, 10));
        assertEquals(0, PrestigeXpGate.clampLead(-5));
        assertEquals(PrestigeMath.MAX_PER_SKILL, PrestigeXpGate.clampLead(999));
        assertFalse(PrestigeXpGate.allows(twoAhead, ALL, SkillType.MINING, -5));
    }

    @Test
    void disabledSkillsNeverHoldTheMinimumForever() {
        Map<SkillType, Integer> map = prestige(2, 2);
        map.put(SkillType.ENCHANTING, 0); // выключенный навык не сбрасывали
        Set<SkillType> tracked = EnumSet.allOf(SkillType.class);
        tracked.remove(SkillType.ENCHANTING);
        assertEquals(2, PrestigeXpGate.minimum(map, tracked));
        assertTrue(PrestigeXpGate.allows(map, tracked, SkillType.MINING, 0));
        assertEquals(0, PrestigeXpGate.minimum(map, Set.of()));
        // Пустой набор отслеживаемых обрабатывается в SkillService
        // (balanceView == null), а сам gate на пустой карте ничего не блокирует.
        assertTrue(PrestigeXpGate.allows(Map.of(), null, SkillType.MINING, 0));
    }

    @Test
    void stepsFromTheFileAreClampedToThePrestigeCeiling() {
        Map<SkillType, Integer> broken = new EnumMap<>(SkillType.class);
        broken.put(SkillType.MINING, 500);
        broken.put(SkillType.FISHING, -3);
        assertEquals(PrestigeMath.MAX_PER_SKILL, PrestigeXpGate.step(broken, SkillType.MINING));
        assertEquals(0, PrestigeXpGate.step(broken, SkillType.FISHING));
        assertEquals(0, PrestigeXpGate.minimum(broken, ALL));
        assertEquals(PrestigeMath.MAX_PER_SKILL,
                PrestigeXpGate.lead(broken, ALL, SkillType.MINING));
    }

    @Test
    void unknownSkillIsNeverBlocked() {
        assertTrue(PrestigeXpGate.allows(prestige(5, 0), ALL, null, 0));
        assertEquals(0, PrestigeXpGate.lead(prestige(5, 0), ALL, null));
    }
}
