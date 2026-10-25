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
    void trailGateRespectsEnabledAndSelection() {
        // отключено админом — никому
        assertFalse(CosmeticsDefs.trailAllowed(false, true, "flame"));
        // не открыт — нет
        assertFalse(CosmeticsDefs.trailAllowed(true, false, "flame"));
        assertTrue(CosmeticsDefs.trailAllowed(true, true, "flame"));
        // без выбранного эффекта — нет
        assertFalse(CosmeticsDefs.trailAllowed(true, true, null));
        assertFalse(CosmeticsDefs.trailAllowed(true, true, " "));
    }

    @Test
    void trailProductDefaultsToPrefixedIdAndCanBeOverridden() {
        var trails = CosmeticsDefs.parseTrails(List.of(
                Map.of("id", "flame", "particle", "flame"),
                Map.of("id", "magic", "particle", "witch", "product", "Trail-Magic-90")), true);
        assertEquals("trail-flame", trails.get(0).product(), "по умолчанию товар = trail-<id>");
        assertEquals("trail-magic-90", trails.get(1).product(), "product нормализуется");
    }

    private static CosmeticsDefs.TrailDef flame() {
        return new CosmeticsDefs.TrailDef("flame", "Огонь", "flame", 3, 0.1, 0.0, "trail-flame", 0);
    }

    private static CosmeticsDefs.TrailDef rankTrail(int rank) {
        return new CosmeticsDefs.TrailDef("gold", "Золотая пыль", "electric_spark", 3, 0.2, 0.0, "", rank);
    }

    private static java.util.function.Predicate<String> owns(String... ids) {
        var set = java.util.Set.of(ids);
        return set::contains;
    }

    @Test
    void passUnlocksEverything() {
        assertTrue(CosmeticsDefs.unlocked(flame(), true, true, 1, owns(), List.of()));
    }

    @Test
    void singlePurchaseUnlocksTrailWithoutPass() {
        assertFalse(CosmeticsDefs.unlocked(flame(), true, false, 0, owns(), List.of()),
                "без ранга и без покупки шлейф закрыт");
        assertTrue(CosmeticsDefs.unlocked(flame(), true, false, 0, owns("trail-flame"), List.of()),
                "купленный шлейф открыт без ранга");
        assertFalse(CosmeticsDefs.unlocked(flame(), true, false, 0, owns("trail-hearts"), List.of()),
                "чужой товар ничего не открывает");
    }

    @Test
    void packUnlocksEveryTrail() {
        assertTrue(CosmeticsDefs.unlocked(flame(), true, false, 0,
                owns("trails-pack"), List.of("trails-pack")));
        assertFalse(CosmeticsDefs.unlocked(flame(), true, false, 0,
                owns("trails-pack"), List.of()),
                "набор учитывается только из конфига pack-products");
    }

    @Test
    void freeModeIgnoresPurchases() {
        assertTrue(CosmeticsDefs.unlocked(flame(), false, false, 0, owns(), List.of()));
    }

    @Test
    void rankOpensTrailsOfItsLevelAndBelow() {
        assertFalse(CosmeticsDefs.unlocked(rankTrail(4), true, true, 3, owns(), List.of()),
                "ступень ниже — шлейф закрыт даже с рангом");
        assertTrue(CosmeticsDefs.unlocked(rankTrail(4), true, true, 4, owns(), List.of()),
                "своя ступень открывает шлейф");
        assertTrue(CosmeticsDefs.unlocked(rankTrail(4), true, true, 5, owns(), List.of()),
                "старшая ступень открывает и нижние шлейфы");
    }

    @Test
    void rankTrailStaysClosedWhenTierIsLowEvenIfPassFlagSet() {
        // Проходка больше не открывает ранговые шлейфы: решает только ступень.
        assertFalse(CosmeticsDefs.unlocked(rankTrail(5), true, true, 2, owns(), List.of()));
    }

    @Test
    void rankTrailWithoutProductCannotBeBought() {
        assertFalse(CosmeticsDefs.unlocked(rankTrail(5), true, false, 0, owns(""), List.of()),
                "пустой product не считается покупкой");
    }
}
