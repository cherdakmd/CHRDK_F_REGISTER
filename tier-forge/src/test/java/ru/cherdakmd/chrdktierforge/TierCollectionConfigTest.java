package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Настройки коллекции кузни: умолчания, вехи, клампы (1.4.150). */
class TierCollectionConfigTest {

    private static final Logger LOGGER = Logger.getLogger("TierCollectionConfigTest");

    private static TierCollectionConfig parse(YamlConfiguration yaml) {
        return TierCollectionConfig.parse(yaml, LOGGER);
    }

    @Test
    void defaultsHaveFourPropertyAndThreeAffixMilestones() {
        TierCollectionConfig config = parse(new YamlConfiguration());
        assertTrue(config.enabled());
        assertTrue(config.announceFinds());
        assertEquals(7, config.tiers().count());
        assertEquals(3, config.tiers().passDays());
        assertEquals(16, config.tiers().diamonds());

        assertEquals(List.of(15, 45, 75, 105),
                config.properties().stream().map(TierCollectionConfig.Milestone::count).toList());
        assertEquals(List.of(10, 20, 30),
                config.affixes().stream().map(TierCollectionConfig.Milestone::count).toList());
        assertEquals(12, config.properties().get(3).passDays());
        assertEquals(64, config.properties().get(3).diamonds());
        assertEquals(24, config.affixes().get(2).diamonds());
    }

    @Test
    void lastMilestoneMatchesCatalogSize() {
        TierCollectionConfig config = parse(new YamlConfiguration());
        assertEquals(TierCollectionConfig.PROPERTY_TOTAL,
                config.properties().get(config.properties().size() - 1).count(),
                "последняя веха по свойствам — весь каталог");
        assertEquals(TierCollectionConfig.AFFIX_TOTAL,
                config.affixes().get(config.affixes().size() - 1).count());
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
        yaml.set("collection.rewards.tiers.pass-days", 10);
        yaml.set("collection.rewards.tiers.diamonds", 0);
        yaml.set("collection.rewards.properties.30.pass-days", 5);
        yaml.set("collection.rewards.properties.30.diamonds", 12);
        yaml.set("collection.rewards.affixes.15.pass-days", 1);
        yaml.set("collection.rewards.affixes.15.diamonds", 3);

        TierCollectionConfig config = parse(yaml);
        assertFalse(config.enabled());
        assertFalse(config.announceFinds());
        assertEquals(10, config.tiers().passDays());
        assertEquals(0, config.tiers().diamonds());
        assertEquals(TierDef.LADDER.size(), config.tiers().count(),
                "порог редкостей всегда равен каталогу");
        assertEquals(1, config.properties().size());
        assertEquals(30, config.properties().get(0).count());
        assertEquals(1, config.affixes().size());
        assertEquals(3, config.affixes().get(0).diamonds());
    }

    @Test
    void emptySectionMeansNoMilestones() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.createSection("collection.rewards.properties");
        yaml.createSection("collection.rewards.affixes");
        TierCollectionConfig config = parse(yaml);
        assertTrue(config.properties().isEmpty(), "пустая секция — «за свойства не платим»");
        assertTrue(config.affixes().isEmpty());
        assertTrue(config.tiers().hasReward(),
                "веха редкостей не отключается пустой секцией — она всегда есть");
    }

    @Test
    void milestonesAreSortedAndOutOfRangeKeysDropped() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("collection.rewards.properties.105.pass-days", 9);
        yaml.set("collection.rewards.properties.15.pass-days", 1);
        yaml.set("collection.rewards.properties.999.pass-days", 100);
        yaml.set("collection.rewards.properties.0.pass-days", 100);
        yaml.set("collection.rewards.properties.abc.pass-days", 100);

        List<TierCollectionConfig.Milestone> milestones = parse(yaml).properties();
        assertEquals(List.of(15, 105),
                milestones.stream().map(TierCollectionConfig.Milestone::count).toList());
        assertEquals(1, milestones.get(0).passDays());
        assertEquals(9, milestones.get(1).passDays());
    }

    @Test
    void rewardsAreClampedToSafeRanges() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("collection.rewards.tiers.pass-days", 9999);
        yaml.set("collection.rewards.tiers.diamonds", -5);
        yaml.set("collection.rewards.properties.15.pass-days", -1);
        yaml.set("collection.rewards.properties.15.diamonds", 9999);
        TierCollectionConfig config = parse(yaml);
        assertEquals(365, config.tiers().passDays(), "больше года за одну веху не выдаём");
        assertEquals(0, config.tiers().diamonds());
        assertEquals(0, config.properties().get(0).passDays());
        assertEquals(576, config.properties().get(0).diamonds(), "9 стаков алмазов — потолок");
    }

    @Test
    void milestoneWithoutRewardIsRecognised() {
        TierCollectionConfig.Milestone empty = new TierCollectionConfig.Milestone(15, 0, 0);
        assertFalse(empty.hasReward());
        assertTrue(new TierCollectionConfig.Milestone(15, 0, 4).hasReward());
        assertTrue(new TierCollectionConfig.Milestone(15, 1, 0).hasReward());
    }

    @Test
    void constructorSanitisesNulls() {
        TierCollectionConfig config = new TierCollectionConfig(true, false, null, null, null);
        assertNotNull(config.tiers());
        assertEquals(7, config.tiers().count());
        assertTrue(config.properties().isEmpty());
        assertTrue(config.affixes().isEmpty());
    }
}
