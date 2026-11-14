package ru.cherdakmd.chrdkskills;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Разбор config.yml: новая база лимита, схема v2 и аддитивные коэффициенты. */
final class DiamondCapConfigTest {

    private static SkillsConfig parse(YamlConfiguration yaml) {
        return SkillsConfig.parse(yaml, Logger.getLogger("diamond-cap-test"));
    }

    @Test
    void stockConfigRaisesTheBaseAndWiresCapSettings() throws Exception {
        YamlConfiguration stock = YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/config.yml").toFile());
        SkillsConfig config = parse(stock);
        assertEquals(256, config.diamonds().dailyCap(), "база выросла с 64 до 256");
        assertEquals(4096, DiamondDrops.MAX_DAILY_CAP);

        DiamondCapMath.Settings cap = config.diamondCap();
        assertEquals(32, cap.perRankLevel());
        assertEquals(1, cap.perRankXpPercent());
        assertEquals(2, cap.perRankDropPercent());
        assertEquals(4, cap.perRankExtraSkill());
        assertEquals(4, cap.perPrestige());
        assertEquals(2, cap.perTenLevels());
        assertEquals(64, cap.passBonus());
        assertEquals(64, cap.boostBonus());
        assertEquals(32, cap.entitlementBonus());
        assertEquals("diamond-cap-boost", cap.entitlementId());
        assertEquals(4096, cap.max());

        DiamondCapMath.Context veteran = new DiamondCapMath.Context(
                5, 50, 25, 10, 100, 1000, true, true, true);
        assertEquals(1316, DiamondCapMath.compute(config.diamonds().dailyCap(), cap, veteran));
        assertEquals(256, DiamondCapMath.compute(config.diamonds().dailyCap(), cap,
                DiamondCapMath.Context.NONE));
    }

    @Test
    void ownerOverridesAndBadValuesAreClamped() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("diamonds.daily-cap", 99999);
        yaml.set("diamonds.cap.per-rank-level", -5);
        yaml.set("diamonds.cap.max", 99999);
        yaml.set("diamonds.cap.entitlement-id", "  My-Boost  ");
        SkillsConfig config = parse(yaml);
        assertEquals(DiamondDrops.MAX_DAILY_CAP, config.diamonds().dailyCap());
        assertEquals(0, config.diamondCap().perRankLevel());
        assertEquals(DiamondDrops.MAX_DAILY_CAP, config.diamondCap().max());
        assertEquals("my-boost", config.diamondCap().entitlementId());
        assertEquals(4096, DiamondCapMath.compute(config.diamonds().dailyCap(),
                config.diamondCap(), DiamondCapMath.Context.NONE));
    }

    @Test
    void schemaBumpedToSecondVersion() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/config-schema.yml"));
        assertTrue(schema.contains("config-version: 3"));
        assertTrue(schema.contains("messages-version: 3"));
        assertTrue(schema.contains("diamonds.cap.*"));
    }
}
