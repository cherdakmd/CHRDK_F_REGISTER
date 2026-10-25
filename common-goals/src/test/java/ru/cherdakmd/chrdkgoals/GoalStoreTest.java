package ru.cherdakmd.chrdkgoals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalStoreTest {

    private GoalStore newStore(Path dir) {
        return new GoalStore(dir, Logger.getAnonymousLogger());
    }

    @Test
    void progressCapsAtTargetAndCompletesOnce(@TempDir Path dir) {
        GoalStore store = newStore(dir);
        store.replaceGoals(List.of(new GoalState("hostile-slay", 10, 96)), 1000L);
        assertFalse(store.addProgress("hostile-slay", "Alex", 5));
        assertTrue(store.addProgress("hostile-slay", "Alex", 9), "добивка — завершение");
        assertFalse(store.addProgress("hostile-slay", "Alex", 1), "уже выполнена — не растёт");
        GoalState goal = store.goal("hostile-slay").orElseThrow();
        assertEquals(10, goal.progress);
        assertTrue(goal.done);
        assertEquals(14, goal.contributionOf("alex"));
        assertEquals(100, goal.percent());
        assertTrue(goal.leader().startsWith("alex"));
    }

    @Test
    void pendingRewardsRoundTripAndClaim(@TempDir Path dir) {
        GoalStore store = newStore(dir);
        store.addPendingReward("Alex", 5);
        store.addPendingReward("alex", 7);
        assertEquals(12, store.pendingRewardOf("ALEX"));
        store.markDirty();
        store.save();

        GoalStore reloaded = newStore(dir);
        reloaded.load();
        assertEquals(12, reloaded.pendingRewardOf("Alex"));
        assertEquals(12, reloaded.claim("alex"));
        assertEquals(0, reloaded.claim("Alex"), "второй раз — пусто");
    }

    @Test
    void needRotationOnEmptyOrDeadline(@TempDir Path dir) {
        GoalStore store = newStore(dir);
        long now = 1_000_000L;
        assertTrue(store.needRotation(now), "пустая волна — надо ротировать");
        store.replaceGoals(List.of(new GoalState("fish-caught", 100, 48)), now + 1000);
        assertFalse(store.needRotation(now));
        assertTrue(store.needRotation(now + 1000));
        assertEquals(1, store.rotationsDone());
    }

    @Test
    void setProgressClamps(@TempDir Path dir) {
        GoalStore store = newStore(dir);
        store.replaceGoals(List.of(new GoalState("ore-break", 100, 48)), 1000L);
        store.setProgress("ore-break", 250);
        assertEquals(100, store.goal("ore-break").orElseThrow().progress);
        store.addProgress("ore-break", "Alex", 0);
        assertTrue(store.goal("ore-break").orElseThrow().done);
    }

    @Test
    void metricsRoundTripPersistsContributions(@TempDir Path dir) {
        GoalStore store = newStore(dir);
        store.replaceGoals(List.of(new GoalState("donate-diamonds", 2000, 192)), 5_000L);
        store.addProgress("donate-diamonds", "Alice", 500);
        store.markDirty();
        store.save();
        GoalStore reloaded = newStore(dir);
        reloaded.load();
        GoalState goal = reloaded.goal("donate-diamonds").orElseThrow();
        assertEquals(500, goal.contributionOf("alice"));
        assertEquals(5_000L, reloaded.rotationEndsAt());
    }
}
