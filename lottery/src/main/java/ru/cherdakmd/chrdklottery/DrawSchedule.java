package ru.cherdakmd.chrdklottery;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * Расписание розыгрышей: ближайшая будущая и ближайшая прошедшая границы
 * «день недели + время» в заданной таймзоне. Без Bukkit — покрыто юнит-тестами.
 */
public final class DrawSchedule {

    private DrawSchedule() {
    }

    /**
     * Ближайший будущий момент розыгрыша строго после {@code after}.
     * Пример: сейчас воскресенье 19:00, розыгрыш в воскресенье 20:00 → сегодняшнее 20:00;
     * сейчас воскресенье 21:00 → воскресенье через неделю.
     */
    public static ZonedDateTime nextBoundary(ZonedDateTime after, DayOfWeek day, LocalTime time) {
        ZonedDateTime candidate = after.with(time).with(TemporalAdjusters.nextOrSame(day));
        if (!candidate.isAfter(after)) {
            candidate = candidate.plusWeeks(1);
        }
        return candidate;
    }

    /** Ближайший уже наступивший (или идущий прямо сейчас) момент розыгрыша. */
    public static ZonedDateTime lastBoundary(ZonedDateTime now, DayOfWeek day, LocalTime time) {
        return nextBoundary(now, day, time).minusWeeks(1);
    }
}
