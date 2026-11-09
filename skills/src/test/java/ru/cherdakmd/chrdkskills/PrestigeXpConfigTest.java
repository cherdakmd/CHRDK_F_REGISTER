package ru.cherdakmd.chrdkskills;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Мягкая зона баланса: дефолты, совместимость со старым max-lead и клампы. */
final class PrestigeXpConfigTest {

    private static final double EPS = 1e-9;
    private static final List<Integer> RANKS =
            SkillsConfig.DEFAULT_BALANCE_RANK_MAX_LEAD;

    private static SkillsConfig parse(YamlConfiguration yaml) {
        return SkillsConfig.parse(yaml, Logger.getLogger("prestige-xp-config-test"));
    }

    private static void assertStockDefaults(SkillsConfig config) {
        assertTrue(config.prestigeEnabled());
        assertTrue(config.prestigeBalanceEnabled());
        assertEquals(SkillsConfig.DEFAULT_BALANCE_BASE_MAX_LEAD,
                config.prestigeBalanceBaseMaxLead());
        assertEquals(RANKS, config.prestigeBalanceRankMaxLead());
        assertEquals(SkillsConfig.DEFAULT_BALANCE_HARD_CAP,
                config.prestigeBalanceHardCap());
        assertEquals(SkillsConfig.DEFAULT_BALANCE_SOFT_STEP,
                config.prestigeBalanceSoftStep(), EPS);
        assertEquals(SkillsConfig.DEFAULT_BALANCE_MIN_XP_FACTOR,
                config.prestigeBalanceMinXpFactor(), EPS);
        assertEquals(SkillsConfig.DEFAULT_BALANCE_NOTICE_SECONDS,
                config.prestigeBalanceNoticeSeconds());
    }

    @Test
    void stockConfigShipsTheSoftZoneAndAutomaticAmnesty() {
        assertStockDefaults(parse(YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/config.yml").toFile())));
        assertEquals(2, SkillsConfig.DEFAULT_BALANCE_BASE_MAX_LEAD);
        assertEquals(List.of(0, 1, 1, 2, 2, 3), RANKS);
        assertEquals(8, SkillsConfig.DEFAULT_BALANCE_HARD_CAP);
        assertEquals(0.25, SkillsConfig.DEFAULT_BALANCE_SOFT_STEP, EPS);
        assertEquals(0.25, SkillsConfig.DEFAULT_BALANCE_MIN_XP_FACTOR, EPS);
        assertEquals(5, SkillsConfig.DEFAULT_BALANCE_NOTICE_SECONDS);
    }

    @Test
    void absentKeysFallBackToTheSameSafeDefaults() {
        assertStockDefaults(parse(new YamlConfiguration()));
    }

    @Test
    void legacyMaxLeadIsOnlyAFallbackForBaseMaxLead() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("prestige.balance.max-lead", 3);
        assertEquals(3, parse(yaml).prestigeBalanceBaseMaxLead(),
                "владельческий config.yml старой схемы не теряет порог");

        yaml.set("prestige.balance.max-lead", 1);
        assertEquals(1, parse(yaml).prestigeBalanceBaseMaxLead(),
                "даже ослабление до +1 сохранено");

        yaml.set("prestige.balance.max-lead", 3);
        yaml.set("prestige.balance.base-max-lead", 4);
        assertEquals(4, parse(yaml).prestigeBalanceBaseMaxLead(),
                "новый ключ владельца не перезаписывается старым");
    }

    @Test
    void stockRc6StrictParityDoesNotCancelTheJarOnlyAmnesty() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("prestige.balance.max-lead", 0);
        assertEquals(SkillsConfig.DEFAULT_BALANCE_BASE_MAX_LEAD,
                parse(yaml).prestigeBalanceBaseMaxLead(),
                "владельческий config.yml rc.6 не переписывается, но база 2 включается");

        yaml.set("prestige.balance.max-lead", -7);
        assertEquals(SkillsConfig.DEFAULT_BALANCE_BASE_MAX_LEAD,
                parse(yaml).prestigeBalanceBaseMaxLead(),
                "битый старый ключ не ломает новую базу");
    }

    @Test
    void stockRc6ChatCooldownDoesNotBecomeAMinuteLongActionbar() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("prestige.balance.notice-seconds", 60);
        assertEquals(SkillsConfig.DEFAULT_BALANCE_NOTICE_SECONDS,
                parse(yaml).prestigeBalanceNoticeSeconds(),
                "штатные 60 секунд чата rc.6 читаются как 5 секунд actionbar");

        yaml.set("prestige.balance.notice-seconds", 300);
        assertEquals(300, parse(yaml).prestigeBalanceNoticeSeconds(),
                "владельческий кулдаун, отличный от 60, сохранён");
    }

    @Test
    void shortRankListIsPaddedWithDefaultsAndLongOneIsTruncated() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("prestige.balance.rank-max-lead", List.of(4));
        assertEquals(List.of(4, 1, 1, 2, 2, 3),
                parse(yaml).prestigeBalanceRankMaxLead(),
                "короткий список дополняется дефолтами, а не нулями");

        yaml.set("prestige.balance.rank-max-lead",
                List.of(1, 2, 3, 4, 5, 6, 77));
        assertEquals(List.of(1, 2, 3, 4, 5, 6),
                parse(yaml).prestigeBalanceRankMaxLead(),
                "лишние ступени отбрасываются");

        yaml.set("prestige.balance.rank-max-lead", List.of(9, -7, 2));
        assertEquals(List.of(9, 0, 2, 2, 2, 3),
                parse(yaml).prestigeBalanceRankMaxLead(),
                "отрицательная аддитивка прижата к нулю");
        assertEquals(PrestigeXpGate.RANK_LEVELS,
                parse(yaml).prestigeBalanceRankMaxLead().size());
    }

    @Test
    void ownerCanDisableOrLoosenTheRuleAndEveryValueIsClamped() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("prestige.balance.enabled", false);
        yaml.set("prestige.balance.base-max-lead", 3);
        yaml.set("prestige.balance.hard-cap", 9);
        yaml.set("prestige.balance.soft-step", 0.10);
        yaml.set("prestige.balance.min-xp-factor", 0.50);
        yaml.set("prestige.balance.notice-seconds", 300);
        SkillsConfig custom = parse(yaml);
        assertFalse(custom.prestigeBalanceEnabled());
        assertEquals(3, custom.prestigeBalanceBaseMaxLead());
        assertEquals(9, custom.prestigeBalanceHardCap());
        assertEquals(0.10, custom.prestigeBalanceSoftStep(), EPS);
        assertEquals(0.50, custom.prestigeBalanceMinXpFactor(), EPS);
        assertEquals(300, custom.prestigeBalanceNoticeSeconds());

        yaml.set("prestige.balance.enabled", true);
        yaml.set("prestige.balance.base-max-lead", 999);
        yaml.set("prestige.balance.hard-cap", 999);
        yaml.set("prestige.balance.soft-step", 2.0);
        yaml.set("prestige.balance.min-xp-factor", -0.5);
        yaml.set("prestige.balance.notice-seconds", 99999);
        SkillsConfig upper = parse(yaml);
        assertTrue(upper.prestigeBalanceEnabled());
        assertEquals(PrestigeMath.MAX_PER_SKILL, upper.prestigeBalanceBaseMaxLead());
        assertEquals(PrestigeMath.MAX_PER_SKILL, upper.prestigeBalanceHardCap());
        assertEquals(1.0, upper.prestigeBalanceSoftStep(), EPS);
        assertEquals(0.0, upper.prestigeBalanceMinXpFactor(), EPS);
        assertEquals(3600, upper.prestigeBalanceNoticeSeconds());

        yaml.set("prestige.balance.base-max-lead", -7);
        yaml.set("prestige.balance.hard-cap", -3);
        yaml.set("prestige.balance.soft-step", -1.0);
        yaml.set("prestige.balance.min-xp-factor", 5.0);
        yaml.set("prestige.balance.notice-seconds", 0);
        SkillsConfig lower = parse(yaml);
        assertEquals(0, lower.prestigeBalanceBaseMaxLead());
        assertEquals(1, lower.prestigeBalanceHardCap(),
                "нулевой потолок не выключает прокачку целиком");
        assertEquals(0.0, lower.prestigeBalanceSoftStep(), EPS);
        assertEquals(1.0, lower.prestigeBalanceMinXpFactor(), EPS);
        assertEquals(1, lower.prestigeBalanceNoticeSeconds());
    }
}
