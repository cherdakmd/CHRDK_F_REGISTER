package ru.cherdakmd.chrdksocial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Гибридный grace: новичок на 15-й день не должен оказаться без срока на привязку. */
class SocialAccessPolicyTest {

    private static final long DAY = SocialAccessPolicy.DAY_MILLIS;
    private static final long ROLLOUT = 1_700_000_000_000L;

    @Test
    void legacyPlayerUsesGlobalDeadline() {
        long firstSeen = ROLLOUT + DAY;
        long deadline = SocialAccessPolicy.graceDeadline(ROLLOUT, firstSeen, 14, 14, ROLLOUT + 15 * DAY);
        assertEquals(ROLLOUT + 15 * DAY, deadline,
                "уже игравшему аккаунту хватает общего дедлайна");
        assertFalse(SocialAccessPolicy.graceActive(deadline, ROLLOUT + 15 * DAY));
        assertTrue(SocialAccessPolicy.graceActive(deadline, ROLLOUT + 14 * DAY));
    }

    @Test
    void newcomerOnDayFifteenGetsPersonalGrace() {
        long firstSeen = ROLLOUT + 15 * DAY;
        long deadline = SocialAccessPolicy.graceDeadline(ROLLOUT, firstSeen, 14, 14, firstSeen);
        assertEquals(firstSeen + 14 * DAY, deadline);
        assertTrue(SocialAccessPolicy.graceActive(deadline, firstSeen + 13 * DAY));
        assertFalse(SocialAccessPolicy.graceActive(deadline, firstSeen + 14 * DAY));
    }

    @Test
    void returningOldAccountAlsoGetsPersonalGrace() {
        long firstSeen = ROLLOUT + 60 * DAY;
        long deadline = SocialAccessPolicy.graceDeadline(ROLLOUT, firstSeen, 14, 3, firstSeen);
        assertEquals(firstSeen + 3 * DAY, deadline,
                "вернувшийся после общего дедлайна не застревает сразу");
    }

    @Test
    void zeroGraceMeansImmediateGate() {
        long now = ROLLOUT + 30 * DAY;
        long deadline = SocialAccessPolicy.graceDeadline(ROLLOUT, ROLLOUT, 0, 0, now);
        assertEquals(ROLLOUT, deadline);
        assertFalse(SocialAccessPolicy.graceActive(deadline, now));
    }

    @Test
    void laterOfTwoDeadlinesAlwaysWins() {
        long firstSeen = ROLLOUT + 40 * DAY;
        assertEquals(firstSeen + 30 * DAY,
                SocialAccessPolicy.graceDeadline(ROLLOUT, firstSeen, 14, 30, firstSeen));
        assertEquals(ROLLOUT + 90 * DAY,
                SocialAccessPolicy.graceDeadline(ROLLOUT, ROLLOUT, 90, 3, ROLLOUT));
    }

    @Test
    void unknownDonateModeIsFailClosed() {
        assertEquals(SocialAccessPolicy.MODE_OFF, SocialAccessPolicy.normalizeMode("whatever"));
        assertEquals(SocialAccessPolicy.MODE_OFF, SocialAccessPolicy.normalizeMode(null));
        assertEquals(SocialAccessPolicy.MODE_ANY_RANK, SocialAccessPolicy.normalizeMode(" ANY-RANK "));
        assertFalse(SocialAccessPolicy.modeActive(SocialAccessPolicy.MODE_OFF));
        assertFalse(SocialAccessPolicy.rankAllowed(SocialAccessPolicy.MODE_OFF));
        assertFalse(SocialAccessPolicy.productsAllowed(SocialAccessPolicy.MODE_ANY_RANK));
        assertTrue(SocialAccessPolicy.productsAllowed(SocialAccessPolicy.MODE_EITHER));
        assertTrue(SocialAccessPolicy.rankAllowed(SocialAccessPolicy.MODE_EITHER));
    }
}
