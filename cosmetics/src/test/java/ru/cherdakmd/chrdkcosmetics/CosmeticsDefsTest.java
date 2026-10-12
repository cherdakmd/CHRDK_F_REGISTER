package ru.cherdakmd.chrdkcosmetics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CosmeticsDefsTest {

    @Test
    void parseSkipsBrokenAndDuplicateEntries() {
        List<CosmeticsDefs.TrailDef> trails = CosmeticsDefs.parseTrails(List.of(
                Map.of("id", "Flame", "name", "Огонь", "particle", "Flame", "count", 99),
                Map.of("id", "", "particle", "heart"),
                Map.of("id", "hearts", "particle", ""),
                Map.of("id", "flame", "particle", "heart")), true);
        assertEquals(1, trails.size());
        CosmeticsDefs.TrailDef def = trails.get(0);
        assertEquals("flame", def.id(), "id нормализуется в нижний регистр");
        assertEquals("flame", def.particle());
        assertEquals(50, def.count(), "count зажат сверху");
    }

    @Test
    void disabledSwitchYieldsNoTrails() {
        assertTrue(CosmeticsDefs.parseTrails(List.of(
                Map.of("id", "a", "particle", "flame")), false).isEmpty());
    }

    @Test
    void joinEffectDisabledWithoutEnableOrPayload() {
        assertFalse(CosmeticsDefs.parseJoinEffect(Map.of("enabled", false)).enabled());
        assertFalse(CosmeticsDefs.parseJoinEffect(null).enabled());
        // включён, но ни частицы, ни звука — тоже выключен
        assertFalse(CosmeticsDefs.parseJoinEffect(Map.of("enabled", true)).enabled());
    }

    @Test
    void joinEffectParsesFields() {
        var effect = CosmeticsDefs.parseJoinEffect(Map.of(
                "enabled", true, "particle", "Totem_Of_Undying",
                "particle-count", 10_000, "sound", "Entity_Player_Levelup",
                "volume", 0.5, "pitch", 1.4));
        assertTrue(effect.enabled());
        assertEquals("totem_of_undying", effect.particle());
        assertEquals(500, effect.particleCount(), "размер вспышки зажат сверху");
        assertEquals("entity_player_levelup", effect.sound());
        assertEquals(1.4, effect.pitch(), 1e-6);
    }

    @Test
    void findIsCaseInsensitive() {
        List<CosmeticsDefs.TrailDef> trails = CosmeticsDefs.parseTrails(List.of(
                Map.of("id", "flame", "particle", "flame")), true);
        assertNotNull(CosmeticsDefs.find(trails, "FLAME"));
        assertNull(CosmeticsDefs.find(trails, "hearts"));
        assertNotNull(CosmeticsDefs.find(trails, " flame ".trim().toLowerCase()));
    }

    @Test
    void trailGateRespectsPassOnlyFlag() {
        // отключено админом — никому
        assertFalse(CosmeticsDefs.trailAllowed(false, true, true, "flame"));
        // pass-only: без проходки — нет
        assertFalse(CosmeticsDefs.trailAllowed(true, true, false, "flame"));
        assertTrue(CosmeticsDefs.trailAllowed(true, true, true, "flame"));
        // свободный доступ: всем, кто выбрал эффект
        assertTrue(CosmeticsDefs.trailAllowed(true, false, false, "flame"));
        // без выбранного эффекта — нет
        assertFalse(CosmeticsDefs.trailAllowed(true, false, true, null));
        assertFalse(CosmeticsDefs.trailAllowed(true, false, true, " "));
    }
}
