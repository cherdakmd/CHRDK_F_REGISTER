package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TreasureRegionTest {

    private static TreasureRegion resolve(TreasureRegion.Dimension dimension, String biome, int y) {
        return TreasureRegion.resolve(dimension, biome, y);
    }

    @Test
    void dimensionsWinOverBiome() {
        assertEquals(TreasureRegion.NETHER,
                resolve(TreasureRegion.Dimension.NETHER, "plains", 64));
        assertEquals(TreasureRegion.END,
                resolve(TreasureRegion.Dimension.END, "deep_dark", -40));
    }

    @Test
    void deepDarkWinsEverywhereInOverworld() {
        assertEquals(TreasureRegion.DEEP_DARK, resolve(null, "deep_dark", 20));
        assertEquals(TreasureRegion.DEEP_DARK, resolve(null, "deep_dark", -40));
    }

    @Test
    void waterAndBeachWinOverDepth() {
        assertEquals(TreasureRegion.OCEAN, resolve(null, "deep_ocean", -40));
        assertEquals(TreasureRegion.OCEAN, resolve(null, "river", -20));
        assertEquals(TreasureRegion.OCEAN, resolve(null, "beach", 60));
    }

    @Test
    void belowDeepYSurfaceBiomeBecomesDeep() {
        assertEquals(TreasureRegion.DEEP, resolve(null, "plains", -8));
        assertEquals(TreasureRegion.DEEP, resolve(null, "desert", -64));
        assertEquals(TreasureRegion.PLAINS, resolve(null, "plains", -7));
        assertEquals(TreasureRegion.PLAINS, resolve(null, "plains", 70));
    }

    @Test
    void surfaceBiomesAreRecognised() {
        assertEquals(TreasureRegion.SNOW, resolve(null, "snowy_taiga", 70));
        assertEquals(TreasureRegion.SNOW, resolve(null, "frozen_peaks", 70));
        assertEquals(TreasureRegion.SNOW, resolve(null, "grove", 70));
        assertEquals(TreasureRegion.DESERT, resolve(null, "desert", 70));
        assertEquals(TreasureRegion.DESERT, resolve(null, "badlands", 70));
        assertEquals(TreasureRegion.JUNGLE, resolve(null, "jungle", 70));
        assertEquals(TreasureRegion.JUNGLE, resolve(null, "bamboo_jungle", 70));
        assertEquals(TreasureRegion.SWAMP, resolve(null, "swamp", 70));
        assertEquals(TreasureRegion.SWAMP, resolve(null, "mangrove_swamp", 70));
        assertEquals(TreasureRegion.SAVANNA, resolve(null, "savanna_plateau", 70));
        assertEquals(TreasureRegion.TAIGA, resolve(null, "old_growth_pine_taiga", 70));
        assertEquals(TreasureRegion.MOUNTAIN, resolve(null, "stony_peaks", 120));
        assertEquals(TreasureRegion.MOUNTAIN, resolve(null, "meadow", 120));
        assertEquals(TreasureRegion.SNOW, resolve(null, "snowy_slopes", 120));
        assertEquals(TreasureRegion.MOUNTAIN, resolve(null, "stony_shore", 60));
        assertEquals(TreasureRegion.PLAINS, resolve(null, "sunflower_plains", 70));
        assertEquals(TreasureRegion.FOREST, resolve(null, "dark_forest", 70));
    }

    @Test
    void unknownAndDotKeysFallBackToForest() {
        assertEquals(TreasureRegion.FOREST, resolve(null, "unknown_biome", 70));
        assertEquals(TreasureRegion.FOREST, resolve(null, "", 70));
        assertEquals(TreasureRegion.FOREST, resolve(null, null, 70));
        // Пространство имён отбрасывается: ключ из API и «minecraft:plains» равны.
        assertEquals(TreasureRegion.PLAINS, resolve(null, "minecraft:plains", 70));
    }

    @Test
    void byIdAcceptsIdAndEnumName() {
        assertNotNull(TreasureRegion.byId("plains"));
        assertNotNull(TreasureRegion.byId("  DEEP_DARK "));
        assertEquals(TreasureRegion.DEEP_DARK, TreasureRegion.byId("deep_dark"));
        assertEquals(TreasureRegion.NETHER, TreasureRegion.byId("Nether"));
        assertNull(TreasureRegion.byId(null));
        assertNull(TreasureRegion.byId(""));
        assertNull(TreasureRegion.byId("lunnye_polyany"));
    }
}
