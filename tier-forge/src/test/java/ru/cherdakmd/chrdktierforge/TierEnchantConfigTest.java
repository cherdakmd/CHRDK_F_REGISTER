package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Секция {@code enchantments.custom.*} (волна 18): дефолты, клампы и порог
 * проклятий. Без Bukkit-сервера — только разбор YAML.
 */
class TierEnchantConfigTest {

    private static List<String> parseWarnings(YamlConfiguration yaml) {
        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        TierEnchantConfig.parse(yaml, logger);
        return warnings;
    }

    @Test
    void defaultsMatchDocumentedConfig() {
        TierEnchantConfig config = TierEnchantConfig.parse(new YamlConfiguration(),
                Logger.getAnonymousLogger());
        assertEquals(TierEnchantConfig.DEFAULT, config);
        assertTrue(config.customEnabled());
        assertEquals(18, config.customChancePercent());
        assertEquals(1, config.customItemLimit());
        assertEquals(15, config.customCurseChancePercent());
        assertEquals(5, config.customCurseMinTier());
    }

    @Test
    void customSectionIsRead() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("enchantments.custom.enabled", true);
        yaml.set("enchantments.custom.chance-percent", 40);
        yaml.set("enchantments.custom.max-per-item", 2);
        yaml.set("enchantments.custom.curse-chance-percent", 25);
        yaml.set("enchantments.custom.curse-min-tier", 3);
        TierEnchantConfig config = TierEnchantConfig.parse(yaml, Logger.getAnonymousLogger());
        assertEquals(40, config.customChancePercent());
        assertEquals(2, config.customItemLimit());
        assertEquals(25, config.customCurseChancePercent());
        assertEquals(3, config.customCurseMinTier());
        assertTrue(config.cursesAllowedAt(3));
        assertFalse(config.cursesAllowedAt(2));
    }

    @Test
    void disabledSectionGivesNoCustomEnchants() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("enchantments.custom.enabled", false);
        yaml.set("enchantments.custom.chance-percent", 50);
        TierEnchantConfig config = TierEnchantConfig.parse(yaml, Logger.getAnonymousLogger());
        assertFalse(config.customEnabled());
        assertEquals(0, config.customItemLimit());
        assertEquals(50, config.customChancePercent(), "шанс сохраняется для /tiersadmin reload");
    }

    @Test
    void outOfRangeValuesAreClamped() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("enchantments.custom.chance-percent", 500);
        yaml.set("enchantments.custom.max-per-item", 9);
        yaml.set("enchantments.custom.curse-chance-percent", -10);
        yaml.set("enchantments.custom.curse-min-tier", 42);
        List<String> warnings = parseWarnings(yaml);
        TierEnchantConfig config = TierEnchantConfig.parse(yaml, Logger.getAnonymousLogger());
        assertEquals(100, config.customChancePercent());
        assertEquals(2, config.customItemLimit());
        assertEquals(0, config.customCurseChancePercent());
        assertEquals(TierDef.LADDER.size() - 1, config.customCurseMinTier());
        assertFalse(config.cursesAllowedAt(TierDef.LADDER.size() - 1),
                "0% проклятий — порог не имеет значения");
        assertFalse(warnings.isEmpty(), "о клампах должно быть предупреждение в лог");
    }
}
