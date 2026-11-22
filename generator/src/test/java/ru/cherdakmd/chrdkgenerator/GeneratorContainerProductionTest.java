package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class GeneratorContainerProductionTest {
    private GeneratorContainerStore.View view(long burn, long time, int coal) {
        var slots = new ArrayList<GeneratorContainerStore.Stack>(Collections.nCopies(45, null));
        if (coal > 0) slots.set(36, new GeneratorContainerStore.Stack("COAL", coal));
        return new GeneratorContainerStore.View(UUID.randomUUID(), 0, slots, burn, time, false, null);
    }
    private GeneratorContainerProduction.Plan plan(GeneratorContainerStore.View v, long now, long room) {
        return GeneratorContainerProduction.plan(v, now, 1000, 1, 0, true, 0.5, 3_600_000, room,
                s -> s.equals("COAL") ? new GeneratorFurnaceFuel.Fuel(s, 3000, null) : null,
                s -> 64, () -> "COBBLESTONE");
    }
    @Test void outputAndFuelShareOnePlan() {
        var p = plan(view(0, 100, 2), 5100, 100);
        assertEquals(5, p.units());
        assertEquals(1000, p.burnMillis());
        assertEquals(5100, p.lastCalcMillis());
        assertEquals(new GeneratorContainerStore.Stack("COBBLESTONE", 5), p.slots().get(0));
        assertNull(p.slots().get(36));
    }
    @Test void noFuelDiscardsIdleAndRefillCannotPayForPast() {
        var p = plan(view(0, 100, 0), 5100, 100);
        assertEquals(0, p.units());
        assertEquals(5100, p.lastCalcMillis());
    }
    @Test void partialOnlineCycleDoesNotConsumeFuelPrematurely() {
        var p = plan(view(3000, 100, 1), 600, 100);
        assertEquals(0, p.units());
        assertEquals(100, p.lastCalcMillis());
        assertEquals(3000, p.burnMillis());
        assertEquals(1, p.slots().get(36).amount());
    }
    @Test void fullCapacityStopsFuelConsumptionAndForgetsIdle() {
        var p = plan(view(3000, 100, 1), 5100, 0);
        assertEquals(0, p.units());
        assertEquals(5100, p.lastCalcMillis());
        assertEquals(3000, p.burnMillis());
    }
}
