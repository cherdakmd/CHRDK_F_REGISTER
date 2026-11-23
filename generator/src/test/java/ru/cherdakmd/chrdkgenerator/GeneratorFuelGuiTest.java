package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GeneratorFuelGuiTest {
    @Test void displaysActualFurnaceTimeIncludingFractions() {
        assertEquals("0с",GeneratorContainerGui.duration(0));
        assertEquals("0.05с",GeneratorContainerGui.duration(50));
        assertEquals("1м 20с",GeneratorContainerGui.duration(80_000));
        assertEquals("1ч 1м 1.5с",GeneratorContainerGui.duration(3_661_500));
        assertThrows(IllegalArgumentException.class,()->GeneratorContainerGui.duration(-1));
    }
    @Test void splitViewsStillUseJournalledTransferAndBurnModel() throws Exception {
        var source=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkgenerator/GeneratorContainerGui.java"));
        assertTrue(source.contains("GeneratorFurnaceBurn.consume(view.slots(),view.burnMillis(),Long.MAX_VALUE"));
        assertTrue(source.contains("transfer.deposit(player, holder.slot, event.getSlot(), target)"));
        assertTrue(source.contains("transfer.withdraw(player, holder.slot, inputIndex)"));
        assertTrue(source.contains("raw + GeneratorContainerStore.RESOURCE_SLOTS"));
        assertTrue(source.contains("event.setCancelled(true)"));
    }
}
