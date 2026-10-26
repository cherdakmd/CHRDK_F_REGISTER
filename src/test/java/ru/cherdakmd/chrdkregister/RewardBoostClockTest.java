package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RewardBoostClockTest {
    private static final UUID ID = UUID.randomUUID();
    private static final long SECOND = 1_000_000_000L;

    @Test void spendsOnlyBetweenAuthenticationAndPause() {
        AtomicLong spent = new AtomicLong();
        var clock = new RewardBoostClock((name, millis) -> spent.addAndGet(millis));
        clock.pause(ID, 100 * SECOND); // connected, but not authenticated
        assertEquals(0, spent.get());
        clock.resume(ID, "Free", 100 * SECOND);
        clock.sample(ID, "Free", 101 * SECOND);
        clock.pause(ID, 101 * SECOND + SECOND / 2);
        assertEquals(1500, spent.get());
        clock.pause(ID, 200 * SECOND); // offline time is not charged
        assertEquals(1500, spent.get());
        clock.resume(ID, "Free", 200 * SECOND);
        clock.stop(202 * SECOND);
        assertEquals(3500, spent.get());
        clock.stop(900 * SECOND);
        assertEquals(3500, spent.get());
    }

    @Test void restartDoesNotApplyOfflineWallClockTime() {
        AtomicLong spent = new AtomicLong();
        var first = new RewardBoostClock((name, millis) -> spent.addAndGet(millis));
        first.resume(ID, "Free", 0);
        first.stop(2 * SECOND);
        var second = new RewardBoostClock((name, millis) -> spent.addAndGet(millis));
        second.pause(ID, 10_000 * SECOND);
        assertEquals(2000, spent.get());
        second.resume(ID, "Free", 10_000 * SECOND);
        second.stop(10_001 * SECOND);
        assertEquals(3000, spent.get());
    }

    @Test void duplicateAuthDoesNotResetTimeAndSubmillisecondFractionsAccumulate() {
        AtomicLong spent = new AtomicLong();
        var clock = new RewardBoostClock((name, millis) -> spent.addAndGet(millis));
        clock.resume(ID, "Free", 0);
        clock.resume(ID, "Free", SECOND);
        clock.sample(ID, "Free", SECOND + 500_000);
        assertEquals(1000, spent.get());
        clock.sample(ID, "Free", SECOND + 1_000_000);
        assertEquals(1001, spent.get());
    }
}
