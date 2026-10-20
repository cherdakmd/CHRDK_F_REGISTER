package ru.cherdakmd.chrdkstreams;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Арифметика наград: час эфира = сутки, дневные и общие потолки, переход через полночь. */
class StreamRewardsTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final long NOW = 1_800_000_000_000L;

    private static final StreamRewards.Rule HOURLY =
            StreamRewards.Rule.of(60, 6, 365);

    @Test
    void oneHourGivesOneDay() {
        StreamRewards.Decision decision =
                StreamRewards.advance(StreamRewards.State.empty(), HOUR, NOW, HOURLY);

        assertEquals(1, decision.awardDays());
        assertTrue(decision.awarded());
        assertEquals(1, decision.state().totalDays());
        assertEquals(0L, decision.state().pendingMillis());
        assertEquals(1, decision.state().awardedToday());
        assertEquals(StreamRewards.dayOf(NOW), decision.state().day());
    }

    @Test
    void partialHourIsKept() {
        StreamRewards.Decision first =
                StreamRewards.advance(StreamRewards.State.empty(), 45 * MINUTE, NOW, HOURLY);
        assertEquals(0, first.awardDays());
        assertEquals(45 * MINUTE, first.state().pendingMillis());
        assertEquals(15, StreamRewards.minutesToNextDay(first.state(), HOURLY));

        StreamRewards.Decision second =
                StreamRewards.advance(first.state(), 15 * MINUTE, NOW + MINUTE, HOURLY);
        assertEquals(1, second.awardDays());
        assertEquals(1, second.state().totalDays());
    }

    @Test
    void pendingNeverReachesFullDay() {
        // Пока дневной потолок выбран, минуты копятся, но не больше «почти суток».
        StreamRewards.State capped = new StreamRewards.State(
                StreamRewards.dayOf(NOW), 6, 0L, 6);
        StreamRewards.Decision decision =
                StreamRewards.advance(capped, 10 * HOUR, NOW, HOURLY);

        assertEquals(0, decision.awardDays(), "шесть наград уже получены — седьмой нет");
        assertEquals(HOUR - 1, decision.state().pendingMillis(),
                "остаток ограничен порогом минус миллисекунда");
    }

    @Test
    void dailyCapStopsAwards() {
        StreamRewards.Rule rule = StreamRewards.Rule.of(60, 2, 0);
        StreamRewards.State fresh = new StreamRewards.State(StreamRewards.dayOf(NOW), 0, 0L, 0);

        StreamRewards.Decision decision = StreamRewards.advance(fresh, 5 * HOUR, NOW, rule);

        assertEquals(2, decision.awardDays(), "не больше двух суток за календарный день");
        assertEquals(2, decision.state().awardedToday());
        assertEquals(2, decision.state().totalDays());
        assertEquals(HOUR - 1, decision.state().pendingMillis());
    }

    @Test
    void totalCapStopsAwards() {
        StreamRewards.Rule rule = StreamRewards.Rule.of(60, 24, 3);
        StreamRewards.State almost = new StreamRewards.State(
                StreamRewards.dayOf(NOW), 0, 0L, 2);

        StreamRewards.Decision decision = StreamRewards.advance(almost, 3 * HOUR, NOW, rule);

        assertEquals(1, decision.awardDays(), "до общего потолка остались одни сутки");
        assertEquals(3, decision.state().totalDays());
    }

    @Test
    void zeroTotalCapMeansNoLimit() {
        StreamRewards.Rule rule = StreamRewards.Rule.of(60, 24, 0);
        StreamRewards.State many = new StreamRewards.State(StreamRewards.dayOf(NOW), 0, 0L, 500);

        StreamRewards.Decision decision = StreamRewards.advance(many, 2 * HOUR, NOW, rule);

        assertEquals(2, decision.awardDays());
        assertEquals(502, decision.state().totalDays());
    }

    @Test
    void newDayResetsDailyCounterButKeepsPending() {
        int day = StreamRewards.dayOf(NOW);
        StreamRewards.State yesterday = new StreamRewards.State(day, 6, 30 * MINUTE, 6);
        long tomorrow = NOW + DAY;

        StreamRewards.Decision decision =
                StreamRewards.advance(yesterday, 40 * MINUTE, tomorrow, HOURLY);

        assertEquals(1, decision.awardDays(), "на следующий день счётчик снова полон");
        assertEquals(1, decision.state().awardedToday());
        assertEquals(7, decision.state().totalDays());
        assertEquals(10 * MINUTE, decision.state().pendingMillis(), "минуты не сгорают в полночь");
        assertEquals(StreamRewards.dayOf(tomorrow), decision.state().day());
    }

    @Test
    void negativeCreditIsIgnored() {
        StreamRewards.State state = new StreamRewards.State(
                StreamRewards.dayOf(NOW), 0, 10 * MINUTE, 0);

        StreamRewards.Decision decision =
                StreamRewards.advance(state, -5 * MINUTE, NOW, HOURLY);

        assertEquals(0, decision.awardDays());
        assertEquals(10 * MINUTE, decision.state().pendingMillis());
    }

    @Test
    void minutesToNextDayRoundsUp() {
        StreamRewards.State state = new StreamRewards.State(
                StreamRewards.dayOf(NOW), 0, 59 * MINUTE + 30_000L, 0);
        assertEquals(1, StreamRewards.minutesToNextDay(state, HOURLY));
        assertEquals(59, StreamRewards.pendingMinutes(state, HOURLY));
    }

    @Test
    void ruleClampsBrokenConfig() {
        StreamRewards.Rule rule = StreamRewards.Rule.of(0, 100, -5);

        assertEquals(1, rule.minutesPerDay(), "порог не может быть нулевым");
        assertEquals(StreamRewards.MAX_DAYS_PER_DAY, rule.maxDaysPerDay());
        assertEquals(0, rule.maxTotalDays(), "0 — без общего потолка");
        assertEquals(Integer.MAX_VALUE, rule.remainingTotal(10));
    }
}
