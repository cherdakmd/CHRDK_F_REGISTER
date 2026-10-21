package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Расчёт активных перков: пороги, суммирование и потолки.
 *
 * <p>Главное, что проверяется: до 10-го уровня не работает ничего, на 10-м
 * открывается ровно первый перк, а доли никогда не превышают 100 % — иначе
 * «шанс двойной добычи» однажды стал бы гарантией.</p>
 */
class SkillPerksTest {

    @Test
    void nothingBeforeFirstThreshold() {
        SkillPerks.Active active = SkillPerks.active(Map.of(SkillType.MINING, 1));
        assertTrue(active.perks().isEmpty());
        assertEquals(0.0, active.value(SkillPerk.Kind.BREAK_SPEED));
        assertFalse(active.has(SkillPerk.Kind.BREAK_SPEED));
    }

    @Test
    void firstPerkAtTen() {
        SkillPerks.Active active = SkillPerks.active(Map.of(SkillType.MINING, 10));
        assertEquals(0.06, active.value(SkillPerk.Kind.BREAK_SPEED), 1e-9);
        assertEquals(1, active.perks().size());
        assertEquals("mining-strong-hand", active.perks().get(0).id());
    }

    @Test
    void levelsSumUpToCeiling() {
        SkillPerks.Active active = SkillPerks.active(Map.of(SkillType.MINING, 100));
        assertEquals(0.20, active.value(SkillPerk.Kind.BREAK_SPEED), 1e-9);
        assertEquals(0.30, active.value(SkillPerk.Kind.DOUBLE_DROP), 1e-9);
        assertEquals(6, active.perks().size(), "на 100-м работают все шесть перков");

        SkillPerks.Active combat = SkillPerks.active(Map.of(SkillType.COMBAT, 100));
        assertEquals(0.15, combat.value(SkillPerk.Kind.MELEE_DAMAGE), 1e-9);
        assertEquals(0.08, combat.value(SkillPerk.Kind.LIFESTEAL), 1e-9);

        SkillPerks.Active defense = SkillPerks.active(Map.of(SkillType.DEFENSE, 100));
        assertEquals(6.0, defense.value(SkillPerk.Kind.ARMOR), 1e-9);
        assertEquals(0.12, defense.value(SkillPerk.Kind.DAMAGE_REDUCTION), 1e-9);
    }

    @Test
    void flagsWorkAsBooleans() {
        assertFalse(SkillPerks.active(Map.of(SkillType.FARMING, 24))
                .flag(SkillPerk.Kind.AUTO_REPLANT));
        assertTrue(SkillPerks.active(Map.of(SkillType.FARMING, 25))
                .flag(SkillPerk.Kind.AUTO_REPLANT));
    }

    @Test
    void onlyRequestedSkillsCount() {
        Map<SkillType, Integer> levels = new EnumMap<>(SkillType.class);
        levels.put(SkillType.MINING, 10);
        levels.put(SkillType.FISHING, 100);
        SkillPerks.Active active = SkillPerks.active(levels);
        assertEquals(0.06, active.value(SkillPerk.Kind.BREAK_SPEED), 1e-9,
                "рыбалка не должна давать скорость копания");
        assertTrue(active.value(SkillPerk.Kind.EXTRA_CATCH) > 0.0);
    }

    @Test
    void emptyAndNullLevelsGiveNothing() {
        assertTrue(SkillPerks.active(Map.of()).perks().isEmpty());
        assertTrue(SkillPerks.active(null).perks().isEmpty());
    }

    @Test
    void perksAtListsOpenedPerks() {
        assertEquals(0, SkillPerks.perksAt(SkillType.MINING, 9).size());
        assertEquals(1, SkillPerks.perksAt(SkillType.MINING, 10).size());
        assertEquals(2, SkillPerks.perksAt(SkillType.MINING, 25).size());
        assertEquals(6, SkillPerks.perksAt(SkillType.MINING, 100).size());
    }

    @Test
    void nextPerkSkipsOpenedAndEndsAtNull() {
        SkillPerk next = SkillPerks.nextPerk(SkillType.MINING, 10);
        assertNotNull(next);
        assertEquals(25, next.level());
        assertNull(SkillPerks.nextPerk(SkillType.MINING, 100), "после 100-го открывать нечего");
        assertNull(SkillPerks.nextPerk(null, 1));
    }

    @Test
    void thresholdsAreSortedWithoutDuplicates() {
        List<Integer> thresholds = SkillPerks.thresholds(SkillType.ENCHANTING);
        assertEquals(List.of(10, 25, 50, 75, 100), thresholds);
    }

    @Test
    void totalLevelStartsAtTenAndGrowsToThousand() {
        assertEquals(10, SkillPerks.totalLevel(Map.of()), "каждый навык начинается с 1-го");
        Map<SkillType, Integer> all = new EnumMap<>(SkillType.class);
        for (SkillType skill : SkillType.values()) {
            all.put(skill, 100);
        }
        assertEquals(1_000, SkillPerks.totalLevel(all));
        assertEquals(109, SkillPerks.totalLevel(Map.of(SkillType.MINING, 100)),
                "остальные навыки остаются на первом уровне");
    }
}
