package ru.cherdakmd.chrdktreasures;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreasureConfigTest {

    private static final Logger LOGGER = Logger.getLogger("TreasureConfigTest");

    private static YamlConfiguration shipped() {
        try (var stream = TreasureConfigTest.class.getClassLoader()
                .getResourceAsStream("config.yml")) {
            assertNotNull(stream, "config.yml не найден в ресурсах");
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (Exception exception) {
            throw new AssertionError("не удалось прочитать config.yml", exception);
        }
    }

    @Test
    void emptyConfigFallsBackToDefaults() {
        TreasuresConfig config = TreasuresConfig.parse(new YamlConfiguration(), LOGGER);
        assertTrue(config.enabled());
        assertEquals(60, config.auraRefreshTicks());
        assertEquals(10, config.topSize());
        assertEquals(1.0, config.chance(TreasureTier.COMMON), 1e-9);
        assertEquals(0.45, config.itemChance(TreasureTier.COMMON), 1e-9);
        assertEquals(TreasuresConfig.defaultBlocks().size(), config.blocks().size());
        assertEquals(3_000L, config.cooldownMillis());
        assertEquals(4, config.milestones().size());
        assertEquals(8, config.milestones().get(10).diamonds());
        assertEquals(96, config.milestones().get(TreasureCatalog.EXPECTED_ITEMS).diamonds());
        assertTrue(config.broadcastLegendary());
        assertTrue(config.broadcastMythical());
    }

    @Test
    void shippedConfigParsesToDocumentedValues() {
        TreasuresConfig config = TreasuresConfig.parse(shipped(), LOGGER);
        assertEquals(1.0, config.chance(TreasureTier.COMMON), 1e-9);
        assertEquals(0.220, config.chance(TreasureTier.RARE), 1e-9);
        assertEquals(0.0008, config.chance(TreasureTier.MYTHICAL), 1e-9);
        assertEquals(1.0, config.itemChance(TreasureTier.MYTHICAL), 1e-9);
        assertEquals(35, config.blocks().size(), "в config.yml должно быть 35 блоков");
        assertEquals(2.0, config.blockMultiplier("diamond_ore"), 1e-9);
        assertEquals(4.0, config.blockMultiplier("ANCIENT_DEBRIS"), 1e-9);
        assertEquals(2.0, config.blockMultiplier("nether_quartz_ore"), 1e-9);
        assertEquals(0.0, config.blockMultiplier("DIRT"), 1e-9);
        assertEquals(1.02, TreasureRates.depthBonus(-10,
                config.depthPerBlock(), config.depthMaxBonus()), 1e-9);
        assertEquals(4, config.milestones().size());
    }

    @Test
    void valuesAreClampedToSaneRange() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("general.aura-refresh-ticks", 1);
        cfg.set("general.top-size", 500);
        cfg.set("chances.common.chance-percent", 500);
        cfg.set("chances.rare.chance-percent", -5);
        cfg.set("chances.rare.item-chance", 5);
        cfg.set("limits.placed-block-seconds", 0);
        cfg.set("limits.placed-block-max", 1);
        cfg.set("limits.min-seconds-between-treasures", 99_999);
        cfg.set("depth.per-block-bonus", 1.0);
        cfg.set("depth.max-bonus", 100.0);
        TreasuresConfig config = TreasuresConfig.parse(cfg, LOGGER);
        assertEquals(20, config.auraRefreshTicks());
        assertEquals(50, config.topSize());
        assertEquals(100.0, config.chance(TreasureTier.COMMON), 1e-9);
        assertEquals(0.0, config.chance(TreasureTier.RARE), 1e-9);
        assertEquals(1.0, config.itemChance(TreasureTier.RARE), 1e-9);
        assertEquals(5L, config.placedBlockTtlSeconds());
        assertEquals(64, config.placedBlockMaxEntries());
        assertEquals(3_600_000L, config.cooldownMillis());
        assertEquals(0.1, config.depthPerBlock(), 1e-9);
        assertEquals(10.0, config.depthMaxBonus(), 1e-9);
    }

    @Test
    void blocksSectionReplacesDefaults() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("blocks.DIAMOND_ORE", 3.0);
        cfg.set("blocks.STONE", 0.0);
        TreasuresConfig config = TreasuresConfig.parse(cfg, LOGGER);
        assertEquals(1, config.blocks().size());
        assertEquals(3.0, config.blockMultiplier("diamond_ore"), 1e-9);
        assertFalse(config.mineable("STONE"));
        assertFalse(config.mineable("DIRT"));
        assertTrue(config.mineable("DIAMOND_ORE"));
        assertFalse(config.mineable(null));
    }

    @Test
    void milestonesIgnoreBadKeysAndFallBackWhenEmpty() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("milestones.one.diamonds", 4);
        cfg.set("milestones.500.diamonds", 4);
        cfg.set("milestones.10.diamonds", -3);
        TreasuresConfig config = TreasuresConfig.parse(cfg, LOGGER);
        assertEquals(1, config.milestones().size());
        assertEquals(0, config.milestones().get(10).diamonds());

        YamlConfiguration onlyBad = new YamlConfiguration();
        onlyBad.set("milestones.nope.diamonds", 1);
        TreasuresConfig fallback = TreasuresConfig.parse(onlyBad, LOGGER);
        assertEquals(TreasuresConfig.defaultMilestones(), fallback.milestones());
    }

    @Test
    void defaultsAreImmutableAndPositive() {
        Map<String, Double> blocks = TreasuresConfig.defaultBlocks();
        assertTrue(blocks.containsKey("ANCIENT_DEBRIS"));
        for (Map.Entry<String, Double> entry : blocks.entrySet()) {
            assertTrue(entry.getValue() > 0.0, "нулевой множитель у " + entry.getKey());
        }
        try {
            blocks.put("DIRT", 1.0);
            throw new AssertionError("набор блоков должен быть неизменяемым");
        } catch (UnsupportedOperationException expected) {
            // так и должно быть
        }
    }
}
