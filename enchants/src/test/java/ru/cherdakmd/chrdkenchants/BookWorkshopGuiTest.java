package ru.cherdakmd.chrdkenchants;

import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import static org.junit.jupiter.api.Assertions.*;

class BookWorkshopGuiTest {
    @Test void onlyOrdinaryCursorOperationsAreAllowed() {
        var allowed=EnumSet.of(InventoryAction.PICKUP_ALL,InventoryAction.PICKUP_HALF,InventoryAction.PICKUP_ONE,
                InventoryAction.PICKUP_SOME,InventoryAction.PLACE_ALL,InventoryAction.PLACE_ONE,
                InventoryAction.PLACE_SOME,InventoryAction.SWAP_WITH_CURSOR,InventoryAction.NOTHING);
        for(var action:InventoryAction.values()) assertEquals(allowed.contains(action),BookWorkshopGui.safeAction(action),action.name());
    }
    @Test void inputSlotsAreReturnedAndCommitChecksSnapshots() throws Exception {
        var gui=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/BookWorkshopGui.java"));
        var workshop=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/EnchantWorkshop.java"));
        assertTrue(gui.contains("h.inv.setItem(slot,null)"));
        assertTrue(gui.contains("p.getInventory().addItem(item)"));
        assertTrue(gui.contains("InventoryDragEvent"));
        assertTrue(gui.contains("Arrays.equals(h.storage,p.getInventory().getStorageContents())"));
        assertTrue(gui.contains("plugin.workshop().applyGui("));
        assertTrue(workshop.contains("Built built = build(action, args, before, off, slot)"));
        assertTrue(workshop.contains("if (!Set.of(\"cleanse\", \"combine\", \"split\", \"splitvanilla\").contains(action) || !permitted(player, action))"));
    }
}
