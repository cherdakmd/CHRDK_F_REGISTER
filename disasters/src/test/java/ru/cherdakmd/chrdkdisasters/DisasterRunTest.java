package ru.cherdakmd.chrdkdisasters;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Переходы фаз и остановка конкретного запуска без Bukkit. */
final class DisasterRunTest {

    private static DisasterRun run(DisasterPhasePlan plan) {
        return new DisasterRun(7, DisasterKind.TORNADO, 3,
                "Alex", "world", 10.5, 64.0, -5.5, plan);
    }

    @Test
    void walksAllPhasesAndCountsActiveSteps() {
        // warn 1 тик, active 2 тика, fade 1 тик, тик = 2 секунды.
        DisasterPhasePlan plan = DisasterPhasePlan.from(0, 4, 0, 2);
        DisasterRun run = run(plan);
        assertEquals(DisasterRun.Phase.WARNING, run.phase());
        assertEquals(2, run.secondsLeft());
        assertEquals(7, run.id());
        assertEquals(3, run.level());
        assertEquals("world", run.world());
        assertEquals(10.5, run.x());
        assertFalse(run.done());

        run.advance();
        assertEquals(DisasterRun.Phase.ACTIVE, run.phase());
        assertEquals(0, run.activeStep());

        run.countStep();
        run.addChanged(4);
        run.advance();
        assertEquals(DisasterRun.Phase.ACTIVE, run.phase(), "второй тик активной фазы");
        assertEquals(1, run.activeStep());

        run.countStep();
        run.advance();
        assertEquals(DisasterRun.Phase.FADING, run.phase());
        assertEquals(2, run.activeStep());

        run.addChanged(3);
        run.advance();
        assertTrue(run.done());
        assertEquals(0, run.ticksLeft());
        assertEquals(0, run.secondsLeft());
        assertEquals(7, run.changed());
        assertFalse(run.stopped());
    }

    @Test
    void manualStopFinishesImmediatelyAndIsRemembered() {
        DisasterRun run = run(DisasterPhasePlan.from(10, 20, 10, 2));
        run.countStep();
        assertEquals(0, run.activeStep(), "вне активной фазы шаги не считаются");
        run.stopNow();
        assertTrue(run.done());
        assertTrue(run.stopped());
        assertEquals(0, run.secondsLeft());
        run.advance();
        assertTrue(run.done(), "повторный тик не воскрешает запуск");
    }
}
