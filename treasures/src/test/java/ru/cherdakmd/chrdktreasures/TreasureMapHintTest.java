package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Подсказка к клад-карте: румбы, дистанция и срок (1.4.153). */
class TreasureMapHintTest {

    private static TreasureMap map(String world, int x, int z) {
        return new TreasureMap(TreasureRegion.FOREST, TreasureTier.COMMON, world,
                x, z, 24, 10, 10, 0L);
    }

    @Test
    void compassKnowsEightDirections() {
        assertEquals("В", TreasureMapHint.compass(10, 0));
        assertEquals("СВ", TreasureMapHint.compass(10, -10));
        assertEquals("С", TreasureMapHint.compass(0, -10), "север — это -Z");
        assertEquals("СЗ", TreasureMapHint.compass(-10, -10));
        assertEquals("З", TreasureMapHint.compass(-10, 0));
        assertEquals("ЮЗ", TreasureMapHint.compass(-10, 10));
        assertEquals("Ю", TreasureMapHint.compass(0, 10), "юг — это +Z");
        assertEquals("ЮВ", TreasureMapHint.compass(10, 10));
        assertEquals("здесь", TreasureMapHint.compass(0, 0));
    }

    @Test
    void distanceRoundsToWholeBlocks() {
        assertEquals(5, TreasureMapHint.distanceBlocks(3, 4));
        assertEquals(0, TreasureMapHint.distanceBlocks(0, 0));
        assertEquals(312, TreasureMapHint.distanceBlocks(0, 312));
    }

    @Test
    void hintMarksZoneAndForeignWorlds() {
        TreasureMap map = map("world", 200, 100);
        TreasureMapHint near = TreasureMapHint.of(map, "world", 190, 95);
        assertTrue(near.sameWorld());
        assertTrue(near.inZone(), "точка рядом — уже зона");
        assertEquals(11, near.distance());
        assertEquals("ЮВ", near.direction(), "тайник правее и южнее игрока");

        TreasureMapHint far = TreasureMapHint.of(map, "world", 0, 0);
        assertFalse(far.inZone());
        assertEquals(224, far.distance());

        TreasureMapHint other = TreasureMapHint.of(map, "nether", 200, 100);
        assertFalse(other.sameWorld());
        assertFalse(other.inZone());
        assertEquals("", other.direction(), "в другом мире румб бессмыслен");
        assertFalse(TreasureMapHint.of(null, "world", 0, 0).sameWorld());
    }

    @Test
    void durationIsHumanReadable() {
        assertEquals("", TreasureMapHint.duration(0L), "бессрочную карту не подписываем");
        assertEquals("меньше минуты", TreasureMapHint.duration(30_000L));
        assertEquals("5 мин", TreasureMapHint.duration(5L * 60_000L));
        assertEquals("59 мин", TreasureMapHint.duration(59L * 60_000L + 59_000L));
        assertEquals("2 ч", TreasureMapHint.duration(120L * 60_000L));
        assertEquals("3 ч 40 мин", TreasureMapHint.duration((3L * 60L + 40L) * 60_000L));
        assertEquals("", TreasureMapHint.duration(-5L));
    }
}
