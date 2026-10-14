package ru.cherdakmd.chrdklottery;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class DrawScheduleTest {
    private static final DayOfWeek DAY = DayOfWeek.SUNDAY;
    private static final LocalTime TIME = LocalTime.of(20, 0);
    private ZonedDateTime at(String time) { return ZonedDateTime.parse(time + "+03:00[Europe/Moscow]"); }
    @Test void beforeAfterAndExactBoundary() {
        var sunday = at("2026-10-18T20:00:00");
        assertEquals(sunday, DrawSchedule.nextBoundary(at("2026-10-18T19:59:59"), DAY, TIME));
        assertEquals(sunday.plusWeeks(1), DrawSchedule.nextBoundary(sunday, DAY, TIME));
        assertEquals(sunday, DrawSchedule.lastBoundary(sunday, DAY, TIME));
        assertEquals(sunday.minusWeeks(1), DrawSchedule.lastBoundary(at("2026-10-18T19:59:59"), DAY, TIME));
        assertEquals(sunday.plusWeeks(1), DrawSchedule.nextBoundary(at("2026-10-18T20:00:01"), DAY, TIME));
    }
    @Test void monthYearAndTimezone() {
        var now = at("2026-12-31T23:00:00");
        assertEquals(at("2027-01-03T20:00:00"), DrawSchedule.nextBoundary(now, DAY, TIME));
        assertEquals(ZoneId.of("Europe/Moscow"), DrawSchedule.nextBoundary(now, DAY, TIME).getZone());
    }
    @Test void followsWallClockAcrossDst() {
        var now = ZonedDateTime.parse("2026-03-28T12:00:00+01:00[Europe/Berlin]");
        var next = DrawSchedule.nextBoundary(now, DAY, TIME);
        assertEquals(LocalTime.of(20, 0), next.toLocalTime());
        assertEquals(ZoneOffset.ofHours(2), next.getOffset());
    }
}
