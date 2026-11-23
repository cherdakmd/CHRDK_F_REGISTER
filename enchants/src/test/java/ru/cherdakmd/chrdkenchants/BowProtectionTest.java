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
        assertTrue(damage.contains("!DamageEvidence.positive(event.getFinalDamage())"));
        assertTrue(damage.contains("instanceof Arrow arrow"));
        assertTrue(damage.contains("PotionEffectType.GLOWING"));
        assertTrue(damage.contains("getPotionEffect(PotionEffectType.GLOWING) == null"));
        assertFalse(damage.contains("victim.setGlowing("));
    }
    @Test void areaImpactWaitsForMatchingCollisionAndPositiveDamage() throws Exception {
        String source=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/BowEffectsListener.java"));
        int hit=source.indexOf("public void onArrowHit(");int apply=source.indexOf("private void applyAreaImpact(",hit);
        String capture=source.substring(hit,apply);
        assertTrue(capture.contains("impacts.collision(key,target!=null"));
        assertTrue(capture.contains("!event.isCancelled()"));
        assertTrue(capture.contains("generation==plugin.sourceGeneration()"));
        assertTrue(capture.contains("shooter.isOnline()"));
        assertFalse(capture.contains("createExplosion("));assertFalse(capture.contains(".damage("));
        int damage=source.indexOf("public void onConfirmedArrowDamage(",apply);
        assertTrue(source.substring(damage).contains("impacts.damage(key,()->!event.isCancelled() && DamageEvidence.positive(event.getFinalDamage()))"));
        assertTrue(source.contains("impacts.clear()"));
    }
}
