package ru.cherdakmd.chrdkdisasters;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Реестр видов: идентификаторы, тексты, звуки и консервативные дефолты. */
final class DisasterKindTest {

    @Test
    void sixKindsHaveUniqueIdsAndKnownFx() {
        assertEquals(6, DisasterKind.values().length);
        Set<String> ids = new HashSet<>();
        Set<String> titles = new HashSet<>();
        Set<String> hints = new HashSet<>();
        for (DisasterKind kind : DisasterKind.values()) {
            assertTrue(ids.add(kind.id()), "дубликат id: " + kind.id());
            assertTrue(titles.add(kind.titleKey()));
            assertTrue(hints.add(kind.hintKey()));
            assertEquals(kind, DisasterKind.fromId(kind.id()).orElseThrow());
            assertEquals(kind, DisasterKind.fromId(kind.name()).orElseThrow());
            assertEquals("kind-" + kind.id(), kind.titleKey());
            assertEquals("hint-" + kind.id(), kind.hintKey());
            assertTrue(DisasterFx.SOUND_NAMES.contains(kind.sound()),
                    "неизвестный звук у " + kind.id());
            assertTrue(DisasterFx.EFFECT_NAMES.contains(kind.effect()),
                    "неизвестный эффект у " + kind.id());
            assertTrue(kind.amplifier() >= 0 && kind.amplifier() <= 1,
                    "эффект остаётся мягким");
        }
    }

    @Test
    void defaultEnabledIsConservative() {
        assertTrue(DisasterKind.DUST_STORM.stockEnabled());
        assertTrue(DisasterKind.METEOR_SHOWER.stockEnabled());
        assertTrue(DisasterKind.BLIZZARD.stockEnabled());
        assertFalse(DisasterKind.EARTHQUAKE.stockEnabled());
        assertFalse(DisasterKind.TORNADO.stockEnabled());
        assertFalse(DisasterKind.FLOOD.stockEnabled());
        assertEquals(4, (int) java.util.Arrays.stream(DisasterKind.values())
                .filter(DisasterKind::destructive).count());
        assertEquals(2, (int) java.util.Arrays.stream(DisasterKind.values())
                .filter(kind -> !kind.destructive()).count());
    }

    @Test
    void fxResolverRejectsUnknownNamesWithoutThrowing() {
        assertFalse(DisasterFx.SOUND_NAMES.contains("NONE"));
        assertFalse(DisasterFx.EFFECT_NAMES.contains("NONE"));
        // Ветвь default не резолвит ни одной Bukkit-константы, поэтому тест
        // остаётся чистым и не требует работающего сервера.
        assertEquals(null, DisasterFx.sound("NONE"));
        assertEquals(null, DisasterFx.effect("NONE"));
        assertEquals(null, DisasterFx.sound("SUPERNOVA"));
        assertEquals(null, DisasterFx.effect("WITHER"));
    }
}
