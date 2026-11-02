package ru.cherdakmd.chrdkregister.api.exploration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import static org.junit.jupiter.api.Assertions.*;

class ExplorationStoreTest {
    @TempDir Path dir;
    final UUID player = UUID.randomUUID();
    ExplorationStore store() {
        var logger = Logger.getAnonymousLogger(); logger.setLevel(Level.OFF);
        var store = new ExplorationStore(dir.resolve("exploration.json"), logger); store.load(); return store;
    }
    @Test void everyBiomeHasFiveStableTasksBesidesItsSeparateVisit() {
        var keys = List.of("plains", "sunflower_plains", "snowy_plains", "ice_spikes", "desert", "swamp", "mangrove_swamp",
                "forest", "flower_forest", "birch_forest", "dark_forest", "old_growth_birch_forest", "old_growth_pine_taiga",
                "old_growth_spruce_taiga", "taiga", "snowy_taiga", "savanna", "savanna_plateau", "windswept_hills",
                "windswept_gravelly_hills", "windswept_forest", "windswept_savanna", "jungle", "sparse_jungle", "bamboo_jungle",
                "badlands", "eroded_badlands", "wooded_badlands", "meadow", "cherry_grove", "grove", "snowy_slopes",
                "frozen_peaks", "jagged_peaks", "stony_peaks", "river", "frozen_river", "beach", "snowy_beach", "stony_shore",
                "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean", "ocean", "deep_ocean", "cold_ocean", "deep_cold_ocean",
                "frozen_ocean", "deep_frozen_ocean", "mushroom_fields", "dripstone_caves", "lush_caves", "deep_dark", "pale_garden",
                "nether_wastes", "warped_forest", "crimson_forest", "soul_sand_valley", "basalt_deltas", "the_end", "end_highlands",
                "end_midlands", "small_end_islands", "end_barrens");
        for (String name : keys) {
            var tasks = ExplorationCatalog.tasks("minecraft:" + name);
            assertEquals(5, tasks.size(), name); assertEquals(5, tasks.stream().map(ExplorationCatalog.Task::id).distinct().count());
            assertTrue(tasks.stream().allMatch(t -> t.target() > 0));
            assertTrue(tasks.stream().filter(t -> t.kind() == ExplorationCatalog.Kind.KILL).noneMatch(t -> t.accepts("warden") || t.accepts("player")));
        }
        assertFalse(ExplorationCatalog.required("minecraft:the_void")); assertFalse(ExplorationCatalog.required("custom:forest"));
        assertFalse(ExplorationCatalog.required("minecraft:../../world"));
        assertThrows(IllegalArgumentException.class, () -> ExplorationCatalog.tasks("minecraft:the_void"));
    }
    @Test void ecologyFitsHostileAndPeacefulBiomesWithoutWardenOrWaterInNether() {
        assertEquals(ExplorationCatalog.Kind.FISH, ExplorationCatalog.tasks("minecraft:frozen_ocean").get(3).kind());
        assertEquals(ExplorationCatalog.Kind.STUDY, ExplorationCatalog.tasks("minecraft:deep_dark").get(3).kind());
        assertEquals(ExplorationCatalog.Kind.STUDY, ExplorationCatalog.tasks("minecraft:small_end_islands").get(3).kind());
        assertEquals(ExplorationCatalog.Kind.STUDY, ExplorationCatalog.tasks("minecraft:warped_forest").get(3).kind());
        assertTrue(ExplorationCatalog.tasks("minecraft:mushroom_fields").get(3).accepts("mooshroom"));
    }
    @Test void namespacesUUIDsUniquePositionsAndOverflowSurviveRestart() {
        var store = store(); var task = ExplorationCatalog.tasks("minecraft:plains").get(0);
        for (int i = 0; i < 8; i++) store.add("season:1", player, "minecraft:plains", task, 1, "world:" + i);
        assertEquals(8, store.progress("season:1", player, "minecraft:plains", "survey"));
        assertEquals(0, store.progress("lifetime", player, "minecraft:plains", "survey"));
        assertEquals(0, store.progress("season:2", player, "minecraft:plains", "survey"));
        assertFalse(store.visited("season:1", UUID.randomUUID(), "minecraft:plains"));
        assertTrue(store.save()); store = store();
        assertFalse(store.add("season:1", player, "minecraft:plains", task, Long.MAX_VALUE, "world:0"));
        assertEquals(8, store.progress("season:1", player, "minecraft:plains", "survey"));
        var path = ExplorationCatalog.tasks("minecraft:plains").get(1);
        assertTrue(store.add("season:1", player, "minecraft:plains", path, Long.MAX_VALUE, null));
        assertEquals(60_000, store.progress("season:1", player, "minecraft:plains", "path"));
    }
    @Test void seasonalCatalogIsFrozenButPermanentCollectionCanGrowWithoutLosingTrophies() {
        var s = store(); var one = List.of("minecraft:plains"); var two = List.of("minecraft:plains", "minecraft:desert");
        assertEquals(one, s.catalog("season:1", one)); assertEquals(one, s.catalog("season:1", two));
        assertEquals(2, s.catalog("season:2", two).size());
        s.catalog("lifetime", one); assertTrue(s.awardTrophy("lifetime", player, "visited"));
        assertEquals(2, s.catalog("lifetime", two).size()); assertTrue(s.trophy("lifetime", player, "visited"));
        assertTrue(s.save()); assertTrue(store().trophy("lifetime", player, "visited"));
    }
    @Test void permanentRankUpgradesAreIdempotentAndNeverDowngrade() {
        var s = store(); assertEquals(0, s.license(player)); assertTrue(s.award(player, 1));
        assertEquals(1, store().license(player)); assertTrue(s.award(player, 2));
        assertTrue(s.award(player, 1)); assertEquals(2, s.license(player));
        assertTrue(s.setTitle(player, "free")); assertEquals("free", store().title(player));
        assertTrue(s.setTitle(player, "off")); assertEquals(2, store().license(player));
        assertEquals(0, s.license(UUID.randomUUID())); assertFalse(s.award(player, 3));
    }
    @Test void failedCheckpointDoesNotAuthorizeRankOrMarker() throws Exception {
        var s = store(); Path tmp = dir.resolve("exploration.json.tmp"); Files.createDirectories(tmp); Files.writeString(tmp.resolve("blocked"), "x");
        assertFalse(s.award(player, 2)); assertEquals(0, s.license(player));
        Files.delete(tmp.resolve("blocked")); Files.delete(tmp); assertTrue(s.award(player, 1));
        Files.createDirectories(tmp); Files.writeString(tmp.resolve("blocked"), "x");
        assertFalse(s.mark(player, "home", point())); assertTrue(s.marks(player).isEmpty());
        assertFalse(s.setTitle(player, "off")); assertEquals("free", s.title(player));
    }
    ExplorationStore.Waypoint point() { return new ExplorationStore.Waypoint(UUID.randomUUID().toString(), 10, 64, 20); }
    @Test void freeAndPremiumLimitsDoNotStackRoutesCannotDangleAndSnapshotsAreImmutable() {
        var s = store(); s.award(player, 1);
        for (int i = 0; i < 8; i++) assertTrue(s.mark(player, "p" + i, point()));
        assertFalse(s.mark(player, "p8", point())); assertTrue(s.route(player, "trip", List.of("p0", "p1")));
        assertFalse(s.route(player, "trip2", List.of("p0"))); assertFalse(s.removeMark(player, "p0"));
        assertThrows(UnsupportedOperationException.class, () -> s.marks(player).clear());
        assertThrows(UnsupportedOperationException.class, () -> s.routes(player).get("trip").clear());
        assertTrue(s.award(player, 2));
        for (int i = 8; i < 24; i++) assertTrue(s.mark(player, "p" + i, point()));
        assertFalse(s.mark(player, "p24", point()));
        for (int i = 1; i < 5; i++) assertTrue(s.route(player, "trip" + i, List.of("p0")));
        assertFalse(s.route(player, "trip5", List.of("p0"))); assertFalse(s.route(player, "bad", List.of("missing")));
        assertFalse(s.mark(player, "<click:run_command>", point())); assertFalse(s.mark(player, "null", null));
        assertEquals(24, store().marks(player).size()); assertEquals(5, store().routes(player).size());
    }
    @Test void malformedFileFailsClosedAndIsNeverOverwritten() throws Exception {
        for (String data : List.of("null", "{not json", "{\"schema\":99}", "{\"schema\":1,\"scopes\":null}", "{\"schema\":1,\"scopes\":{\"lifetime\":{\"badUUID\":{}}}}")) {
            Files.writeString(dir.resolve("exploration.json"), data);
            var s = store(); assertFalse(s.healthy()); assertFalse(s.award(player, 2)); assertFalse(s.save());
            assertEquals(data, Files.readString(dir.resolve("exploration.json")));
        }
    }
    @Test void waypointRejectsNonFiniteCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> new ExplorationStore.Waypoint(UUID.randomUUID().toString(), Double.NaN, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> new ExplorationStore.Waypoint("notUUID", 1, 2, 3));
    }
}
