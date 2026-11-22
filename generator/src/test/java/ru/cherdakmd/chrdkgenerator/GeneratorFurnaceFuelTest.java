package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorFurnaceFuelTest {
    @Test void furnaceDurationsAreConvertedWithoutHardcodedFuelList() {
        assertEquals(new GeneratorFurnaceFuel.Fuel("COAL", 80_000, null),
                GeneratorFurnaceFuel.definition("COAL", 1_600, null).orElseThrow());
        assertEquals(new GeneratorFurnaceFuel.Fuel("LAVA_BUCKET", 1_000_000, "BUCKET"),
                GeneratorFurnaceFuel.definition("LAVA_BUCKET", 20_000, "BUCKET").orElseThrow());
        assertEquals(new GeneratorFurnaceFuel.Fuel("BAMBOO", 2_500, null),
                GeneratorFurnaceFuel.definition("BAMBOO", 50, null).orElseThrow());
    }

    @Test void malformedOrOverflowingDefinitionsNeverBecomeFuel() {
        assertTrue(GeneratorFurnaceFuel.definition("COAL", 0, null).isEmpty());
        assertTrue(GeneratorFurnaceFuel.definition("COAL", -1, null).isEmpty());
        assertTrue(GeneratorFurnaceFuel.definition("COAL", Long.MAX_VALUE, null).isEmpty());
        assertTrue(GeneratorFurnaceFuel.definition("coal", 10, null).isEmpty());
        assertTrue(GeneratorFurnaceFuel.definition("COAL", 10, "bucket").isEmpty());
        assertTrue(GeneratorFurnaceFuel.definition(null, 10, null).isEmpty());
    }

    @Test void plainVanillaItemAndServerRegistryAreRequired() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkgenerator/GeneratorFurnaceFuel.java"));
        assertTrue(source.contains("stack.isSimilar(new ItemStack(material))"));
        assertTrue(source.contains("itemType.isFuel()"));
        assertTrue(source.contains("itemType.getBurnDuration()"));
        assertTrue(source.contains("itemType.getCraftingRemainingItem()"));
    }

    @Test void startsExactlyOnePieceAndNeverBurnsAnotherWhilePreviousIsActive() {
        var coal = GeneratorFurnaceFuel.definition("COAL", 1600, null).orElseThrow();
        var stack = new GeneratorFuelLoading.Stack(coal, 3);
        var plan = GeneratorFuelLoading.start(stack, 0, false);
        assertEquals(new GeneratorFuelLoading.Stack(coal, 2), plan.remaining());
        assertEquals(80_000, plan.burnMillis());
        assertNull(plan.remainder());
        assertEquals(new GeneratorFuelLoading.Plan(plan.remaining(), 70_000, null),
                GeneratorFuelLoading.start(plan.remaining(), 70_000, false));
    }

    @Test void remainderMustFitBeforeAContainerFuelIsConsumed() {
        var lava = GeneratorFurnaceFuel.definition("LAVA_BUCKET", 20_000, "BUCKET").orElseThrow();
        var stack = new GeneratorFuelLoading.Stack(lava, 1);
        assertEquals(new GeneratorFuelLoading.Plan(stack, 0, null), GeneratorFuelLoading.start(stack, 0, false));
        assertEquals(new GeneratorFuelLoading.Plan(null, 1_000_000, "BUCKET"),
                GeneratorFuelLoading.start(stack, 0, true));
        assertThrows(IllegalArgumentException.class, () -> GeneratorFuelLoading.start(stack, -1, true));
    }
}
