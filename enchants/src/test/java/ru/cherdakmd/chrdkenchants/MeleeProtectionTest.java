package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MeleeProtectionTest {
    @Test
    void secondaryEffectsRunAfterNormalProtectionPriorities() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/MeleeEffectsListener.java"));
        int monitor = source.indexOf("@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)");
        int effects = source.indexOf("private void applyHitEffects");
        assertTrue(monitor > source.indexOf("private void applyDamageModifiers"));
        assertTrue(effects > monitor);
        assertTrue(source.substring(monitor, effects).contains("!DamageEvidence.positive(event.getFinalDamage())"));
        assertTrue(source.substring(monitor, effects).contains("processing.contains(attacker.getUniqueId())"));
        assertFalse(source.substring(source.indexOf("private void applyDamageModifiers"), monitor).contains("victim.damage("));
    }
}
