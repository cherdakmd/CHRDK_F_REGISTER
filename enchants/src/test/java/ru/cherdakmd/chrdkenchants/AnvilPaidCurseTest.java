package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AnvilPaidCurseTest {
    @Test void notifiesOnlyNewOrStrongerKnownCurse() {
        assertTrue(AnvilApplyListener.addsCurse(Map.of(),Map.of("dull_blade",1)));
        assertTrue(AnvilApplyListener.addsCurse(Map.of("dull_blade",1),Map.of("dull_blade",2)));
        assertFalse(AnvilApplyListener.addsCurse(Map.of("dull_blade",1),Map.of("dull_blade",1)));
        assertFalse(AnvilApplyListener.addsCurse(Map.of("dull_blade",2),Map.of("dull_blade",1)));
        assertFalse(AnvilApplyListener.addsCurse(Map.of("dull_blade",1),Map.of()));
        assertFalse(AnvilApplyListener.addsCurse(Map.of(),Map.of("quickdraw",3)));
        assertFalse(AnvilApplyListener.addsCurse(Map.of(),Map.of("unknown:foreign",3)));
    }
    @Test void paidHookIsAnObserverAndNativeNotificationStillHonorsCancellation() throws Exception {
        var source=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/AnvilApplyListener.java"));
        var paid=source.substring(source.indexOf("public void paid("),source.indexOf("static boolean addsCurse"));
        assertTrue(paid.contains("AnvilPaidResultEvent"));assertTrue(paid.contains("anvil-curse-applied"));
        for(var forbidden:new String[]{"setItem", "giveExp", "setLevel", "setCancelled", "addItem", "applyFromBook"}) assertFalse(paid.contains(forbidden),forbidden);
        assertTrue(source.contains("!event.isCancelled() && player.isOnline()"));
        var plugin=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/EnchantsPlugin.java"));
        assertTrue(plugin.contains("getInt(null) < 41"));
    }
}
