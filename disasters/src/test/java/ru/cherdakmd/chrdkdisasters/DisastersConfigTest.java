package ru.cherdakmd.chrdkdisasters;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DisastersConfigTest {

    @Test
    void stockConfigIsDisabledAndSafe() {
        YamlConfiguration stock = YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/config.yml").toFile());
        DisastersConfig config = DisastersConfig.parse(stock);
        assertFalse(config.enabled(), "по умолчанию модуль выключен");
        assertEquals("ru", config.locale());
        assertEquals(1800, config.minSeconds());
        assertEquals(5400, config.maxSeconds());
        assertEquals(600, config.announceCooldownSeconds());
        assertEquals(600, config.restoreAfterSeconds());
        assertEquals(12, config.radius());
        assertEquals(256, config.maxBlocks());
        assertTrue(config.respectClaims());
        assertTrue(config.worlds().isEmpty());
        assertEquals(List.of(DisasterKind.DUST_STORM, DisasterKind.METEOR_SHOWER),
                config.enabledKinds(), "землетрясение включено только явно");
        assertTrue(config.worldAllowed("world"));
    }

    @Test
    void numericSettingsAreClampedAndWorldWhitelistWorks() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("disasters.enabled", true);
        yaml.set("disasters.max-level", 99);
        yaml.set("disasters.radius", 9999);
        yaml.set("disasters.max-blocks", -3);
        yaml.set("disasters.spawn-protection-radius", -10);
        yaml.set("disasters.restore-after-seconds", -1);
        yaml.set("disasters.worlds", List.of("world", "world_nether"));
        yaml.set("disasters.kinds.earthquake", true);
        DisastersConfig config = DisastersConfig.parse(yaml);
        assertEquals(5, config.maxLevel());
        assertEquals(48, config.radius());
        assertEquals(1, config.maxBlocks());
        assertEquals(0, config.spawnProtectionRadius());
        assertEquals(0, config.restoreAfterSeconds(), "0 — разрешённый отказ от отката");
        assertTrue(config.worldAllowed("world"));
        assertFalse(config.worldAllowed("resource"));
        assertEquals(3, config.enabledKinds().size());
    }

    @Test
    void maxIsNeverBelowMin() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("disasters.min-seconds", 3600);
        yaml.set("disasters.max-seconds", 60);
        DisastersConfig config = DisastersConfig.parse(yaml);
        assertEquals(3600, config.minSeconds());
        assertEquals(3600, config.maxSeconds());
        assertEquals(3_600_000L, config.minMillis());
    }

    @Test
    void kindIdsRoundTrip() {
        assertTrue(DisasterKind.fromId("DUST-STORM").isPresent());
        assertEquals(DisasterKind.EARTHQUAKE, DisasterKind.fromId("earthquake").orElseThrow());
        assertTrue(DisasterKind.fromId("tornado").isEmpty());
        assertFalse(DisasterKind.DUST_STORM.destructive());
        assertTrue(DisasterKind.METEOR_SHOWER.destructive());
    }
}
