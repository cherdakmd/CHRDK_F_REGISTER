package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GrindstoneParityTest {
    @Test void onlyCursesSurviveGrindstoneLikeVanillaCurses() {
        assertTrue(GrindstoneListener.survivors(Map.of()).isEmpty());
        assertTrue(GrindstoneListener.survivors(null).isEmpty());
        assertTrue(GrindstoneListener.survivors(Map.of("careful_work", 3)).isEmpty());
        assertTrue(GrindstoneListener.survivors(Map.of("careful_work", 3, "dull_blade", 2)).equals(Map.of("dull_blade", 2)));
        assertEquals(Map.of("dull_blade", 2, "cursed_blood", 1),
                GrindstoneListener.survivors(Map.of("dull_blade", 2, "cursed_blood", 1, "careful_work", 1)));
        assertTrue(GrindstoneListener.survivors(Map.of("unknown:foreign", 3)).isEmpty());
    }

    @Test void listenerStripsOnPrepareAndNeverTouchesOtherInventories() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/GrindstoneListener.java"));
        assertTrue(source.contains("PrepareGrindstoneEvent"));
        assertTrue(source.contains("EventPriority.HIGH"));
        assertTrue(source.contains("event.setResult(result)"));
        assertTrue(source.contains("def.curse()"));
        assertTrue(source.contains("plugin.items().write(result, kept)"));
        assertTrue(source.contains("plugin.items().marked(left)"));
        assertFalse(source.contains("giveExp"));
        assertFalse(source.contains("setLevel("));
        assertFalse(source.contains("PrepareAnvilEvent"));
        String plugin = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/EnchantsPlugin.java"));
        assertTrue(plugin.contains("registerEvents(new GrindstoneListener(this), this)"));
    }

    @Test void tableFallbackKeepsOfferedVanillaEnchantsAndRejectsMarkedResults() throws Exception {
        assertTrue(TableListener.class.getDeclaredMethod("carriesOffered",
                org.bukkit.inventory.ItemStack.class, Map.class, boolean.class) != null);
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/TableListener.java"));
        assertTrue(source.contains("carriesOffered(current, event.getEnchantsToAdd()"));
        assertTrue(source.contains("!plugin.items().marked(current)"));
        assertTrue(source.contains("pending.size() >= LIMIT"));
    }
}
