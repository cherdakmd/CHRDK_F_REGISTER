package ru.cherdakmd.chrdkregister;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BankChargeGuardTest {
    @Test void markerSurvivesNormalInventoryChangesButOldOrMissingPlayerdataMarkerFailsClosed() {
        var first=UUID.randomUUID(); var second=UUID.randomUUID();
        assertTrue(BankChargeGuard.matches(Map.of(),Map.of()));
        assertTrue(BankChargeGuard.matches(Map.of("lottery",first),Map.of("lottery",first.toString(),"unrelated","anything")));
        assertFalse(BankChargeGuard.matches(Map.of("lottery",first),Map.of()));
        assertFalse(BankChargeGuard.matches(Map.of("lottery",first),Map.of("lottery",second.toString())));
        assertFalse(BankChargeGuard.matches(Map.of("lottery",first,"common-goals",second),Map.of("lottery",first.toString())));
        assertFalse(BankChargeGuard.matches(null,Map.of()));
    }
    @Test void rootGuardRunsAfterAddonAuthRecoveryAndRemainsRegisteredWithoutAddons() throws Exception {
        String guard=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/BankChargeGuard.java"));
        assertTrue(guard.contains("EventPriority.MONITOR")); assertTrue(guard.contains("getPersistentDataContainer()")); assertTrue(guard.contains("p.kick(reason)"));
        assertFalse(guard.contains("getStorageContents")); assertFalse(guard.contains("setStorageContents")); assertFalse(guard.contains("saveData"));
        String root=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/RegisterPlugin.java"));
        assertTrue(root.contains("registerEvents(chargeGuard, this)")); assertTrue(root.contains("chargeGuard::checkOnline"));
    }
}
