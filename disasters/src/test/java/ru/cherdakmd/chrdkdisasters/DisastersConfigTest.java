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
        assertEquals(List.of(DisasterKind.DUST_STORM, DisasterKind.METEOR_SHOWER,
                        DisasterKind.BLIZZARD),
                config.enabledKinds(), "разрушительные площадные виды включены только явно");
        assertTrue(config.worldAllowed("world"));

        assertEquals(3, config.phases().warnTicks());
        assertEquals(10, config.phases().activeTicks());
        assertEquals(3, config.phases().fadeTicks());
        assertEquals(2, config.phases().tickSeconds());
        assertEquals(32, config.phases().totalSeconds());
        assertTrue(config.soundsEnabled());
        assertTrue(config.playerEffectsEnabled());
        assertEquals("BLOCK_NOTE_BLOCK_BASS", config.soundFor(DisasterKind.DUST_STORM));
        assertEquals("BLOCK_SNOW_BREAK", config.soundFor(DisasterKind.BLIZZARD));
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
        yaml.set("disasters.phases.warn-seconds", 9999);
        yaml.set("disasters.phases.active-seconds", 9999);
        yaml.set("disasters.phases.tick-seconds", -5);
        yaml.set("disasters.sounds.enabled", false);
        yaml.set("disasters.player-effects.enabled", false);
        DisastersConfig config = DisastersConfig.parse(yaml);
        assertEquals(5, config.maxLevel());
        assertEquals(48, config.radius());
        assertEquals(1, config.maxBlocks());
        assertEquals(0, config.spawnProtectionRadius());
        assertEquals(0, config.restoreAfterSeconds(), "0 — разрешённый отказ от отката");
        assertTrue(config.worldAllowed("world"));
        assertFalse(config.worldAllowed("resource"));
        assertEquals(4, config.enabledKinds().size());
        assertEquals(60, config.phases().warnSeconds());
        assertEquals(300, config.phases().activeSeconds());
        assertEquals(1, config.phases().tickSeconds());
        assertFalse(config.soundsEnabled());
        assertFalse(config.playerEffectsEnabled());
        assertEquals("NONE", config.soundFor(DisasterKind.TORNADO));
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
        assertEquals(DisasterKind.TORNADO, DisasterKind.fromId("tornado").orElseThrow());
        assertEquals(DisasterKind.FLOOD, DisasterKind.fromId("flood").orElseThrow());
        assertEquals(DisasterKind.BLIZZARD, DisasterKind.fromId("blizzard").orElseThrow());
        assertTrue(DisasterKind.fromId("supernova").isEmpty());
        assertEquals(6, DisasterKind.values().length);
        assertFalse(DisasterKind.DUST_STORM.destructive());
        assertFalse(DisasterKind.BLIZZARD.destructive());
        assertTrue(DisasterKind.METEOR_SHOWER.destructive());
        assertTrue(DisasterKind.TORNADO.destructive());
        assertTrue(DisasterKind.FLOOD.destructive());
    }

    @Test
    void customSoundAndNewKindsAreReadFromOwnerFile() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("disasters.sounds.tornado", "block_glass_break");
        yaml.set("disasters.kinds.tornado", true);
        yaml.set("disasters.kinds.flood", true);
        DisastersConfig config = DisastersConfig.parse(yaml);
        assertEquals("BLOCK_GLASS_BREAK", config.soundFor(DisasterKind.TORNADO));
        assertTrue(config.enabledKinds().contains(DisasterKind.TORNADO));
        assertTrue(config.enabledKinds().contains(DisasterKind.FLOOD));
        assertEquals(5, config.enabledKinds().size());
    }
}
