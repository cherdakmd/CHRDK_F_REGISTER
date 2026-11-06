package ru.cherdakmd.chrdksocial;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Кулдаун не душит разные события и разных игроков, но гасит всплески. */
class SocialEventThrottleTest {

    @Test
    void sameEventForSamePlayerIsThrottled() {
        AtomicLong now = new AtomicLong(1_000L);
        SocialEventThrottle throttle = new SocialEventThrottle(now::get);
        assertTrue(throttle.allow("DEATH", "Steve", 30_000L));
        assertFalse(throttle.allow("DEATH", "Steve", 30_000L));
        now.addAndGet(29_999L);
        assertFalse(throttle.allow("DEATH", "Steve", 30_000L));
        now.addAndGet(1L);
        assertTrue(throttle.allow("DEATH", "Steve", 30_000L));
    }

    @Test
    void otherPlayersAndOtherKindsPassThrough() {
        AtomicLong now = new AtomicLong(1_000L);
        SocialEventThrottle throttle = new SocialEventThrottle(now::get);
        assertTrue(throttle.allow("DEATH", "Steve", 30_000L));
        assertTrue(throttle.allow("DEATH", "Alex", 30_000L));
        assertTrue(throttle.allow("WELCOME", "Steve", 30_000L));
        assertTrue(throttle.allow("SERVER_START", "", 30_000L));
        assertFalse(throttle.allow("SERVER_START", "", 30_000L));
    }

    @Test
    void zeroCooldownAllowsEveryEventAndClearResetsState() {
        AtomicLong now = new AtomicLong(1L);
        SocialEventThrottle throttle = new SocialEventThrottle(now::get);
        assertTrue(throttle.allow("DEATH", "Steve", 0L));
        assertTrue(throttle.allow("DEATH", "Steve", 0L));
        assertFalse(throttle.allow("DEATH", "Steve", 30_000L),
                "последняя отметка всё ещё свежая");
        throttle.clear();
        assertTrue(throttle.allow("DEATH", "Steve", 30_000L));
    }
}
