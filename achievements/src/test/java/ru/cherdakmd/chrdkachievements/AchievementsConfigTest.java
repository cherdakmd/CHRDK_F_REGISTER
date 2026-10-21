package ru.cherdakmd.chrdkachievements;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AchievementsConfigTest {

    private static AchievementsConfig parse(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return AchievementsConfig.parse(cfg, Logger.getLogger("AchievementsConfigTest"));
    }

    @Test
    void defaultsAreSane() throws Exception {
        AchievementsConfig config = parse("");
        assertTrue(config.enabled());
        assertTrue(config.announce());
        assertTrue(config.actionbar());
        assertFalse(config.countCreative());
        assertFalse(config.countFlying());
        assertTrue(config.badges());
        assertEquals(60, config.timeTickSeconds());
    }

    @Test
    void valuesAreClamped() throws Exception {
        AchievementsConfig config = parse("""
                achievements:
                  playtime-tick-seconds: 5
                """);
        assertEquals(10, config.timeTickSeconds());
        assertEquals(600, parse("""
                achievements:
                  playtime-tick-seconds: 99999
                """).timeTickSeconds());
    }

    @Test
    void rewardOverrideReplacesCatalogueValues() throws Exception {
        AchievementDef veteran = AchievementRegistry.byId("veteran");
        // По умолчанию — 5 дней и 40 алмазов из каталога.
        assertEquals(new AchievementsConfig.Reward(5, 40), parse("").rewardOf(veteran));

        AchievementsConfig custom = parse("""
                rewards:
                  veteran:
                    pass-days: 10
                    diamonds: 64
                """);
        assertEquals(new AchievementsConfig.Reward(10, 64), custom.rewardOf(veteran));

        // Одно поле можно не указывать: второе остаётся каталожным.
        AchievementsConfig partial = parse("""
                rewards:
                  veteran:
                    diamonds: 0
                """);
        assertEquals(new AchievementsConfig.Reward(5, 0), partial.rewardOf(veteran));
    }

    @Test
    void rewardOverridesAreClampedAndUnknownIdsSkipped() throws Exception {
        AchievementsConfig config = parse("""
                rewards:
                  veteran:
                    pass-days: 9999
                    diamonds: -7
                  nesushchestvuyushchee:
                    pass-days: 5
                """);
        assertEquals(new AchievementsConfig.Reward(365, 0),
                config.rewardOf(AchievementRegistry.byId("veteran")));
        // Неизвестный ид просто не попал в карту — каталог не пострадал.
        assertEquals(new AchievementsConfig.Reward(2, 12),
                config.rewardOf(AchievementRegistry.byId("sutki-v-puti")));
    }
}
