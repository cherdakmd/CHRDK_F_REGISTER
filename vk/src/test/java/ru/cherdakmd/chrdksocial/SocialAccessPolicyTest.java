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
    void tabCountersUsePlainTextAndRoundDaysUp() {
        long now = ROLLOUT;
        assertEquals("30 дн.", SocialAccessPolicy.accessCounter(now + 30 * DAY, now, false));
        assertEquals("1 дн.", SocialAccessPolicy.accessCounter(now + 3600_000L, now, false));
        assertEquals("нет", SocialAccessPolicy.accessCounter(now, now, false));
        assertEquals("нет", SocialAccessPolicy.accessCounter(now - DAY, now, false));
        assertEquals("∞", SocialAccessPolicy.accessCounter(now + DAY, now, true));
        assertEquals("ВК", SocialAccessPolicy.linkCounter("vk", now, now, true, false));
        assertEquals("MAX", SocialAccessPolicy.linkCounter("max", now, now, true, false));
        assertEquals("не требуется", SocialAccessPolicy.linkCounter("", now, now, false, false));
        assertEquals("аварийный вход", SocialAccessPolicy.linkCounter("", now, now, true, true));
        assertEquals("обязательна", SocialAccessPolicy.linkCounter("", now - DAY, now, true, false));
        assertEquals("осталось 3 дн.", SocialAccessPolicy.linkCounter("", now + 3 * DAY, now, true, false));
        assertEquals(3, SocialAccessPolicy.remainingDays(now + 3 * DAY, now));
    }

    @Test
    void tabStatusNamesTheChoiceExplicitly() {
        long now = ROLLOUT;
        long deadline = now + 14 * DAY;
        assertEquals("✅ привязан (ВК)",
                SocialAccessPolicy.tabStatus(true, "vk", 0, deadline, now, true, false, false));
        assertEquals("✅ привязан (MAX)",
                SocialAccessPolicy.tabStatus(true, "max", 0, deadline, now, true, false, false));
        assertEquals("✅ проход: 27 дн.",
                SocialAccessPolicy.tabStatus(false, "", now + 27 * DAY, deadline, now, true, false, false));
        assertEquals("✅ проход: ∞",
                SocialAccessPolicy.tabStatus(false, "", 0, deadline, now, true, false, true));
        assertEquals("⚠ БЕЗ ПРИВЯЗКИ: осталось 14 дн.",
                SocialAccessPolicy.tabStatus(false, "", 0, deadline, now, true, false, false));
        assertEquals("⛔ ВХОД ЗАКРЫТ",
                SocialAccessPolicy.tabStatus(false, "", 0, now - DAY, now, true, false, false));
        assertEquals("✅ привязка не требуется",
                SocialAccessPolicy.tabStatus(false, "", 0, deadline, now, false, false, false));
        assertEquals("⚠ аварийный вход",
                SocialAccessPolicy.tabStatus(false, "", 0, now - DAY, now, true, true, false));
    }

    @Test
    void tabLineHasOneClearStepAndNoCounterAfterLink() {
        long now = ROLLOUT;
        long deadline = now + 14 * DAY;
        assertEquals("<green>✅ ВКонтакте привязан — можно играть</green>",
                SocialAccessPolicy.tabLine(true, "vk", 0, deadline, now, true, false, false));
        String linked = SocialAccessPolicy.tabLine(true, "max", now + 30 * DAY,
                deadline, now, true, false, true);
        assertEquals("<green>✅ MAX привязан — можно играть</green>", linked);
        assertFalse(linked.contains("дн."), "после привязки счётчик исчезает полностью");
        assertFalse(linked.contains("осталось"));

        assertEquals("<yellow>⚠ Привяжите ВКонтакте: /link → код боту. Осталось 14 дн.</yellow>",
                SocialAccessPolicy.tabLine(false, "", 0, deadline, now, true, false, false));
        assertEquals("<green>✅ Вход без ВК оплачен: 27 дн. · привязка не нужна</green>",
                SocialAccessPolicy.tabLine(false, "", now + 27 * DAY, deadline, now, true, false, false));
        assertEquals("<red>⛔ Вход закрыт: /link → код → бот ВКонтакте</red>",
                SocialAccessPolicy.tabLine(false, "", 0, now - DAY, now, true, false, false));
        assertEquals("<yellow>⚠ Аварийный вход: привяжите ВК через /link</yellow>",
                SocialAccessPolicy.tabLine(false, "", 0, now - DAY, now, true, true, false));
        assertEquals("<green>✅ Привязка не требуется — можно играть</green>",
                SocialAccessPolicy.tabLine(false, "", 0, deadline, now, false, false, false));
        assertEquals("ВКонтакте", SocialAccessPolicy.providerTitle("vk"));
        assertEquals("MAX", SocialAccessPolicy.providerTitle("max"));
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
