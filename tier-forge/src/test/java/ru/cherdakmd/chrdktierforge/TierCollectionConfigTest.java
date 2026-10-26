package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Настройки коллекции кузни: умолчания, вехи, разделы, титулы (1.4.150). */
class TierCollectionConfigTest {

    private static final Logger LOGGER = Logger.getLogger("TierCollectionConfigTest");

    private static TierCollectionConfig parse(YamlConfiguration yaml) {
        return TierCollectionConfig.parse(yaml, LOGGER);
    }

    @Test
    void defaultsHaveThreePropertyAndTwoAffixMilestones() {
        TierCollectionConfig config = parse(new YamlConfiguration());
        assertTrue(config.enabled());
        assertTrue(config.announceFinds());
        assertTrue(config.titles().enabled());
        assertTrue(config.titles().tab());
        assertEquals(List.of(15, 45, 75),
                config.properties().stream().map(TierCollectionConfig.Milestone::count).toList());
        assertEquals(List.of(10, 20),
                config.affixes().stream().map(TierCollectionConfig.Milestone::count).toList());
        assertEquals(2, config.properties().get(0).passHours());
        assertEquals(32, config.properties().get(2).diamonds());
    }

    @Test
    void fourSectionsAreRewarded() {
        TierCollectionConfig config = parse(new YamlConfiguration());
        assertEquals(4, config.sections().size());
        assertEquals(new TierCollectionConfig.SectionReward(3, 16), config.section("tiers"));
        assertEquals(new TierCollectionConfig.SectionReward(5, 24), config.section("affixes"));
        assertEquals(new TierCollectionConfig.SectionReward(8, 48), config.section("properties"));
        assertEquals(new TierCollectionConfig.SectionReward(15, 96), config.section("all"));
        for (String section : TierCollectionConfig.SECTIONS) {
            assertTrue(config.section(section).hasReward(), section + " — награда по умолчанию");
        }
    }

    @Test
    void unknownSectionHasNoReward() {
        TierCollectionConfig config = parse(new YamlConfiguration());
        assertFalse(config.section("mythic").hasReward());
        assertFalse(config.section(null).hasReward());
    }

    @Test
    void thresholdsMatchCatalog() {
        assertEquals(TierDef.LADDER.size(), TierCollectionConfig.threshold("tiers"));
        assertEquals(TierCollectionConfig.AFFIX_TOTAL, TierCollectionConfig.threshold("affixes"));
        assertEquals(TierCollectionConfig.PROPERTY_TOTAL,
                TierCollectionConfig.threshold("properties"));
        assertEquals(TierCollectionConfig.PROPERTY_TOTAL + TierCollectionConfig.AFFIX_TOTAL
                        + TierDef.LADDER.size(),
                TierCollectionConfig.threshold("all"));
        assertEquals(0, TierCollectionConfig.threshold("unknown"));
        assertEquals(0, TierCollectionConfig.threshold(null));
    }

    @Test
    void lastLadderMilestoneStopsBeforeSection() {
        TierCollectionConfig config = parse(new YamlConfiguration());
        assertTrue(config.properties().get(config.properties().size() - 1).count()
                < TierCollectionConfig.PROPERTY_TOTAL,
                "полный набор свойств — уже раздел, а не промежуточная веха");
        assertTrue(config.affixes().get(config.affixes().size() - 1).count()
                < TierCollectionConfig.AFFIX_TOTAL);
    }

    @Test
    void nullConfigFallsBackToDefaults() {
        assertEquals(TierCollectionConfig.DEFAULT, TierCollectionConfig.parse(null, LOGGER));
    }

    @Test
    void customRewardsAreRead() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("collection.enabled", false);
        yaml.set("collection.announce-finds", false);
        yaml.set("collection.titles.enabled", false);
        yaml.set("collection.rewards.sections.all.pass-hours", 30);
        yaml.set("collection.rewards.sections.all.diamonds", 100);
        yaml.set("collection.rewards.properties.30.pass-hours", 5);
        yaml.set("collection.rewards.properties.30.diamonds", 12);
        yaml.set("collection.rewards.affixes.15.pass-hours", 1);
        yaml.set("collection.rewards.affixes.15.diamonds", 3);

        TierCollectionConfig config = parse(yaml);
        assertFalse(config.enabled());
        assertFalse(config.announceFinds());
        assertFalse(config.titles().enabled());
        assertEquals(24, config.section("all").passHours(), "потолок награды — сутки");
        assertEquals(100, config.section("all").diamonds());
        assertEquals(new TierCollectionConfig.SectionReward(3, 16), config.section("tiers"),
                "незаданный раздел остаётся на умолчании");
        assertEquals(1, config.properties().size());
        assertEquals(30, config.properties().get(0).count());
        assertEquals(1, config.affixes().size());
        assertEquals(3, config.affixes().get(0).diamonds());
    }

    @Test
    void zeroedSectionMeansNoReward() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("collection.rewards.sections.all.pass-hours", 0);
        yaml.set("collection.rewards.sections.all.diamonds", 0);
        TierCollectionConfig config = parse(yaml);
        assertFalse(config.section("all").hasReward());
        assertTrue(config.section("tiers").hasReward());
    }

    @Test
    void emptyMilestoneSectionMeansNoMilestones() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.createSection("collection.rewards.properties");
        yaml.createSection("collection.rewards.affixes");
        TierCollectionConfig config = parse(yaml);
        assertTrue(config.properties().isEmpty(), "пустая секция — «за вехи не платим»");
        assertTrue(config.affixes().isEmpty());
        assertEquals(4, config.sections().size(), "разделы при этом остаются");
    }

    @Test
    void milestonesAreSortedAndOutOfRangeKeysDropped() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("collection.rewards.properties.105.pass-hours", 9);
        yaml.set("collection.rewards.properties.15.pass-hours", 1);
        yaml.set("collection.rewards.properties.999.pass-hours", 100);
        yaml.set("collection.rewards.properties.0.pass-hours", 100);
        yaml.set("collection.rewards.properties.abc.pass-hours", 100);

        List<TierCollectionConfig.Milestone> milestones = parse(yaml).properties();
        assertEquals(List.of(15, 105),
                milestones.stream().map(TierCollectionConfig.Milestone::count).toList());
        assertEquals(1, milestones.get(0).passHours());
        assertEquals(9, milestones.get(1).passHours());
    }

    @Test
    void rewardsAreClampedToSafeRanges() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("collection.rewards.sections.tiers.pass-hours", 9999);
        yaml.set("collection.rewards.sections.tiers.diamonds", -5);
        yaml.set("collection.rewards.properties.15.pass-hours", -1);
        yaml.set("collection.rewards.properties.15.diamonds", 9999);
        TierCollectionConfig config = parse(yaml);
        assertEquals(365, config.section("tiers").passHours(), "больше года за раздел не выдаём");
        assertEquals(0, config.section("tiers").diamonds());
        assertEquals(0, config.properties().get(0).passHours());
        assertEquals(576, config.properties().get(0).diamonds(), "9 стаков алмазов — потолок");
    }

    @Test
    void milestoneAndSectionRewardFlags() {
        TierCollectionConfig.Milestone empty = new TierCollectionConfig.Milestone(15, 0, 0);
        assertFalse(empty.hasReward());
        assertTrue(new TierCollectionConfig.Milestone(15, 0, 4).hasReward());
        assertFalse(new TierCollectionConfig.SectionReward(0, 0).hasReward());
        assertTrue(new TierCollectionConfig.SectionReward(1, 0).hasReward());
    }

    @Test
    void constructorSanitisesNulls() {
        TierCollectionConfig config = new TierCollectionConfig(true, false, null, null, null, null);
        assertNotNull(config.properties());
        assertTrue(config.properties().isEmpty());
        assertTrue(config.affixes().isEmpty());
        assertTrue(config.sections().isEmpty());
        assertFalse(config.section("all").hasReward());
        assertTrue(config.titles().enabled(), "null-секция титулов — умолчания");
        assertEquals(TierCollectionConfig.Titles.DEFAULT.tabFormat(), config.titles().tabFormat());
    }

    @Test
    void tabTitleSettingsAreParsedAndSanitised() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("collection.titles.tab.enabled", false);
        yaml.set("collection.titles.tab.name-format", "<gray>%player%</gray> — %title%");
        yaml.set("collection.titles.tab.refresh-ticks", 5);
        TierCollectionConfig.Titles titles = parse(yaml).titles();
        assertFalse(titles.tab());
        assertEquals("<gray>%player%</gray> — %title%", titles.tabFormat());
        assertEquals(20, titles.tabRefreshTicks(), "период клампится снизу до 20 тиков");
    }

    @Test
    void tabFormatProtectsPlayerName() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("collection.titles.tab.name-format", "%title%");
        assertTrue(parse(yaml).titles().tabFormat().startsWith("%player%"),
                "без %player% ник пропал бы из таба — аддон возвращает его сам");

        YamlConfiguration empty = new YamlConfiguration();
        empty.set("collection.titles.tab.name-format", "");
        assertEquals(TierCollectionConfig.Titles.DEFAULT.tabFormat(),
                parse(empty).titles().tabFormat(), "пустой шаблон — умолчание");
    }

    @Test
    void sectionOrderIsFromSmallToWhole() {
        TierCollectionConfig config = parse(new YamlConfiguration());
        Map<String, TierCollectionConfig.SectionReward> sections = config.sections();
        assertEquals(List.of("tiers", "affixes", "properties", "all"),
                List.copyOf(sections.keySet()));
    }
}
