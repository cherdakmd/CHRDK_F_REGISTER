package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Повышение уровня класса (1.4.173): первый пульс молчит, рост ловится,
 * падение не считается повышением.
 */
class ClassProgressTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    @Test
    void firstObservationIsSilent() {
        ClassProgress progress = new ClassProgress();
        assertNull(progress.observe(PLAYER, 12, 3), "вход не должен быть повышением");
        assertEquals(1, progress.tracked());
    }

    @Test
    void levelGrowthIsReported() {
        ClassProgress progress = new ClassProgress();
        progress.observe(PLAYER, 12, 3);
        ClassProgress.Change change = progress.observe(PLAYER, 13, 4);
        assertNotNull(change);
        assertEquals(13, change.level());
        assertEquals(4, change.points());
        assertNull(progress.observe(PLAYER, 13, 4), "тот же уровень — молчим");
    }

    @Test
    void dropIsNotReportedButIsRemembered() {
        ClassProgress progress = new ClassProgress();
        progress.observe(PLAYER, 20, 5);
        assertNull(progress.observe(PLAYER, 3, 1), "навыки могли не загрузиться — не повышение");
        ClassProgress.Change change = progress.observe(PLAYER, 4, 1);
        assertNotNull(change, "после просадки рост снова считается");
        assertEquals(4, change.level());
    }

    @Test
    void baselineIsSilentAndForgetClearsIt() {
        ClassProgress progress = new ClassProgress();
        progress.baseline(PLAYER, 30);
        assertNull(progress.observe(PLAYER, 30, 7));
        progress.forget(PLAYER);
        assertEquals(0, progress.tracked());
        assertNull(progress.observe(PLAYER, 31, 8), "после сброса первое наблюдение молчит");
    }

    @Test
    void nullAndZeroAreHandled() {
        ClassProgress progress = new ClassProgress();
        assertNull(progress.observe(null, 5, 1));
        progress.baseline(null, 5);
        progress.forget(null);
        assertEquals(0, progress.tracked());
        assertNull(progress.observe(PLAYER, 0, 0), "уровень хотя бы первый");
    }
}
