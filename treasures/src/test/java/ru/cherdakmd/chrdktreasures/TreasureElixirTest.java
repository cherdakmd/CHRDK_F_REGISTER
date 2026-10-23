package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Каталог эликсиров 1.4.157: иды, эффекты и границы.
 *
 * <p>Ошибка в иде эффекта не видна в игре — зелье просто окажется пустым,
 * потому что {@code Registry.EFFECT} такого ида не знает. Поэтому тест
 * сверяет иды со списком ванильных эффектов и требует, чтобы у каждого
 * эликсира был хотя бы один эффект в допустимых границах.</p>
 */
class TreasureElixirTest {

    /** Ванильные иды, которые обязаны существовать в любом современном Paper. */
    private static final Set<String> VANILLA_EFFECTS = Set.of(
            "speed", "haste", "night_vision", "water_breathing", "dolphins_grace",
            "fire_resistance", "regeneration", "absorption", "strength", "jump_boost",
            "luck", "hero_of_the_village");

    @Test
    void catalogueIsRichAndUnique() {
        List<TreasureElixir> all = TreasureElixir.all();
        assertEquals(12, all.size(), "эликсиров должно быть двенадцать");
        Set<String> ids = new HashSet<>();
        // Цвет — единственное, чем эликсиры отличаются в инвентаре до наведения
        // курсора, поэтому одинаковые цвета считаются ошибкой каталога.
        Set<String> colours = new HashSet<>();
        for (TreasureElixir elixir : all) {
            assertTrue(ids.add(elixir.id()), "повторяющийся ид: " + elixir.id());
            assertTrue(colours.add(elixir.red() + "," + elixir.green() + "," + elixir.blue()),
                    "два эликсира одного цвета: " + elixir.id());
        }
        assertEquals(all.size(), ids.size());
    }

    @Test
    void everyEffectIsAVanillaEffectInLimits() {
        for (TreasureElixir elixir : TreasureElixir.all()) {
            assertTrue(!elixir.effects().isEmpty(), "эликсир без эффектов: " + elixir.id());
            for (TreasureElixir.Effect effect : elixir.effects()) {
                assertTrue(VANILLA_EFFECTS.contains(effect.type()),
                        "неизвестный эффект «" + effect.type() + "» у " + elixir.id());
                assertEquals(effect.type(), effect.type().toLowerCase(Locale.ROOT));
                assertTrue(effect.seconds() >= TreasureElixir.Effect.MIN_SECONDS
                                && effect.seconds() <= TreasureElixir.Effect.MAX_SECONDS,
                        "длительность вне границ: " + elixir.id());
                assertTrue(effect.amplifier() >= 0
                                && effect.amplifier() <= TreasureElixir.Effect.MAX_AMPLIFIER,
                        "уровень вне границ: " + elixir.id());
            }
        }
    }

    @Test
    void everyDocumentedIdResolves() {
        for (String id : List.of("swiftness", "haste", "night_vision", "water_breathing",
                "fire_resistance", "regeneration", "absorption", "strength", "leaping",
                "luck", "hero", "insight")) {
            assertNotNull(TreasureElixir.byId(id), "нет эликсира " + id);
        }
        assertNull(TreasureElixir.byId("speed"), "ид эффекта — не ид эликсира");
        assertNull(TreasureElixir.byId(""));
        assertNull(TreasureElixir.byId(null));
    }

    @Test
    void idsAreCaseInsensitiveAndTrimmed() {
        TreasureElixir swiftness = TreasureElixir.byId(" SWIFTNESS ");
        assertNotNull(swiftness);
        assertEquals("swiftness", swiftness.id());
        assertEquals("haste", TreasureElixir.byId(" HAste ").id());
    }

    @Test
    void nameKeyFollowsMessagesLayout() {
        assertEquals("elixir.swiftness.name", TreasureElixir.byId("swiftness").nameKey());
        assertEquals("elixir.hero.name", TreasureElixir.byId("hero").nameKey());
    }

    @Test
    void levelsAreRoman() {
        assertEquals("I", new TreasureElixir.Effect("speed", 30, 0).level());
        assertEquals("II", new TreasureElixir.Effect("speed", 30, 1).level());
        assertEquals("III", new TreasureElixir.Effect("speed", 30, 2).level());
    }

    @Test
    void effectValuesAreClamped() {
        TreasureElixir.Effect shortEffect = new TreasureElixir.Effect(" speed ", 1, 9);
        assertEquals("speed", shortEffect.type(), "ид нормализуется");
        assertEquals(TreasureElixir.Effect.MIN_SECONDS, shortEffect.seconds());
        assertEquals(TreasureElixir.Effect.MAX_AMPLIFIER, shortEffect.amplifier());

        TreasureElixir.Effect longEffect = new TreasureElixir.Effect("haste", 99_000, -3);
        assertEquals(TreasureElixir.Effect.MAX_SECONDS, longEffect.seconds());
        assertEquals(0, longEffect.amplifier());
    }

    @Test
    void elixirRejectsBrokenDescriptions() {
        assertThrows(IllegalArgumentException.class,
                () -> new TreasureElixir("", 1, 2, 3, false,
                        List.of(new TreasureElixir.Effect("speed", 30, 0))));
        assertThrows(IllegalArgumentException.class,
                () -> new TreasureElixir("плохой ид", 1, 2, 3, false,
                        List.of(new TreasureElixir.Effect("speed", 30, 0))));
        assertThrows(IllegalArgumentException.class,
                () -> new TreasureElixir("empty", 1, 2, 3, false, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new TreasureElixir.Effect("", 30, 0));
    }

    @Test
    void coloursAndLongestDurationAreSane() {
        for (TreasureElixir elixir : TreasureElixir.all()) {
            assertTrue(elixir.red() >= 0 && elixir.red() <= 255);
            assertTrue(elixir.green() >= 0 && elixir.green() <= 255);
            assertTrue(elixir.blue() >= 0 && elixir.blue() <= 255);
            int longest = 0;
            for (TreasureElixir.Effect effect : elixir.effects()) {
                longest = Math.max(longest, effect.seconds());
            }
            assertEquals(longest, elixir.longestSeconds());
            assertTrue(elixir.longestSeconds() > 0);
        }
    }

    @Test
    void strongestElixirStaysBelowTenMinutes() {
        for (TreasureElixir elixir : TreasureElixir.all()) {
            assertTrue(elixir.longestSeconds() <= TreasureElixir.Effect.MAX_SECONDS,
                    "эликсир " + elixir.id() + " действует дольше десяти минут");
        }
    }
}
