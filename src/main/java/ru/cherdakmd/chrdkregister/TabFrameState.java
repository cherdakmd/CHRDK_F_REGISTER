package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;

/** Последний кадр и счётчики: не отправляем дубликаты, замечаем чужую перезапись между heartbeat. */
record TabFrameState(Component expected, long changes, long repairs) {
    static TabFrameState next(TabFrameState previous, Component expected, Component actual) {
        return new TabFrameState(expected, previous == null ? 1
                : previous.changes() + (previous.expected().equals(expected) ? 0 : 1),
                previous == null ? 0 : previous.repairs() + (previous.needsDelivery(actual) ? 1 : 0));
    }
    boolean needsDelivery(Component actual) { return !expected.equals(actual); }
    static String audit(boolean enabled, boolean active, TabFrameState before, TabFrameState after, Component actual) {
        if (!enabled) return "disabled";
        if (!active) return "inactive";
        if (before == null || after == null) return "pending";
        if (after.needsDelivery(actual)) return "overwritten";
        // Конечный цвет может совпасть с начальным на симметричном участке pulse.
        if (after.changes() <= before.changes()) return "frozen";
        return after.repairs() > before.repairs() ? "repaired" : "ok";
    }
}
