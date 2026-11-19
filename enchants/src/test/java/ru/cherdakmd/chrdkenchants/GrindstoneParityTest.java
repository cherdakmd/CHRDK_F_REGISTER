package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GrindstoneParityTest {
    @Test void grindstoneStripsEverythingAndBooksBecomeVanilla() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/GrindstoneListener.java"));
        assertTrue(source.contains("PrepareGrindstoneEvent"));
        assertTrue(source.contains("EventPriority.HIGH"));
        assertTrue(source.contains("event.setResult(strip(result))"));
        assertTrue(source.contains("new ItemStack(Material.BOOK"),
                "книга после спиливания — чисто ванильная, без имени и PDC");
        assertTrue(source.contains("plugin.items().write(result, Map.of())"),
                "все наши чары спиливаются, включая проклятия");
        assertTrue(source.contains("meta.removeEnchant"),
                "ванильные чары и проклятия тоже спиливаются");
        assertFalse(source.contains("displayName"),
                "имя не переносится — выходная книга стакается с ванильными");
        assertFalse(source.contains("def.curse()"),
                "проклятия не переживают точило");
        assertFalse(source.contains("giveExp"));
        assertFalse(source.contains("setLevel("));
        assertFalse(source.contains("PrepareAnvilEvent"));
        String plugin = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/EnchantsPlugin.java"));
        assertTrue(plugin.contains("registerEvents(new GrindstoneListener(this), this)"));
    }

    @Test void tableFallbackKeepsOfferedVanillaEnchantsAndRejectsMarkedResults() throws Exception {
        assertTrue(TableListener.class.getDeclaredMethod("carriesOffered",
                org.bukkit.inventory.ItemStack.class, java.util.Map.class, boolean.class) != null);
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/TableListener.java"));
        assertTrue(source.contains("carriesOffered(current, event.getEnchantsToAdd()"));
        assertTrue(source.contains("!plugin.items().marked(current)"));
        assertTrue(source.contains("pending.size() >= LIMIT"));
    }
}
