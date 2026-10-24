package ru.cherdakmd.chrdkevents;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Модель мирового события (1.4.160): клампы, пригодность и форматирование
 * времени.
 */
class EventModelTest {

    private static WorldEvent xp(int percent, int duration, int weight) {
        return new WorldEvent("test", "Тест", "описание", EventKind.XP, duration, weight,
                0, percent, List.of(), List.of(), List.of(), Material.EXPERIENCE_BOTTLE);
    }

    @Test
    void kindParsesCaseAndSpaces() {
        assertSame(EventKind.XP, EventKind.parse(" xp "));
        assertSame(EventKind.MINING, EventKind.parse("Mining"));
        assertSame(EventKind.LOOT, EventKind.parse("loot"));
        assertSame(EventKind.EFFECT, EventKind.parse("Effect"));
        assertNull(EventKind.parse(null));
        assertNull(EventKind.parse(" "));
        assertNull(EventKind.parse("нет-такого"));
        assertEquals("mining", EventKind.MINING.id());
    }

    @Test
    void contentIsClamped() {
        WorldEvent event = new WorldEvent("TEST", " ", " ", null, 5, 5_000, -10, 500,
                List.of(Material.AIR, Material.IRON_ORE, Material.IRON_ORE),
                List.of(), List.of(new WorldEvent.Effect("speed", 100, -5)),
                Material.AIR);
        assertEquals("test", event.id());
        assertEquals("test", event.name(), "пустое имя заменяется идом");
        assertEquals("", event.description());
        assertSame(EventKind.XP, event.kind(), "неизвестный вид — опыт по умолчанию");
        assertEquals(WorldEvent.MIN_DURATION_SECONDS, event.durationSeconds());
        assertEquals(WorldEvent.MAX_WEIGHT, event.weight());
        assertEquals(0, event.cooldownSeconds());
        assertEquals(100, event.percent());
        assertEquals(List.of(Material.IRON_ORE), event.blocks(), "воздух и дубли убраны");
        assertTrue(event.items().isEmpty());
        assertEquals(1, event.effects().size());
        assertEquals(9, event.effects().get(0).amplifier(), "уровень зажат");
        assertEquals(1, event.effects().get(0).seconds(), "длительность зажата");
        assertSame(Material.CLOCK, event.icon(), "воздух заменяется часами");
    }

    @Test
    void longestDurationIsClampedToo() {
        WorldEvent event = xp(50, 1_000_000, 10);
        assertEquals(WorldEvent.MAX_DURATION_SECONDS, event.durationSeconds());
    }

    @Test
    void usableNeedsWeightAndKindData() {
        assertTrue(xp(50, 300, 10).usable());
        assertFalse(xp(50, 300, 0).usable(), "нулевой вес — только вручную");
        assertFalse(xp(0, 300, 10).usable(), "без процента опыт не даёт ничего");

        WorldEvent mining = new WorldEvent("mine", "Руда", "", EventKind.MINING, 300, 10, 0,
                50, List.of(), List.of(Material.RAW_IRON), List.of(), Material.IRON_ORE);
        assertFalse(mining.usable(), "без блоков событию нечего усиливать");

        WorldEvent loot = new WorldEvent("loot", "Дроп", "", EventKind.LOOT, 300, 10, 0,
                50, List.of(), List.of(), List.of(), Material.BONE);
        assertFalse(loot.usable(), "без предметов дроп некуда добавить");

        WorldEvent effect = new WorldEvent("effect", "Эффект", "", EventKind.EFFECT, 300, 10, 0,
                50, List.of(), List.of(), List.of(), Material.CLOCK);
        assertFalse(effect.usable(), "без эффектов событию нечего давать");
    }

    @Test
    void longestEffectWins() {
        WorldEvent event = new WorldEvent("e", "E", "", EventKind.EFFECT, 300, 10, 0, 0,
                List.of(), List.of(),
                List.of(new WorldEvent.Effect("speed", 0, 30),
                        new WorldEvent.Effect("haste", 0, 120)), Material.CLOCK);
        assertEquals(120, event.longestEffectSeconds());
        assertEquals(0, xp(10, 100, 1).longestEffectSeconds());
    }

    @Test
    void clockAndDurationAreReadable() {
        assertEquals("00:00", EventText.clock(0));
        assertEquals("00:05", EventText.clock(5));
        assertEquals("04:35", EventText.clock(275));
        assertEquals("1:00:00", EventText.clock(3_600));
        assertEquals("00:00", EventText.clock(-5), "отрицательное время — ноль");

        assertEquals("45 с", EventText.duration(45));
        assertEquals("5 мин", EventText.duration(300));
        assertEquals("1 ч 20 мин", EventText.duration(4_800));
        assertEquals("2 ч", EventText.duration(7_200));
        assertEquals("—", EventText.stamp(0));
        assertTrue(EventText.stamp(1_700_000_000_000L).matches("\\d{2}\\.\\d{2} \\d{2}:\\d{2}"));
    }
}
