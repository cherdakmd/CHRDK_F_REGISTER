package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class BowProtectionTest {
    @Test void directControlRequiresAnUncancelledPositiveDamageEvent() throws Exception {
        String source=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/BowEffectsListener.java"));
        int hit=source.indexOf("public void onArrowHit(");
        int confirmed=source.indexOf("public void onConfirmedArrowDamage(",hit);
        int death=source.indexOf("public void onEntityDeath(",confirmed);
        assertTrue(hit>=0&&confirmed>hit&&death>confirmed);
        String collision=source.substring(hit,confirmed);
        assertFalse(collision.contains("setFreezeTicks"));assertFalse(collision.contains("setGlowing("));
        assertTrue(collision.contains("EventPriority.MONITOR, ignoreCancelled = true"));
        String damage=source.substring(confirmed,death);
        assertTrue(damage.contains("event.getFinalDamage() <= 0"));
        assertTrue(damage.contains("instanceof Arrow arrow"));
        assertTrue(damage.contains("PotionEffectType.GLOWING"));
        assertTrue(damage.contains("getPotionEffect(PotionEffectType.GLOWING) == null"));
        assertFalse(damage.contains("victim.setGlowing("));
    }
}
