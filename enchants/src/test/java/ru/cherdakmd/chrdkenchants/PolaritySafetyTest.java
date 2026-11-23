package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PolaritySafetyTest {
    @Test void plunderFailsClosedForPlayersForeignAndMalformedDrops() {
        assertTrue(PlunderPolicy.eligible(false, false, 1, 64));
        assertTrue(PlunderPolicy.eligible(false, false, 64, 64));
        assertFalse(PlunderPolicy.eligible(true, false, 1, 64));
        assertFalse(PlunderPolicy.eligible(false, true, 1, 64));
        assertFalse(PlunderPolicy.eligible(false, false, 0, 64));
        assertFalse(PlunderPolicy.eligible(false, false, 65, 64));
        assertFalse(PlunderPolicy.eligible(false, false, 1, 0));
    }
    @Test void productionGuardsMobRewardsAndTakesLightfootFromBoots() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/PolarityEffectsListener.java"));
        int kill = source.indexOf("public void onKill(");
        String handler = source.substring(kill, source.indexOf("/* ------------------------------ утилиты", kill));
        assertTrue(handler.contains("!(event.getEntity() instanceof Player)"));
        assertTrue(handler.contains("MineTreasureLootPolicy.isForeignDrop(drop)"));
        assertTrue(handler.contains("PlunderPolicy.eligible("));
        assertTrue(handler.contains("plugin.config().isEnabled(\"lightfoot\")"));
        assertTrue(handler.contains("killer.getInventory().getBoots()"));
    }
    @Test void burningExtensionIsNotExecutedBeforeProtection() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/PolarityEffectsListener.java"));
        int incoming=source.indexOf("public void onIncoming(");
        int confirmed=source.indexOf("public void onConfirmedFireDamage(");
        int predicate=source.indexOf("private static boolean isFireDamage(");
        assertFalse(source.substring(incoming,confirmed).contains("setFireTicks("));
        assertTrue(source.substring(incoming,confirmed).contains("EventPriority.MONITOR, ignoreCancelled = true"));
        assertTrue(source.substring(confirmed,predicate).contains("DamageEvidence.positive(event.getFinalDamage())"));
        assertTrue(source.substring(confirmed,predicate).contains("setFireTicks("));
    }
}
