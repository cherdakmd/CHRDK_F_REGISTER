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
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals(2.0, config.chance(TreasureTier.COMMON), 1e-9);
        assertEquals(0.45, config.itemChance(TreasureTier.COMMON), 1e-9);
        assertEquals(TreasuresConfig.defaultBlocks().size(), config.blocks().size());
        assertEquals(TreasuresConfig.defaultBlocks().size(), config.blockCount());
        assertEquals(3_000L, config.cooldownMillis());
        assertEquals(6, config.milestones().size());
        assertEquals(8, config.milestones().get(20).diamonds());
        assertEquals(96, config.milestones().get(TreasureCatalog.EXPECTED_ITEMS).diamonds());
        assertEquals(192, config.milestones().get(TreasureRegistry.size()).diamonds());
        assertTrue(config.broadcastLegendary());
        assertTrue(config.broadcastMythical());
    }

    @Test
    void shippedConfigParsesToDocumentedValues() {
        TreasuresConfig config = TreasuresConfig.parse(shipped(), LOGGER);
        assertEquals(2.0, config.chance(TreasureTier.COMMON), 1e-9);
        assertEquals(0.440, config.chance(TreasureTier.RARE), 1e-9);
        assertEquals(0.0016, config.chance(TreasureTier.MYTHICAL), 1e-9);
        assertEquals(1.0, config.itemChance(TreasureTier.MYTHICAL), 1e-9);
        assertEquals(35, config.blockCount(), "в config.yml должно быть 35 блоков");
        assertEquals(0, config.blocks().size(), "в новом формате все блоки — таблицы");
        Map<TreasureTier, Double> diamond = config.blockChances("diamond_ore");
        assertNotNull(diamond, "у алмазной руды должна быть таблица шансов");
        assertEquals(4.0, diamond.get(TreasureTier.COMMON), 1e-9);
        assertEquals(1.0, diamond.get(TreasureTier.RARE), 1e-9);
        assertEquals(0.128, diamond.get(TreasureTier.EPIC), 1e-9);
        assertEquals(0.064, diamond.get(TreasureTier.LEGENDARY), 1e-9);
        assertEquals(0.016, diamond.get(TreasureTier.MYTHICAL), 1e-9);
        assertEquals(3.2, config.blockChances("DEEPSLATE").get(TreasureTier.COMMON), 1e-9);
        assertEquals(0.4, config.blockChances("DIORITE").get(TreasureTier.RARE), 1e-9);
        assertNull(config.blockChances("DIRT"));
        assertEquals(0.0, config.blockMultiplier("DIRT"), 1e-9);
        assertTrue(config.mineable("diamond_ore"));
        assertFalse(config.mineable("DIRT"));
        assertEquals(1.02, TreasureRates.depthBonus(-10,
                config.depthPerBlock(), config.depthMaxBonus()), 1e-9);
        assertEquals(6, config.milestones().size());
        // Вехи в поставляемом config.yml должны совпадать с умолчаниями кода:
        // иначе новая установка получает алмазы за половину коллекции.
        assertEquals(TreasuresConfig.defaultMilestones(), config.milestones(),
                "вехи коллекции в config.yml разошлись с умолчаниями кода");
        assertEquals(0.35, config.arsenalShare(), 1e-9);
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
    void abilitiesSetsAndCursesAreOnByDefault() {
        TreasuresConfig defaults = TreasuresConfig.parse(new YamlConfiguration(), LOGGER);
        assertTrue(defaults.abilitiesEnabled());
        assertTrue(defaults.cursesEnabled());
        assertTrue(defaults.setsEnabled());

        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("abilities.enabled", false);
        cfg.set("curses.enabled", false);
        cfg.set("sets.enabled", false);
        TreasuresConfig off = TreasuresConfig.parse(cfg, LOGGER);
        assertFalse(off.abilitiesEnabled());
        assertFalse(off.cursesEnabled());
        assertFalse(off.setsEnabled());
    }

    @Test
    void shippedConfigKeepsNewSystemsOn() {
        TreasuresConfig config = TreasuresConfig.parse(shipped(), LOGGER);
        assertTrue(config.abilitiesEnabled(), "в config.yml способности выключены");
        assertTrue(config.cursesEnabled(), "в config.yml проклятия выключены");
        assertTrue(config.setsEnabled(), "в config.yml сеты выключены");
    }

    @Test
    void blocksSectionReplacesDefaults() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("blocks.DIAMOND_ORE", 3.0);
        cfg.set("blocks.STONE", 0.0);
        TreasuresConfig config = TreasuresConfig.parse(cfg, LOGGER);
        assertEquals(1, config.blocks().size());
        assertEquals(1, config.blockCount());
        assertEquals(3.0, config.blockMultiplier("diamond_ore"), 1e-9);
        assertFalse(config.mineable("STONE"));
        assertFalse(config.mineable("DIRT"));
        assertTrue(config.mineable("DIAMOND_ORE"));
        assertFalse(config.mineable(null));
    }

    @Test
    void blockTableFormatMixesWithMultipliers() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("blocks.GOLD_ORE.common", 1.4);
        cfg.set("blocks.GOLD_ORE.rare", 0.35);
        cfg.set("blocks.GOLD_ORE.epic", 0.0448);
        cfg.set("blocks.GOLD_ORE.legendary", 0.0224);
        cfg.set("blocks.GOLD_ORE.mythical", 0.0056);
        cfg.set("blocks.GRANITE", 1.5);
        TreasuresConfig config = TreasuresConfig.parse(cfg, LOGGER);
        assertEquals(2, config.blockCount());
        assertEquals(1, config.blocks().size());
        Map<TreasureTier, Double> gold = config.blockChances("gold_ore");
        assertNotNull(gold);
        assertEquals(5, gold.size());
        assertEquals(1.4, gold.get(TreasureTier.COMMON), 1e-9);
        assertEquals(0.0056, gold.get(TreasureTier.MYTHICAL), 1e-9);
        assertEquals(1.5, config.blockMultiplier("granite"), 1e-9);
        assertEquals(0.0, config.blockMultiplier("gold_ore"), 1e-9);
        assertTrue(config.mineable("GOLD_ORE"));
        assertTrue(config.mineable("GRANITE"));
        assertFalse(config.mineable("DIRT"));
        assertNull(config.blockChances("GRANITE"));
    }

    @Test
    void emptyBlockTableIsIgnoredAndCannotBreakMining() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("blocks.STONE.common", 0.0);
        cfg.set("blocks.STONE.rare", 0.0);
        TreasuresConfig config = TreasuresConfig.parse(cfg, LOGGER);
        assertEquals(TreasuresConfig.defaultBlocks().size(), config.blockCount(),
                "пустая таблица не должна отключать весь список блоков");
        assertNull(config.blockChances("STONE"));
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
    void overridesReplaceChancesOnTopOfConfig() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("chances.legendary.chance-percent", 0.008);
        TreasuresConfig config = TreasuresConfig.parse(cfg,
                Map.of(TreasureTier.LEGENDARY, 0.5, TreasureTier.RARE, 150.0), LOGGER);
        assertEquals(0.5, config.chance(TreasureTier.LEGENDARY), 1e-9);
        assertEquals(100.0, config.chance(TreasureTier.RARE), 1e-9);
        assertEquals(2.0, config.chance(TreasureTier.COMMON), 1e-9);
        assertEquals(0.008, TreasuresConfig.parse(cfg, LOGGER)
                .chance(TreasureTier.LEGENDARY), 1e-9);
    }

    @Test
    void streakRankAndEffectDefaultsAreDocumented() {
        TreasuresConfig config = TreasuresConfig.parse(new YamlConfiguration(), LOGGER);
        assertEquals(600, config.streakWindowSeconds());
        assertEquals(600_000L, config.streakWindowMillis());
        assertEquals(5, config.streakBonusEvery());
        assertEquals(4, config.rankReward(TreasureRank.SEEKER));
        assertEquals(80, config.rankReward(TreasureRank.LEGEND));
        assertEquals(0, config.rankReward(TreasureRank.NOVICE));
        assertTrue(config.effects().titles());
        assertTrue(config.effects().actionbar());
        assertTrue(config.effects().fireworks());
        assertEquals(TreasureTier.EPIC, config.effects().titleMinTier());
        assertEquals(TreasureTier.LEGENDARY, config.effects().fireworkMinTier());
        assertTrue(config.broadcastLegend());
    }

    @Test
    void lootDetailsAreOnByDefaultAndClamped() {
        TreasuresConfig defaults = TreasuresConfig.parse(new YamlConfiguration(), LOGGER);
        assertTrue(defaults.showLoot(), "состав клада показывается по умолчанию");
        assertEquals(TreasureLootText.DEFAULT_LIMIT, defaults.lootDetailsMax());
        assertTrue(defaults.logFinds());

        TreasuresConfig shipped = TreasuresConfig.parse(shipped(), LOGGER);
        assertTrue(shipped.showLoot());
        assertEquals(TreasureLootText.DEFAULT_LIMIT, shipped.lootDetailsMax());
        assertTrue(shipped.logFinds());

        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("feedback.show-loot", false);
        cfg.set("feedback.loot-details-max", 500);
        cfg.set("feedback.log-finds", false);
        TreasuresConfig clamped = TreasuresConfig.parse(cfg, LOGGER);
        assertFalse(clamped.showLoot());
        assertEquals(TreasureLootText.MAX_LIMIT, clamped.lootDetailsMax(),
                "длиннее строки не показываем");
        assertFalse(clamped.logFinds());

        cfg.set("feedback.loot-details-max", -3);
        assertEquals(1, TreasuresConfig.parse(cfg, LOGGER).lootDetailsMax(),
                "хотя бы один предмет показывается всегда");
    }

    @Test
    void shippedConfigCarriesNewSections() {
        TreasuresConfig config = TreasuresConfig.parse(shipped(), LOGGER);
        assertEquals(600, config.streakWindowSeconds());
        assertEquals(5, config.streakBonusEvery());
        assertEquals(4, config.rankReward(TreasureRank.SEEKER));
        assertEquals(80, config.rankReward(TreasureRank.LEGEND));
        assertTrue(config.effects().fireworks());
        assertTrue(config.broadcastLegend());
    }

    @Test
    void newValuesAreClampedAndBadTiersFallBack() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("streak.window-seconds", 1);
        cfg.set("streak.bonus-every", 500);
        cfg.set("ranks.legend.diamonds", 5000);
        cfg.set("effects.title-min-tier", "нет-такой");
        cfg.set("effects.firework-min-tier", "common");
        cfg.set("arsenal.share", 5);
        TreasuresConfig config = TreasuresConfig.parse(cfg, LOGGER);
        assertEquals(10, config.streakWindowSeconds());
        assertEquals(100, config.streakBonusEvery());
        assertEquals(576, config.rankReward(TreasureRank.LEGEND));
        assertEquals(TreasureTier.EPIC, config.effects().titleMinTier());
        assertEquals(TreasureTier.COMMON, config.effects().fireworkMinTier());
        assertEquals(1.0, config.arsenalShare(), 1e-9);
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

    @Test
    void tierChanceGrowsWithRarityAndIsConfigurable() {
        TreasuresConfig defaults = TreasuresConfig.parse(new YamlConfiguration(), LOGGER);
        assertTrue(defaults.tiersEnabled());
        assertEquals(20.0, defaults.tierChance(TreasureTier.COMMON), 1e-9);
        assertEquals(40.0, defaults.tierChance(TreasureTier.RARE), 1e-9);
        assertEquals(60.0, defaults.tierChance(TreasureTier.EPIC), 1e-9);
        assertEquals(80.0, defaults.tierChance(TreasureTier.LEGENDARY), 1e-9);
        assertEquals(100.0, defaults.tierChance(TreasureTier.MYTHICAL), 1e-9);

        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("tiers.enabled", false);
        cfg.set("tiers.chance-percent.common", 150);
        cfg.set("tiers.chance-percent.legendary", -1);
        TreasuresConfig custom = TreasuresConfig.parse(cfg, LOGGER);
        assertFalse(custom.tiersEnabled());
        assertEquals(100.0, custom.tierChance(TreasureTier.COMMON), 1e-9);
        assertEquals(0.0, custom.tierChance(TreasureTier.LEGENDARY), 1e-9);
    }

    @Test
    void mapsHaveSafeDefaults() {
        TreasuresConfig.Maps maps = TreasuresConfig.parse(new YamlConfiguration(), LOGGER).maps();
        assertTrue(maps.enabled());
        assertEquals(12.0, maps.dropChancePercent(), 1e-9);
        assertEquals(0.12, maps.dropChance(), 1e-9);
        assertEquals(3, maps.maxActive());
        assertEquals(8, maps.digsMin());
        assertEquals(20, maps.digsMax());
        assertEquals(24, maps.radius());
        assertEquals(240, maps.expireMinutes());
        assertEquals(240L * 60_000L, maps.expireMillis());
        assertEquals(1.5, maps.namedChanceMultiplier(), 1e-9);
        assertEquals(120, maps.distanceMin());
        assertEquals(480, maps.distanceMax());
        assertEquals(40, maps.hintTicks());
        assertEquals(256, maps.hintDistance());
        assertEquals(TreasureTier.values().length, maps.tierWeights().size(),
                "вес задан для каждой редкости тайника");
        assertFalse(TreasuresConfig.Maps.DISABLED.enabled());
    }

    @Test
    void mapsConfigIsClamped() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("maps.enabled", false);
        cfg.set("maps.drop-chance-percent", 500);
        cfg.set("maps.max-active", 0);
        cfg.set("maps.digs-min", 1000);
        cfg.set("maps.digs-max", 1);
        cfg.set("maps.radius", 5000);
        cfg.set("maps.expire-minutes", 9999);
        cfg.set("maps.named-chance-multiplier", 10);
        cfg.set("maps.distance-min", 100_000);
        cfg.set("maps.distance-max", 1);
        cfg.set("maps.hint-ticks", 1);
        cfg.set("maps.hint-distance", -5);
        cfg.set("maps.tier-chance-percent.epic", 0);
        TreasuresConfig.Maps maps = TreasuresConfig.parse(cfg, LOGGER).maps();
        assertFalse(maps.enabled());
        assertEquals(100.0, maps.dropChancePercent(), 1e-9);
        assertEquals(1, maps.maxActive(), "одна карта — минимум");
        assertEquals(TreasureMap.MAX_DIGS, maps.digsMin());
        assertEquals(TreasureMap.MAX_DIGS, maps.digsMax());
        assertEquals(TreasureMap.MAX_RADIUS, maps.radius());
        assertEquals(1440, maps.expireMinutes(), "сутки — потолок срока");
        assertEquals(3.0, maps.namedChanceMultiplier(), 1e-9);
        assertEquals(2000, maps.distanceMin());
        assertEquals(2000, maps.distanceMax(), "максимум не может быть меньше минимума");
        assertEquals(20, maps.hintTicks());
        assertEquals(0, maps.hintDistance());
        assertEquals(0.0, maps.tierWeights().get(TreasureTier.EPIC), 1e-9);
    }

    @Test
    void shippedConfigDescribesExperience() {
        TreasureExperience.Rule rule = TreasuresConfig.parse(shipped(), LOGGER).experience();
        assertTrue(rule.enabled(), "склянки опыта в config.yml выключены");
        assertEquals(new TreasureExperience.Range(1, 2), rule.range(TreasureTier.COMMON));
        assertEquals(new TreasureExperience.Range(2, 4), rule.range(TreasureTier.RARE));
        assertEquals(new TreasureExperience.Range(3, 6), rule.range(TreasureTier.EPIC));
        assertEquals(new TreasureExperience.Range(5, 9), rule.range(TreasureTier.LEGENDARY));
        assertEquals(new TreasureExperience.Range(8, 14), rule.range(TreasureTier.MYTHICAL));
        assertEquals(25, rule.bonusChancePercent());
        assertEquals(100, rule.bonusMultiplierPercent());
        assertEquals(32, rule.maxPerTreasure());
        // Поставляемый config.yml не должен расходиться с умолчаниями кода,
        // иначе новая установка получает не тот опыт, что описано в README.
        assertEquals(TreasureExperience.defaults(), rule);
    }

    @Test
    void experienceFallsBackToDefaultsAndClamps() {
        TreasureExperience.Rule defaults =
                TreasuresConfig.parse(new YamlConfiguration(), LOGGER).experience();
        assertEquals(TreasureExperience.defaults(), defaults);

        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("experience.enabled", false);
        cfg.set("experience.bottles.mythical.min", 500);
        cfg.set("experience.bottles.mythical.max", 900);
        cfg.set("experience.bottles.common.min", -5);
        cfg.set("experience.bottles.common.max", 5000);
        cfg.set("experience.bonus-chance-percent", 900);
        cfg.set("experience.bonus-multiplier-percent", -100);
        cfg.set("experience.max-per-treasure", 0);
        TreasureExperience.Rule rule = TreasuresConfig.parse(cfg, LOGGER).experience();
        assertFalse(rule.enabled());
        assertEquals(TreasureExperience.MAX_PER_TIER, rule.range(TreasureTier.MYTHICAL).max());
        assertEquals(TreasureExperience.MAX_PER_TIER, rule.range(TreasureTier.COMMON).max());
        assertEquals(0, rule.range(TreasureTier.COMMON).min());
        assertEquals(100, rule.bonusChancePercent());
        assertEquals(0, rule.bonusMultiplierPercent());
        assertEquals(1, rule.maxPerTreasure());
    }

    @Test
    void shippedConfigDescribesMaps() {
        TreasuresConfig.Maps maps = TreasuresConfig.parse(shipped(), LOGGER).maps();
        assertTrue(maps.enabled());
        assertEquals(12.0, maps.dropChancePercent(), 1e-9);
        assertEquals(24, maps.radius());
        assertEquals(8, maps.digsMin());
        assertEquals(20, maps.digsMax());
        assertEquals(45.0, maps.tierWeights().get(TreasureTier.COMMON), 1e-9);
        assertEquals(0.5, maps.tierWeights().get(TreasureTier.MYTHICAL), 1e-9);
    }
}
