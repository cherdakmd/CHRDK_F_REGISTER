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
    void collectionSectionIsWiredIntoConfig() {
        YamlConfiguration yaml = empty();
        yaml.set("collection.enabled", false);
        yaml.set("collection.rewards.properties.15.pass-hours", 5);
        yaml.set("collection.rewards.properties.15.diamonds", 7);
        TierForgeConfig config = parse(yaml).config();
        assertFalse(config.collection().enabled(), "флаг из collection.enabled доходит до снимка");
        assertEquals(1, config.collection().properties().size());
        assertEquals(5, config.collection().properties().get(0).passHours());
        assertEquals(7, config.collection().properties().get(0).diamonds());
    }

    @Test
    void defaultsAreSane() {
        TierForgeConfig config = parse(empty()).config();
        assertEquals(100, config.rollOnCraftChancePercent());
        assertEquals(100, config.craftChanceFor(ItemKind.SWORD));
        assertEquals(100, config.craftChanceFor(ItemKind.CHESTPLATE));
        assertEquals(100, config.craftChanceFor(ItemKind.PICKAXE));
        assertEquals(100, config.craftChanceFor(ItemKind.UTILITY),
                "утилита получает тир по умолчанию");
        assertEquals(0, config.craftChanceFor(null), "без вида наката нет");
        assertTrue(config.anvilEnabled());
        assertTrue(config.rerollEnabled());
        assertEquals("AMETHYST_SHARD", config.rerollCatalyst());
        assertEquals(10, config.identifyScanTicks());
        assertEquals(5, config.identifyScanPlayersPerTick());
        assertEquals(15, config.mobDropChanceFor(ItemKind.SWORD));
        assertEquals(15, config.mobDropChanceFor(ItemKind.CHESTPLATE));
        assertEquals(15, config.mobDropChanceFor(ItemKind.PICKAXE));
        assertEquals(15, config.mobDropChanceFor(ItemKind.UTILITY));
        assertEquals(0, config.mobDropChanceFor(null));
        assertEquals(2.0, config.mobBossMultiplier(), 1e-9);
    }

    @Test
    void propertiesDefaultsAndClamp() {
        TierForgeConfig config = parse(empty()).config();
        assertTrue(config.propertiesEnabled(), "свойства включены по умолчанию");
        assertEquals(TierProperties.DEFAULT_PER_ITEM, config.propertiesPerItem());

        YamlConfiguration off = empty();
        off.set("properties.enabled", false);
        off.set("properties.per-item", 99);
        Parsed parsed = parse(off);
        assertFalse(parsed.config().propertiesEnabled());
        assertEquals(TierProperties.MAX_PER_ITEM, parsed.config().propertiesPerItem(),
                "больше трёх свойств не выдаём");
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("properties.per-item")));

        YamlConfiguration zero = empty();
        zero.set("properties.per-item", 0);
        Parsed zeroParsed = parse(zero);
        assertEquals(1, zeroParsed.config().propertiesPerItem(), "0 → хотя бы одно свойство");
        assertTrue(zeroParsed.warnings().stream()
                .anyMatch(w -> w.contains("properties.per-item")));
    }

    @Test
    void visualsAreParsedWithConfig() {
        TierForgeConfig config = parse(empty()).config();
        assertTrue(config.visuals().enabled(), "аура включена по умолчанию");
        assertEquals(7, config.visuals().tiers().size());

        YamlConfiguration yaml = empty();
        yaml.set("visuals.enabled", false);
        yaml.set("visuals.interval-ticks", 30);
        TierVisuals visuals = parse(yaml).config().visuals();
        assertFalse(visuals.enabled());
        assertEquals(30, visuals.intervalTicks());
        assertEquals(7, visuals.tiers().size(), "стили читаются и при выключенной ауре");
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
    void scanPlayersPerTickIsClamped() {
        YamlConfiguration low = empty();
        low.set("identify.scan-players-per-tick", 0);
        assertEquals(1, parse(low).config().identifyScanPlayersPerTick());

        YamlConfiguration high = empty();
        high.set("identify.scan-players-per-tick", 500);
        assertEquals(100, parse(high).config().identifyScanPlayersPerTick());

        YamlConfiguration fine = empty();
        fine.set("identify.scan-players-per-tick", 25);
        assertEquals(25, parse(fine).config().identifyScanPlayersPerTick());
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
    void affixesAndEnchantmentsHaveSaneDefaults() {
        TierForgeConfig config = parse(empty()).config();

        assertTrue(config.affixes().enabled(), "аффиксы включены по умолчанию");
        assertEquals(2, config.affixes().maxGood());
        assertEquals(2, config.affixes().maxBad());
        assertEquals(2, config.affixes().goodLimit());
        assertEquals(2, config.affixes().badLimit());

        assertTrue(config.enchants().enabled(), "зачарования включены по умолчанию");
        assertEquals(20, config.enchants().chancePercent());
        assertEquals(2, config.enchants().itemLimit());
        assertEquals(3, config.enchants().levelLimit());
    }

    @Test
    void affixAndEnchantSettingsAreClamped() {
        YamlConfiguration yaml = empty();
        yaml.set("affixes.enabled", false);
        yaml.set("affixes.max-good", 99);
        yaml.set("affixes.max-bad", -4);
        yaml.set("enchantments.enabled", false);
        yaml.set("enchantments.chance-percent", 250);
        yaml.set("enchantments.max-per-item", 9);
        yaml.set("enchantments.max-level", 0);
        Parsed parsed = parse(yaml);
        TierForgeConfig config = parsed.config();

        assertFalse(config.affixes().enabled());
        assertEquals(0, config.affixes().goodLimit(), "выключенные аффиксы не считаются");
        assertEquals(0, config.affixes().badLimit());
        assertEquals(TierAffixes.MAX_PER_SIGN, config.affixes().maxGood(), "потолок 3");
        assertEquals(0, config.affixes().maxBad(), "минус → 0");

        assertFalse(config.enchants().enabled());
        assertEquals(100, config.enchants().chancePercent());
        assertEquals(3, config.enchants().maxPerItem());
        assertEquals(1, config.enchants().maxLevel(), "ниже I не бывает");
        assertEquals(0, config.enchants().itemLimit(), "выключено — 0 чар");

        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("affixes.max-good")));
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("enchantments.max-level")));
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
    void utilityIsItsOwnCraftGroup() {
        // 1.4.142: у утилиты свой флаг — щит и рог не должны зависеть от
        // настройки инструментов или брони.
        assertTrue(parse(empty()).config().kindAllowed(ItemKind.UTILITY),
                "по умолчанию утилита получает тир");
        assertEquals(ForgeNamePool.DEFAULT_UTILITIES,
                parse(empty()).config().namesUtilities(),
                "имена утилиты по умолчанию — из реестра пула");
        YamlConfiguration yaml = empty();
        yaml.set("craft.utilities", false);
        assertFalse(parse(yaml).config().kindAllowed(ItemKind.UTILITY));

        YamlConfiguration chance = empty();
        chance.set("roll-on-craft-chance-percent", 40);
        chance.set("craft.utilities-chance-percent", 7);
        assertEquals(7, parse(chance).config().craftChanceFor(ItemKind.UTILITY));
        chance.set("craft.utilities-chance-percent", -1);
        assertEquals(40, parse(chance).config().craftChanceFor(ItemKind.UTILITY),
                "-1 = общий шанс");

        YamlConfiguration drop = empty();
        drop.set("mob-drop.chance-percent", 10);
        drop.set("mob-drop.utilities-chance-percent", 35);
        assertEquals(35, parse(drop).config().mobDropChanceFor(ItemKind.UTILITY));
        drop.set("mob-drop.utilities-chance-percent", -1);
        assertEquals(10, parse(drop).config().mobDropChanceFor(ItemKind.UTILITY));
    }

    @Test
    void mobDropChancePerKind() {
        YamlConfiguration yaml = empty();
        yaml.set("mob-drop.chance-percent", 10);
        yaml.set("mob-drop.weapons-chance-percent", 0);
        yaml.set("mob-drop.armor-chance-percent", 50);
        yaml.set("mob-drop.tools-chance-percent", -1);
        TierForgeConfig config = parse(yaml).config();

        assertEquals(0, config.mobDropChanceFor(ItemKind.SWORD), "0 — группе выключено");
        assertEquals(50, config.mobDropChanceFor(ItemKind.HELMET));
        assertEquals(10, config.mobDropChanceFor(ItemKind.PICKAXE), "-1 = общий шанс");
    }

    @Test
    void mobDropChancePerKindIsClamped() {
        YamlConfiguration yaml = empty();
        yaml.set("mob-drop.chance-percent", 20);
        yaml.set("mob-drop.weapons-chance-percent", 150);
        yaml.set("mob-drop.armor-chance-percent", -9);
        TierForgeConfig config = parse(yaml).config();

        assertEquals(100, config.mobDropChanceFor(ItemKind.SWORD));
        assertEquals(20, config.mobDropChanceFor(ItemKind.HELMET),
                "-9 → -1 → общий шанс, не отрицательный");
    }

    @Test
    void mobDropWorldsAndKillOnly() {
        YamlConfiguration worlds = empty();
        worlds.set("mob-drop.world-blacklist", List.of(" World_Nether ", "", "CREATIVE"));
        TierForgeConfig config = parse(worlds).config();
        assertTrue(config.mobWorldBlocked("world_nether"), "регистр не важен");
        assertTrue(config.mobWorldBlocked("CREATIVE"));
        assertFalse(config.mobWorldBlocked("world"));
        assertFalse(config.mobWorldBlocked(null));
        assertFalse(config.mobPlayerKillOnly(), "по умолчанию — старое поведение");

        YamlConfiguration killOnly = empty();
        killOnly.set("mob-drop.player-kill-only", true);
        assertTrue(parse(killOnly).config().mobPlayerKillOnly());
    }

    @Test
    void statsBackupCopiesAreClamped() {
        assertEquals(3, parse(empty()).config().statsBackupCopies(), "по умолчанию три копии");
        YamlConfiguration none = empty();
        none.set("stats.backup-copies", -5);
        assertEquals(0, parse(none).config().statsBackupCopies(), "минус — копии выключены");
        YamlConfiguration many = empty();
        many.set("stats.backup-copies", 99);
        assertEquals(StatsBackup.MAX_COPIES, parse(many).config().statsBackupCopies());
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

    @Test
    void worldSourcesDefaultsAndPerKindChances() {
        TierForgeConfig config = parse(empty()).config();
        assertTrue(config.worldEnabled(), "мировая редкость включена по умолчанию");
        assertEquals(25, config.worldChancePercent());
        assertEquals(25, config.worldChanceFor(ItemKind.SWORD));
        assertEquals(25, config.worldChanceFor(ItemKind.CHESTPLATE));
        assertEquals(25, config.worldChanceFor(ItemKind.PICKAXE));
        assertEquals(25, config.worldChanceFor(ItemKind.UTILITY));
        assertEquals(0, config.worldChanceFor(null), "без вида наката нет");
        assertTrue(config.worldLootEnabled(), "сундуки и структуры включены");
        assertTrue(config.worldFishingEnabled(), "рыбалка включена");
        assertFalse(config.worldWorldBlocked("world"));

        YamlConfiguration yaml = empty();
        yaml.set("world.chance-percent", 40);
        yaml.set("world.weapons-chance-percent", 0);
        yaml.set("world.armor-chance-percent", 90);
        TierForgeConfig custom = parse(yaml).config();
        assertEquals(0, custom.worldChanceFor(ItemKind.SWORD), "0 — группе выключено");
        assertEquals(90, custom.worldChanceFor(ItemKind.HELMET));
        assertEquals(40, custom.worldChanceFor(ItemKind.PICKAXE), "-1 = общий шанс");
    }

    @Test
    void worldSourcesAreClampedAndWorldsAreNormalized() {
        YamlConfiguration yaml = empty();
        yaml.set("world.chance-percent", 500);
        yaml.set("world.weapons-chance-percent", 150);
        yaml.set("world.armor-chance-percent", -9);
        yaml.set("world.loot-enabled", false);
        yaml.set("world.fishing-enabled", false);
        yaml.set("world.world-blacklist", List.of(" World_Nether ", "", "CREATIVE"));
        Parsed parsed = parse(yaml);
        TierForgeConfig config = parsed.config();

        assertEquals(100, config.worldChancePercent(), "500% → 100%");
        assertEquals(100, config.worldChanceFor(ItemKind.SWORD));
        assertEquals(100, config.worldChanceFor(ItemKind.HELMET),
                "-9 → -1 → общий шанс, а не отрицательный");
        assertFalse(config.worldLootEnabled());
        assertFalse(config.worldFishingEnabled());
        assertTrue(config.worldWorldBlocked("world_nether"), "регистр не важен");
        assertTrue(config.worldWorldBlocked("CREATIVE"));
        assertFalse(config.worldWorldBlocked("world"));
        assertFalse(config.worldWorldBlocked(null));
        assertTrue(parsed.warnings().stream()
                .anyMatch(w -> w.contains("world.chance-percent")));
        assertTrue(parsed.warnings().stream()
                .anyMatch(w -> w.contains("world.weapons-chance-percent")));
    }
}
