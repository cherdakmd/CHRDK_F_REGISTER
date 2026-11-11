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
        assertEquals(8, config.maxConcurrent());
        assertFalse(config.cataclysm().enabled());
        assertTrue(config.cataclysm().titles());
        assertTrue(config.cataclysm().damage());
        assertTrue(config.cataclysm().knockback());
        assertEquals(4, config.cataclysm().maxFireSeconds());
        assertEquals(24, config.cataclysm().maxAffectedPlayers());
        assertEquals(6.0, config.cataclysm().maxDamagePerPulse());
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

        assertTrue(config.rewards().enabled());
        assertEquals(5, config.rewards().base());
        assertEquals(2, config.rewards().perLevel());
        assertEquals(120, config.rewards().dailyCap());
        assertEquals(1.5, config.rewards().bonusCap());
        assertEquals(100, config.progression().baseXp());
        assertEquals(30, config.progression().xpPerSurvive());
        assertEquals(10, config.progression().maxLevel());
        assertTrue(config.integrations().achievements());
        assertTrue(config.integrations().skills());
        assertEquals("defense", config.integrations().skillId());
        assertEquals(2, config.integrations().units());
        assertEquals(20, config.shop().diamondsPrice());
        assertEquals(16, config.shop().diamondsAmount());
        assertEquals(12, config.shop().suppliesPrice());
        assertEquals(30, config.shop().wardPrice());
        assertEquals(30, config.shop().wardMinutes());
    }

    @Test
    void numericSettingsAreClampedAndWorldWhitelistWorks() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("disasters.enabled", true);
        yaml.set("disasters.max-level", 99);
        yaml.set("disasters.radius", 9999);
        yaml.set("disasters.max-blocks", -3);
        yaml.set("disasters.max-concurrent", 9999);
        yaml.set("disasters.cataclysm.max-fire-seconds", 9999);
        yaml.set("disasters.cataclysm.max-affected-players", -10);
        yaml.set("disasters.cataclysm.max-damage-per-pulse", 9999);
        yaml.set("disasters.spawn-protection-radius", -10);
        yaml.set("disasters.restore-after-seconds", -1);
        yaml.set("disasters.worlds", List.of("world", "world_nether"));
        yaml.set("disasters.kinds.earthquake", true);
        yaml.set("disasters.phases.warn-seconds", 9999);
        yaml.set("disasters.phases.active-seconds", 9999);
        yaml.set("disasters.phases.tick-seconds", -5);
        yaml.set("disasters.sounds.enabled", false);
        yaml.set("disasters.player-effects.enabled", false);
        yaml.set("disasters.rewards.daily-cap", -5);
        yaml.set("disasters.rewards.bonus-cap", 99.0);
        yaml.set("disasters.progression.max-level", 999);
        yaml.set("disasters.shop.diamonds.price", 999_999);
        yaml.set("disasters.integrations.skill-id", "");
        DisastersConfig config = DisastersConfig.parse(yaml);
        assertEquals(5, config.maxLevel());
        assertEquals(48, config.radius());
        assertEquals(1, config.maxBlocks());
        assertEquals(64, config.maxConcurrent());
        assertEquals(30, config.cataclysm().maxFireSeconds());
        assertEquals(1, config.cataclysm().maxAffectedPlayers());
        assertEquals(20.0, config.cataclysm().maxDamagePerPulse());
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
        assertEquals(0, config.rewards().dailyCap());
        assertEquals(3.0, config.rewards().bonusCap());
        assertEquals(50, config.progression().maxLevel());
        assertEquals(10_000, config.shop().diamondsPrice());
        assertEquals("defense", config.integrations().skillId());
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
