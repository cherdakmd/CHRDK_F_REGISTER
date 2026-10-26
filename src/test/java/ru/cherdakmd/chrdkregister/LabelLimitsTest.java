package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Лимиты пресетов по ступеням (1.4.187): у игрока без ранга два слота, у
 * Покровителя шесть, кривой конфиг не роняет меню.
 */
class LabelLimitsTest {

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        return config;
    }

    @Test
    void defaultsGrowWithTheRank() {
        LabelLimits limits = LabelLimits.DEFAULT;
        assertEquals(2, limits.slotsFor(0));
        assertEquals(2, limits.slotsFor(1));
        assertEquals(3, limits.slotsFor(2));
        assertEquals(4, limits.slotsFor(3));
        assertEquals(5, limits.slotsFor(4));
        assertEquals(6, limits.slotsFor(5));
    }

    @Test
    void levelOutsideTheLadderUsesTheNearestEdge() {
        LabelLimits limits = LabelLimits.DEFAULT;
        assertEquals(2, limits.slotsFor(-3));
        assertEquals(6, limits.slotsFor(99));
    }

    @Test
    void configListIsReadPerStep() {
        LabelLimits limits = LabelLimits.fromConfig(yaml("""
                labels:
                  presets:
                    slots: [1, 3, 3, 5]
                """));
        assertEquals(1, limits.slotsFor(0));
        assertEquals(3, limits.slotsFor(1));
        assertEquals(3, limits.slotsFor(2));
        assertEquals(5, limits.slotsFor(3));
        assertEquals(5, limits.slotsFor(5), "недостающие ступени берут последнее значение");
    }

    @Test
    void sillyValuesAreClamped() {
        LabelLimits limits = new LabelLimits(List.of(0, 999, -5));
        assertTrue(limits.slotsFor(0) >= 1, "меньше одного слота не бывает");
        assertTrue(limits.slotsFor(1) <= LabelLimits.MAX_SLOTS, "больше шести не поместится");
        assertTrue(limits.slotsFor(2) >= 1);
    }

    @Test
    void canSaveCountsUsedSlots() {
        LabelLimits limits = LabelLimits.DEFAULT;
        assertTrue(limits.canSave(0, 0));
        assertTrue(limits.canSave(0, 1));
        assertFalse(limits.canSave(0, 2), "два слота уже заняты");
        assertTrue(limits.canSave(5, 5));
        assertFalse(limits.canSave(5, 6));
    }

    @Test
    void missingSectionFallsBackToDefaults() {
        assertEquals(LabelLimits.DEFAULT, LabelLimits.fromConfig(yaml("general:\n  x: 1\n")));
        assertEquals(LabelLimits.DEFAULT, LabelLimits.fromConfig(null));
    }
}
