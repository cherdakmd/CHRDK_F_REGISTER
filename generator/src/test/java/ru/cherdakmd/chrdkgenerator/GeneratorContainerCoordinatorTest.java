package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class GeneratorContainerCoordinatorTest {
    @TempDir Path dir;
    UUID owner = UUID.randomUUID(), core = UUID.randomUUID();
    GeneratorStore source() { var s = new GeneratorStore(dir, Logger.getAnonymousLogger()); s.load(); assertTrue(s.healthy()); return s; }
    GeneratorContainerStore target() { var t = new GeneratorContainerStore(dir, Logger.getAnonymousLogger()); t.load(); assertTrue(t.healthy()); return t; }
    GeneratorStore ready(long legacy) throws Exception {
        var s = source(); s.markRegistered(); s.install(owner, core, "world", 0, 64, 0, 100, 3);
        s.addBunker(owner, 0, legacy, 100); s.flush(); return s;
    }
    GeneratorContainerCoordinator.Result run(GeneratorStore s, GeneratorContainerStore t, long now, boolean working) {
        return GeneratorContainerCoordinator.run(s, t, owner, 0, now,
                List.of(new GeneratorDrops.Drop("GOLD_INGOT", 1, false)), 0,
                frozen -> frozen.drops().get(0).material(), () -> "COBBLESTONE", x -> 64,
                x -> x.equals("COAL") ? new GeneratorFurnaceFuel.Fuel(x, 3000, null) : null,
                units -> units * 100, working, 1000, 1, 0, true, .5, 3_600_000, 5000);
    }
    @Test void migrationAndNewProductionUseSeparateFrozenDropsAndAtomicReward() throws Exception {
        var s = ready(10); var t = target();
        assertEquals(GeneratorContainerCoordinator.Result.READY, run(s, t, 100, true));
        assertEquals(0, source().entry(owner, 0).bunker());
        var v = t.view(owner, 0); assertEquals("GOLD_INGOT", v.slots().get(0).material());
        var slots = new ArrayList<>(v.slots()); slots.set(36, new GeneratorContainerStore.Stack("COAL", 2));
        assertTrue(t.settle(owner, 0, core, v.revision(), slots, 0, 100));
        assertEquals(GeneratorContainerCoordinator.Result.READY, run(s, t, 5100, true));
        var after = target().view(owner, 0);
        assertEquals(new GeneratorContainerStore.Stack("COBBLESTONE", 5), after.slots().get(1));
        assertEquals(1000, after.burnMillis());
        assertEquals(500, target().saleState(owner, 0).moneyDebt());
        assertEquals(GeneratorContainerCoordinator.Result.READY, run(source(), target(), 5100, true));
        assertEquals(500, target().saleState(owner, 0).moneyDebt());
    }
    @Test void overflowStopsNewProductionAndDoesNotConsumeFuel() throws Exception {
        var s = ready(3000); var t = target();
        assertEquals(GeneratorContainerCoordinator.Result.BACKLOG, run(s, t, 100, true));
        var v = t.view(owner, 0); var slots = new ArrayList<>(v.slots());
        slots.set(36, new GeneratorContainerStore.Stack("COAL", 1));
        assertTrue(t.settle(owner, 0, core, v.revision(), slots, 0, 100));
        assertEquals(GeneratorContainerCoordinator.Result.BACKLOG, run(s, t, 5100, true));
        assertEquals(5100, target().view(owner, 0).lastCalcMillis());
        assertEquals(1, target().view(owner, 0).slots().get(36).amount());
        assertEquals(696, source().entry(owner, 0).bunker());
    }
    @Test void brokenChamberDiscardsIdleWithoutBurningFuel() throws Exception {
        var s = ready(0); var t = target();
        assertEquals(GeneratorContainerCoordinator.Result.READY, run(s, t, 100, false));
        var v = t.view(owner, 0); var slots = new ArrayList<>(v.slots());
        slots.set(36, new GeneratorContainerStore.Stack("COAL", 1));
        assertTrue(t.settle(owner, 0, core, v.revision(), slots, 0, 100));
        assertEquals(GeneratorContainerCoordinator.Result.READY, run(s, t, 5100, false));
        assertEquals(5100, target().view(owner, 0).lastCalcMillis());
        assertEquals(1, target().view(owner, 0).slots().get(36).amount());
    }
}
