package ru.cherdakmd.chrdktierforge;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Конфиг шансов и границ (1.4.80): шанс по виду предмета, клампы scan-ticks,
 * множителя босса и «сломанных» процентов. Ловим и предупреждения в лог.
 */
class TierForgeConfigTest {

    /** Конфиг с перехватом предупреждений. */
    private record Parsed(TierForgeConfig config, List<String> warnings) {
    }

    private static Parsed parse(YamlConfiguration yaml) {
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
        return new Parsed(TierForgeConfig.parse(yaml, logger), warnings);
    }

    private static YamlConfiguration empty() {
        return new YamlConfiguration();
    }

    @Test
    void defaultsAreSane() {
        TierForgeConfig config = parse(empty()).config();
        assertEquals(100, config.rollOnCraftChancePercent());
        assertEquals(100, config.craftChanceFor(ItemKind.SWORD));
        assertEquals(100, config.craftChanceFor(ItemKind.CHESTPLATE));
        assertEquals(100, config.craftChanceFor(ItemKind.PICKAXE));
        assertEquals(0, config.craftChanceFor(null), "без вида наката нет");
        assertTrue(config.anvilEnabled());
        assertTrue(config.rerollEnabled());
        assertEquals("AMETHYST_SHARD", config.rerollCatalyst());
        assertEquals(10, config.identifyScanTicks());
        assertEquals(2.0, config.mobBossMultiplier(), 1e-9);
    }

    @Test
    void perKindChanceOverridesGlobal() {
        YamlConfiguration yaml = empty();
        yaml.set("roll-on-craft-chance-percent", 40);
        yaml.set("craft.weapons-chance-percent", 5);
        yaml.set("craft.armor-chance-percent", 75);
        yaml.set("craft.tools-chance-percent", -1);
        TierForgeConfig config = parse(yaml).config();

        assertEquals(5, config.craftChanceFor(ItemKind.SWORD));
        assertEquals(5, config.craftChanceFor(ItemKind.AXE), "топор — оружие");
        assertEquals(75, config.craftChanceFor(ItemKind.HELMET));
        assertEquals(40, config.craftChanceFor(ItemKind.PICKAXE), "-1 = общий шанс");
    }

    @Test
    void outOfRangeKindChanceIsClampedWithWarning() {
        YamlConfiguration yaml = empty();
        yaml.set("roll-on-craft-chance-percent", 30);
        yaml.set("craft.weapons-chance-percent", 150);
        yaml.set("craft.armor-chance-percent", -5);
        yaml.set("craft.tools-chance-percent", 0);
        Parsed parsed = parse(yaml);
        TierForgeConfig config = parsed.config();

        assertEquals(100, config.craftChanceFor(ItemKind.SWORD), "150% → 100%");
        assertEquals(30, config.craftChanceFor(ItemKind.CHESTPLATE),
                "-5 → -1 → общий шанс, а не отрицательный");
        assertEquals(0, config.craftChanceFor(ItemKind.PICKAXE), "0 — законный «выключено»");
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("craft.weapons-chance-percent")));
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("craft.armor-chance-percent")));
    }

    @Test
    void scanTicksStayInSafeRange() {
        YamlConfiguration low = empty();
        low.set("identify.scan-ticks", 1);
        assertEquals(10, parse(low).config().identifyScanTicks(), "реже, чем раз в 10 тиков");

        YamlConfiguration high = empty();
        high.set("identify.scan-ticks", 99_999);
        assertEquals(1200, parse(high).config().identifyScanTicks(), "не чаще, чем раз в минуту");

        YamlConfiguration fine = empty();
        fine.set("identify.scan-ticks", 40);
        assertEquals(40, parse(fine).config().identifyScanTicks());
    }

    @Test
    void bossMultiplierIsClampedToOneAndTwenty() {
        YamlConfiguration low = empty();
        low.set("mob-drop.boss-multiplier", 0.25);
        assertEquals(1.0, parse(low).config().mobBossMultiplier(), 1e-9,
                "меньше 1.0 бессмысленно: босс не может быть слабее обычного моба");

        YamlConfiguration high = empty();
        high.set("mob-drop.boss-multiplier", 500);
        assertEquals(20.0, parse(high).config().mobBossMultiplier(), 1e-9);

        YamlConfiguration fine = empty();
        fine.set("mob-drop.boss-multiplier", 3.5);
        assertEquals(3.5, parse(fine).config().mobBossMultiplier(), 1e-9);
    }

    @Test
    void kindChanceHelperAcceptsOnlyMinusOneToHundred() {
        Logger silent = Logger.getAnonymousLogger();
        assertEquals(-1, TierForgeConfig.kindChance(-1, silent, "k"));
        assertEquals(0, TierForgeConfig.kindChance(0, silent, "k"));
        assertEquals(100, TierForgeConfig.kindChance(100, silent, "k"));
        assertEquals(-1, TierForgeConfig.kindChance(-7, silent, "k"));
        assertEquals(100, TierForgeConfig.kindChance(101, silent, "k"));
    }

    @Test
    void materialBlacklistIgnoresCaseAndBlanks() {
        YamlConfiguration yaml = empty();
        yaml.set("craft.material-blacklist", List.of(" diamond_sword ", "", "BOW"));
        TierForgeConfig config = parse(yaml).config();
        assertTrue(config.materialBlocked(Material.DIAMOND_SWORD));
        assertTrue(config.materialBlocked(Material.BOW));
        assertFalse(config.materialBlocked(Material.IRON_SWORD));
        assertFalse(config.materialBlocked(null));
    }

    @Test
    void craftFlagsGovernKinds() {
        YamlConfiguration yaml = empty();
        yaml.set("craft.armor", false);
        yaml.set("craft.weapons", true);
        yaml.set("craft.tools", false);
        TierForgeConfig config = parse(yaml).config();
        assertTrue(config.kindAllowed(ItemKind.SWORD));
        assertFalse(config.kindAllowed(ItemKind.CHESTPLATE));
        assertFalse(config.kindAllowed(ItemKind.PICKAXE));
        assertFalse(config.kindAllowed(null));
    }

    @Test
    void brokenCatalystDisablesReroll() {
        YamlConfiguration yaml = empty();
        yaml.set("reroll.catalyst", "NOT_A_MATERIAL");
        Parsed parsed = parse(yaml);
        assertFalse(parsed.config().rerollEnabled(), "неизвестный катализатор гасит перекат");
        assertNotNull(parsed.config().rerollCatalyst());
    }

    @Test
    void improveMaterialsFallBackToDefaults() {
        YamlConfiguration yaml = empty();
        yaml.set("improve.materials.edge", "NOT_A_MATERIAL");
        Parsed parsed = parse(yaml);
        assertEquals(ForgeUpgrade.forMaterial("QUARTZ", ItemKind.SWORD,
                        parsed.config().improveMaterials()).id(), "edge",
                "битый материал возвращается к умолчанию QUARTZ");
    }
}
