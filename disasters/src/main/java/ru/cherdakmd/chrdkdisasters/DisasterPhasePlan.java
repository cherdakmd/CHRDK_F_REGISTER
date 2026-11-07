package ru.cherdakmd.chrdkdisasters;

/**
 * План фаз одной катастрофы в тиках движка. Чистая арифметика без Bukkit:
 * владелец задаёт секунды в config.yml, движок считает по ним шаги активной
 * фазы. Даже нулевая длительность даёт один тик, чтобы предупреждение и
 * затухание всегда успели показаться игроку.
 */
public record DisasterPhasePlan(
        int warnTicks,
        int activeTicks,
        int fadeTicks,
        int tickSeconds,
        int warnSeconds,
        int activeSeconds,
        int fadeSeconds) {

    public static DisasterPhasePlan from(int warnSeconds, int activeSeconds,
                                         int fadeSeconds, int tickSeconds) {
        int tick = Math.max(1, tickSeconds);
        int warn = Math.max(0, warnSeconds);
        int active = Math.max(1, activeSeconds);
        int fade = Math.max(0, fadeSeconds);
        return new DisasterPhasePlan(ticks(warn, tick), ticks(active, tick),
                ticks(fade, tick), tick, warn, active, fade);
    }

    private static int ticks(int seconds, int tickSeconds) {
        return Math.max(1, (Math.max(0, seconds) + tickSeconds - 1) / tickSeconds);
    }

    public int totalTicks() {
        return warnTicks + activeTicks + fadeTicks;
    }

    public int totalSeconds() {
        return totalTicks() * tickSeconds;
    }

    /** Равномерно раскладывает лимит блоков по активным тикам без хвостов. */
    public int budget(int total, int step) {
        if (total <= 0 || activeTicks <= 0 || step < 0) {
            return 0;
        }
        int base = total / activeTicks;
        return base + (step < total % activeTicks ? 1 : 0);
    }
}
