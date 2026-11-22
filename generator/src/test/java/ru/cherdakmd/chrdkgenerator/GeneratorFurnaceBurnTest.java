package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GeneratorFurnaceBurnTest {
    private List<GeneratorContainerStore.Stack> empty() {
        return new ArrayList<>(Collections.nCopies(GeneratorContainerStore.SLOTS, null));
    }
    private GeneratorFurnaceFuel.Fuel fuel(String material) {
        return switch (material) {
            case "COAL" -> new GeneratorFurnaceFuel.Fuel(material, 80_000, null);
            case "LAVA_BUCKET" -> new GeneratorFurnaceFuel.Fuel(material, 1_000_000, "BUCKET");
            default -> null;
        };
    }
    private GeneratorFurnaceBurn.Result consume(List<GeneratorContainerStore.Stack> slots, long burn, long time) {
        return GeneratorFurnaceBurn.consume(slots, burn, time, this::fuel, x -> x.equals("LAVA_BUCKET") ? 1 : 64);
    }
    @Test void consumptionContinuesAcrossDifferentFuelAndRetainsBucket() {
        var slots = empty();
        slots.set(36, new GeneratorContainerStore.Stack("COAL", 2));
        slots.set(37, new GeneratorContainerStore.Stack("LAVA_BUCKET", 1));
        var result = consume(slots, 0, 200_000);
        assertEquals(200_000, result.usedMillis());
        assertEquals(960_000, result.burnMillis());
        assertEquals(new GeneratorContainerStore.Stack("BUCKET", 1), result.slots().get(36));
        assertNull(result.slots().get(37));
        assertEquals(2, slots.get(36).amount());
    }
    @Test void existingBurnIsUsedBeforeNewFuelAndShortageDoesNotCreateCredit() {
        var slots = empty(); slots.set(36, new GeneratorContainerStore.Stack("COAL", 1));
        var result = consume(slots, 10_000, 100_000);
        assertEquals(90_000, result.usedMillis());
        assertEquals(0, result.burnMillis());
        assertNull(result.slots().get(36));
        assertEquals(0, consume(result.slots(), result.burnMillis(), 10_000).usedMillis());
    }
    @Test void fullFuelAreaDoesNotDestroyRemainder() {
        var slots = empty();
        slots.set(36, new GeneratorContainerStore.Stack("LAVA_BUCKET", 2));
        for (int i = 37; i < 45; i++) slots.set(i, new GeneratorContainerStore.Stack("BUCKET", 64));
        var result = consume(slots, 0, 1);
        assertEquals(0, result.usedMillis());
        assertEquals(slots, result.slots());
    }
    @Test void zeroElapsedNeverLoadsFuel() {
        var slots = empty(); slots.set(36, new GeneratorContainerStore.Stack("COAL", 1));
        assertEquals(slots, consume(slots, 0, 0).slots());
    }
}
