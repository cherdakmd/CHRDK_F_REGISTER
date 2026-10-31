package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Проводка общего владельца: одни и те же правила используются всеми тремя аддонами. */
class PassivePotionIntegrationTest {
    private String source(String path) throws Exception { return Files.readString(Path.of(path)); }

    @Test
    void allContinuousPlayerSourcesPublishFramesInsteadOfIndependentPotionWrites() throws Exception {
        String forge = source("tier-forge/src/main/java/ru/cherdakmd/chrdktierforge/ForgeUpgradeListener.java");
        String pulse = forge.substring(forge.indexOf("public void pulse()"), forge.indexOf("/** Стоит ли игрок"));
        assertTrue(pulse.contains("PassivePotions.replace(plugin, player, passive, 40)"));
        assertFalse(pulse.contains("addPotionEffect("));
        assertFalse(pulse.contains("applyFresh("));
        String enchants = source("enchants/src/main/java/ru/cherdakmd/chrdkenchants/AuraService.java");
        assertTrue(enchants.contains("PassivePotions.replace(plugin, player, passive, 20)"));
        assertFalse(enchants.contains("player.addPotionEffect("));
        assertTrue(enchants.contains("GLOWING, 120")); // скан каждые 4×20=80 тиков
        assertTrue(enchants.contains("lavaCrust.containsKey(support)"));
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
        String forge = source("tier-forge/src/main/java/ru/cherdakmd/chrdktierforge/ForgeUpgradeListener.java");
        assertTrue(forge.contains("PassivePotions.isApplying(player)"));
        String service = source("src/main/java/ru/cherdakmd/chrdkregister/api/PassivePotions.java");
        assertTrue(service.contains("ignoreCancelled = true"));
        assertTrue(service.contains("event.setOverride(true)"));
        assertTrue(service.contains("release(key, player)"));
        assertTrue(service.contains("Cause.EXPIRATION"));
        assertTrue(service.contains("renewalDuration(current.getDuration()"));
    }
}
