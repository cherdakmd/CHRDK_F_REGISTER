package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ScribeGuiContractTest {
    private String source(String name)throws Exception {
        return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/"+name+".java"));
    }
    @Test void selectionAndConfirmationUseServerSideSnapshotAndWorkshop()throws Exception {
        String gui=source("ScribeGui"),workshop=source("EnchantWorkshop"),commands=source("EnchantCommandHandler");
        assertTrue(commands.contains("plugin.scribe().open(player)"));
        assertTrue(gui.contains("event.setCancelled(true)"));
        assertTrue(gui.contains("holder.book,player.getInventory().getItemInMainHand()"));
        assertTrue(gui.contains("holder.generation==plugin.sourceGeneration()"));
        assertTrue(gui.contains("player.getOpenInventory().getTopInventory().getHolder()!=holder"));
        assertTrue(gui.contains("player.closeInventory()"));
        assertTrue(gui.contains("plugin.workshop().confirmedGui(player,holder.selected.action(),holder.selected.id())"));
        assertTrue(workshop.contains("if (confirmed) return command(player, new String[]{\"confirm\", quote.code().toString()}, false)"));
        assertFalse(gui.contains("setStorageContents"));
        assertFalse(gui.contains("addItem("));
    }
    @Test void legacyAndVanillaEnchantmentsUseSeparateCheckedPaths()throws Exception {
        String gui=source("ScribeGui");
        assertTrue(gui.contains("plugin.items().validated(book)"));
        assertTrue(gui.contains("new Entry(\"split\""));
        assertTrue(gui.contains("new Entry(\"splitvanilla\""));
        assertTrue(gui.contains("entries.size()<2"));
        assertTrue(gui.contains("InventoryDragEvent"));
    }
}
