package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DamageEvidenceTest {
    @Test void requiresPositiveFiniteDamage() {
        for (double damage : new double[] {0, -0.0, -1, Double.NaN,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
            assertFalse(DamageEvidence.positive(damage));
        assertTrue(DamageEvidence.positive(Double.MIN_VALUE));
        assertTrue(DamageEvidence.positive(1));
    }
    @Test void armorControlAndThresholdCheckPositiveDamage() throws Exception {
        String source=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/ArmorEffectsListener.java"));
        int guard=source.indexOf("if (!DamageEvidence.positive(event.getFinalDamage())) return;");
        int threshold=source.indexOf("double healthAfter");
        int monitor=source.indexOf("public void onDamaged(");
        int control=source.indexOf("attacker.setFreezeTicks",monitor);
        assertTrue(guard>=0 && threshold>guard && monitor>threshold && control>monitor);
        assertTrue(source.substring(monitor,control).contains("!DamageEvidence.positive(event.getFinalDamage())"));
        assertTrue(source.substring(threshold,monitor).contains("EventPriority.MONITOR, ignoreCancelled = true"));
    }
}
