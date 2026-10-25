package ru.cherdakmd.chrdkartifacts;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactsConfigTest {

    private static ArtifactsConfig parse(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return ArtifactsConfig.parse(cfg, Logger.getLogger("ArtifactsConfigTest"));
    }

    @Test
    void traitDefaultsAndCustomSection() throws Exception {
        assertEquals(ArtifactsConfig.Traits.DEFAULT, parse("").traits());

        ArtifactsConfig custom = parse("""
                traits:
                  enabled: false
                  chance-percent: 20
                  max-per-item: 1
                  max-level: 2
                  cross-chance-percent: 50
                """);
        assertEquals(new ArtifactsConfig.Traits(false, 20, 1, 2, 50), custom.traits());
    }

    @Test
    void traitValuesAreClamped() throws Exception {
        ArtifactsConfig clamped = parse("""
                traits:
                  chance-percent: 500
                  max-per-item: 9
                  max-level: 0
                  cross-chance-percent: -5
                """);
        assertEquals(new ArtifactsConfig.Traits(true, 100, 3, 1, 0), clamped.traits());
    }

    @Test
    void collectionDefaultsAndCustomRewards() throws Exception {
        assertEquals(ArtifactsConfig.Collection.DEFAULT, parse("").collection());

        ArtifactsConfig custom = parse("""
                collection:
                  enabled: false
                  rewards:
                    80: {pass-days: 5, diamonds: 40}
                    20: {pass-days: 1, diamonds: 4}
                """);
        assertEquals(new ArtifactsConfig.Collection(false, List.of(
                new CollectionRewards.Tier(20, 1, 4),
                new CollectionRewards.Tier(80, 5, 40))), custom.collection());
    }

    @Test
    void collectionRewardsAreClampedAndBadKeysSkipped() throws Exception {
        ArtifactsConfig config = parse("""
                collection:
                  rewards:
                    250: {pass-days: 99999, diamonds: -5}
                    abc: {pass-days: 1, diamonds: 1}
                    50: {pass-days: 2, diamonds: 16}
                """);
        assertEquals(2, config.collection().rewards().size());
        // Список нормализуется по возрастанию: сначала 50, затем ужатый до 100 порог.
        assertEquals(50, config.collection().rewards().get(0).percent());
        CollectionRewards.Tier capped = config.collection().rewards().get(1);
        assertEquals(100, capped.percent());
        assertEquals(365, capped.passDays());
        assertEquals(0, capped.diamonds());
    }

    @Test
    void feedbackDefaultsAndOverrides() throws Exception {
        assertEquals(ArtifactsConfig.Feedback.DEFAULT, parse("").feedback());
        assertEquals(new ArtifactsConfig.Feedback(false, false, 0), parse("""
                feedback:
                  actionbar: false
                  sounds: false
                  curse-message-seconds: 0
                """).feedback());
        assertEquals(3600, parse("""
                feedback:
                  curse-message-seconds: 99999
                """).feedback().curseMessageSeconds());
    }

    @Test
    void lootTableShortNameIsLastSegment() throws Exception {
        assertEquals("ancient_city", ArtifactsConfig.shortName("minecraft:chests/ancient_city"));
        assertEquals("ancient_city", ArtifactsConfig.shortName("mine_treasure:chests/ancient_city"));
        assertEquals("ancient_city", ArtifactsConfig.shortName("ANCIENT_CITY"));
        assertEquals("end_city_treasure",
                ArtifactsConfig.shortName("minecraft:chests/end_city_treasure"));
    }

    @Test
    void datapackNamespaceMatchesByShortName() throws Exception {
        ArtifactsConfig config = parse("""
                sources:
                  chests:
                    minecraft:chests/ancient_city: 12
                """);
        assertEquals(12.0, config.chestChancePercent("minecraft:chests/ancient_city"), 1e-9);
        // Таблица датапака приходит со своим namespace — совпадение по последнему сегменту.
        assertEquals(12.0, config.chestChancePercent("mine_treasure:chests/ancient_city"), 1e-9);
        assertEquals(0.0, config.chestChancePercent("minecraft:chests/shipwreck_treasure"), 1e-9);
        assertEquals(0.0, config.chestChancePercent(null), 1e-9);
    }

    @Test
    void worldPolicyDefaultsAndOverrides() throws Exception {
        assertTrue(parse("").worldPolicy().allowsWeather("world"));
        assertTrue(parse("").worldPolicy().allowsTime("world"));

        ArtifactsConfig limited = parse("""
                world-effects:
                  weather: false
                  time: true
                  worlds: [world]
                """);
        assertFalse(limited.worldPolicy().allowsWeather("world"));
        assertTrue(limited.worldPolicy().allowsTime("world"));
        assertFalse(limited.worldPolicy().allowsTime("world_the_end"));
    }

    @Test
    void overridesAreClamped() throws Exception {
        ArtifactsConfig config = parse("""
                general:
                  passive-refresh-ticks: 9999
                artifacts:
                  rog-tajgi:
                    enabled: false
                    cooldown-seconds: -5
                    xp-cost-levels: 999
                """);
        assertEquals(200, config.refreshTicks());
        assertFalse(config.isEnabled("rog-tajgi"));
        ArtifactDef dash = ArtifactRegistry.byId("rog-tajgi");
        assertEquals(0, config.cooldownSeconds(dash));
        assertEquals(100, config.xpCostLevels(dash));
    }
}
