package ru.cherdakmd.chrdkachievements;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AchievementStoreTest {

    private static final Logger LOGGER = Logger.getLogger("AchievementStoreTest");
    private static final String PLAYER = "0f8fad5b-d9cb-469f-a165-70867728950e";

    @TempDir
    Path dir;

    @Test
    void countersAccumulateAndRaiseNeverLowers() {
        AchievementStore store = new AchievementStore(dir, LOGGER);
        assertEquals(5, store.add(PLAYER, AchievementMetric.MOBS_KILLED, 5));
        assertEquals(12, store.add(PLAYER, AchievementMetric.MOBS_KILLED, 7));
        assertEquals(12, store.value(PLAYER, AchievementMetric.MOBS_KILLED));
        // Отрицательные приращения игнорируются.
        assertEquals(12, store.add(PLAYER, AchievementMetric.MOBS_KILLED, -100));
        // raise поднимает только вверх.
        assertEquals(12, store.raise(PLAYER, AchievementMetric.MOBS_KILLED, 3));
        assertEquals(50, store.raise(PLAYER, AchievementMetric.MOBS_KILLED, 50));
        assertEquals(0, store.value("другой", AchievementMetric.MOBS_KILLED));
    }

    @Test
    void unlockAndClaimAreIdempotent() {
        AchievementStore store = new AchievementStore(dir, LOGGER);
        assertTrue(store.unlock(PLAYER, "ohotnik"));
        assertFalse(store.unlock(PLAYER, "ohotnik"));
        assertTrue(store.isUnlocked(PLAYER, "ohotnik"));
        assertFalse(store.isUnlocked(PLAYER, "groza-mobov"));

        assertTrue(store.markClaimed(PLAYER, "ohotnik"));
        assertFalse(store.markClaimed(PLAYER, "ohotnik"));
        assertEquals(java.util.Set.of("ohotnik"), store.claimed(PLAYER));
    }

    @Test
    void badgeAndProgressSurviveReload() {
        AchievementStore store = new AchievementStore(dir, LOGGER);
        store.add(PLAYER, AchievementMetric.PLAY_SECONDS, 3_600);
        store.unlock(PLAYER, "chas-v-puti");
        store.markClaimed(PLAYER, "chas-v-puti");
        store.setBadge(PLAYER, "veteran");
        store.flush();

        AchievementStore reloaded = new AchievementStore(dir, LOGGER);
        reloaded.load();
        assertEquals(3_600, reloaded.value(PLAYER, AchievementMetric.PLAY_SECONDS));
        assertTrue(reloaded.isUnlocked(PLAYER, "chas-v-puti"));
        assertEquals("veteran", reloaded.badge(PLAYER));
        assertFalse(reloaded.isDirty());
    }

    @Test
    void badgeCanBeClearedAndEmptyMeansNone() {
        AchievementStore store = new AchievementStore(dir, LOGGER);
        store.setBadge(PLAYER, "legenda");
        store.setBadge(PLAYER, "");
        assertNull(store.badge(PLAYER));
        store.setBadge(PLAYER, null);
        assertNull(store.badge(PLAYER));
    }

    @Test
    void brokenAndUnknownDataIsIgnored() throws Exception {
        Files.writeString(dir.resolve("achievements.json"), """
                {"version":1,"players":{"%s":{"counters":{"mobs-killed":10,
                "nesushchestvuyushchaya-metrika":99,"play-seconds":-5},
                "unlocked":["ohotnik"],"claimed":[],"badge":null}}}
                """.formatted(PLAYER).replace("\n", ""), StandardCharsets.UTF_8);

        AchievementStore store = new AchievementStore(dir, LOGGER);
        store.load();
        assertEquals(10, store.value(PLAYER, AchievementMetric.MOBS_KILLED));
        assertEquals(0, store.value(PLAYER, AchievementMetric.PLAY_SECONDS),
                "отрицательный счётчик из файла не должен приниматься");
        assertTrue(store.isUnlocked(PLAYER, "ohotnik"));
        assertNull(store.badge(PLAYER));
    }

    @Test
    void missingFileIsEmptyAndSaveCreatesDirectories() {
        AchievementStore store = new AchievementStore(dir.resolve("nested"), LOGGER);
        store.load();
        assertEquals(0, store.value(PLAYER, AchievementMetric.JOINS));
        store.add(PLAYER, AchievementMetric.JOINS, 1);
        store.saveNow();
        assertTrue(Files.isRegularFile(dir.resolve("nested").resolve("achievements.json")));
    }
}
