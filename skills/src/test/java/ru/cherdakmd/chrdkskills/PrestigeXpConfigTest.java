package ru.cherdakmd.chrdkskills;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.5.2: ключи баланса престижа читаются из config.yml и клампятся. */
final class PrestigeXpConfigTest {

    private static SkillsConfig parse(YamlConfiguration yaml) {
        return SkillsConfig.parse(yaml, Logger.getLogger("prestige-xp-config-test"));
    }

    @Test
    void stockConfigEnablesStrictBalanceByDefault() {
        SkillsConfig config = parse(YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/config.yml").toFile()));
        assertTrue(config.prestigeEnabled());
        assertTrue(config.prestigeBalanceEnabled());
        assertEquals(0, config.prestigeBalanceMaxLead(),
                "по умолчанию отрыв ступеней не разрешён");
        assertEquals(60, config.prestigeBalanceNoticeSeconds());
    }

    @Test
    void absentKeysFallBackToTheSafeDefaults() {
        SkillsConfig config = parse(new YamlConfiguration());
        assertTrue(config.prestigeBalanceEnabled());
        assertEquals(0, config.prestigeBalanceMaxLead());
        assertEquals(60, config.prestigeBalanceNoticeSeconds());
    }

    @Test
    void ownerCanDisableOrLoosenTheRuleAndValuesAreClamped() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("prestige.balance.enabled", false);
        yaml.set("prestige.balance.max-lead", 3);
        yaml.set("prestige.balance.notice-seconds", 300);
        SkillsConfig config = parse(yaml);
        assertFalse(config.prestigeBalanceEnabled());
        assertEquals(3, config.prestigeBalanceMaxLead());
        assertEquals(300, config.prestigeBalanceNoticeSeconds());

        yaml.set("prestige.balance.enabled", true);
        yaml.set("prestige.balance.max-lead", 999);
        yaml.set("prestige.balance.notice-seconds", 1);
        SkillsConfig clamped = parse(yaml);
        assertTrue(clamped.prestigeBalanceEnabled());
        assertEquals(PrestigeMath.MAX_PER_SKILL, clamped.prestigeBalanceMaxLead());
        assertEquals(5, clamped.prestigeBalanceNoticeSeconds());

        yaml.set("prestige.balance.max-lead", -7);
        yaml.set("prestige.balance.notice-seconds", 99999);
        SkillsConfig clampedAgain = parse(yaml);
        assertEquals(0, clampedAgain.prestigeBalanceMaxLead());
        assertEquals(3600, clampedAgain.prestigeBalanceNoticeSeconds());
    }
}
