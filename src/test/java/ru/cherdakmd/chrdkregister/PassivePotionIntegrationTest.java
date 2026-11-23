package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Проводка общего владельца: одни и те же правила используются всеми аддонами. */
class PassivePotionIntegrationTest {
    private String source(String path) throws Exception { return Files.readString(Path.of(path)); }

    @Test
    void allContinuousPlayerSourcesPublishFramesInsteadOfIndependentPotionWrites() throws Exception {
        String enchants = source("enchants/src/main/java/ru/cherdakmd/chrdkenchants/AuraService.java");
        assertTrue(enchants.contains("PassivePotions.replace(plugin, player, passive, 20)"));
        assertFalse(enchants.contains("player.addPotionEffect("));
        assertTrue(enchants.contains("GLOWING, 120")); // скан каждые 4×20=80 тиков
        assertTrue(enchants.contains("feet.getType() != Material.LAVA && support.getType() != Material.LAVA"));
        assertTrue(enchants.contains("passive.add(new PotionEffect(PotionEffectType.FIRE_RESISTANCE"));
        assertFalse(enchants.contains("lavaCrust"));
        String artifacts = source("artifacts/src/main/java/ru/cherdakmd/chrdkartifacts/PassiveEffectsService.java");
        assertTrue(artifacts.contains("!type.isInstant()) continuous.add"));
        assertTrue(artifacts.contains("PassivePotions.replace(plugin, player, continuous"));
        assertTrue(artifacts.contains("type == null || !type.isInstant()"));
        String traits = source("artifacts/src/main/java/ru/cherdakmd/chrdkartifacts/ArtifactTraitService.java");
        assertTrue(traits.contains("PassivePotions.replace(plugin, \"traits\", player, effects"));
        assertFalse(traits.contains("player.addPotionEffect("));
    }

    @Test
    void coreStartsStopsAndSkipsPotionDurationMultiplierForOwnedPassives() throws Exception {
        String core = source("src/main/java/ru/cherdakmd/chrdkregister/RegisterPlugin.java");
        assertTrue(core.contains("PassivePotions.start(this)"));
        assertTrue(core.contains("PassivePotions.stop()"));
        String service = source("src/main/java/ru/cherdakmd/chrdkregister/api/PassivePotions.java");
        assertTrue(service.contains("ignoreCancelled = true"));
        assertTrue(service.contains("PassivePotionPolicy.releasePlan("));
        assertTrue(service.contains("effect.getHiddenPotionEffect()"));
        assertTrue(service.contains("duration = Math.max(duration, current.getDuration())"));
        assertTrue(service.contains("release(key, player)"));
        assertTrue(service.contains("Cause.EXPIRATION"));
        assertTrue(service.contains("renewalDuration(current.getDuration()"));
    }
}
