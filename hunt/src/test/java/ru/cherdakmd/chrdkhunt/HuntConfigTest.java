package ru.cherdakmd.chrdkhunt;

import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор config.yml охоты: умолчания, клампы, отбраковка опечаток и звания
 * (1.4.155).
 */
class HuntConfigTest {

    private static final Logger LOGGER = Logger.getLogger("HuntConfigTest");

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(text);
        return config;
    }

    private static HuntConfig parse(String text) throws Exception {
        return HuntConfig.parse(yaml(text), LOGGER);
    }

    @Test
    void emptyConfigFallsBackToDefaults() throws Exception {
        HuntConfig config = parse("");
        assertTrue(config.enabled());
        assertEquals("ru", config.locale());
        assertEquals(10, config.topSize());
        assertTrue(config.actionbar());
        assertFalse(config.worldDisabled("world"));
        assertFalse(config.worldDisabled(null));
        assertEquals(2_000, config.coinsPerDay());
        assertEquals(8, config.exchangePerDay());
        assertEquals(40, config.coinsPerDiamond());
        assertEquals(1, config.exchangeMin());
        assertTrue(config.blockSpawners());
        assertTrue(config.blockEggs());
        assertTrue(config.blockCommand());
        assertTrue(config.families().isEmpty());
        assertTrue(config.mobRules().isEmpty());
        assertTrue(config.shop().isEmpty());
        assertTrue(config.trophies().isEmpty());
        assertNull(config.family("undead"));
        assertNull(config.offer("bread"));
        assertNull(config.trophy("skull"));
        assertNull(config.ruleFor(EntityType.ZOMBIE));
        assertEquals("novice", config.rankId(0L));
        assertEquals("legend", config.rankId(99_999L));
        assertEquals(300, config.nextRankThreshold(0L));
        assertEquals("tracker", config.nextRankId(0L));
        assertEquals(-1, config.nextRankThreshold(10_000L));
        assertNull(config.nextRankId(10_000L));
        assertEquals(10_000, config.lastRankThreshold());
        assertEquals(5, HuntConfig.defaultRanks().size());
        assertEquals("hunter", HuntConfig.defaultRanks().get(1_200));
        assertEquals(HuntConfig.parse(null, LOGGER).topSize(), config.topSize());
    }

    @Test
    void valuesAreClampedToSafeBounds() throws Exception {
        HuntConfig config = parse("""
                general:
                  enabled: false
                  locale: en
                  top-size: 500
                  actionbar: false
                  disabled-worlds: [World, " HUB "]
                limits:
                  coins-per-day: -5
                  exchange-per-day: 999
                  coins-per-diamond: 0
                  exchange-min: 10
                anti-farm:
                  block-spawners: false
                  block-eggs: false
                  block-command: false
                """);
        assertFalse(config.enabled());
        assertEquals("en", config.locale());
        assertEquals(HuntConfig.MAX_TOP_SIZE, config.topSize());
        assertFalse(config.actionbar());
        assertTrue(config.worldDisabled("world"));
        assertTrue(config.worldDisabled("WORLD"));
        assertTrue(config.worldDisabled("hub"));
        assertEquals(0, config.coinsPerDay());
        assertEquals(100, config.exchangePerDay());
        assertEquals(1, config.coinsPerDiamond());
        assertEquals(10, config.exchangeMin());
        assertFalse(config.blockSpawners());
        assertFalse(config.blockEggs());
        assertFalse(config.blockCommand());
    }

    @Test
    void bestiarySectionsRewardsAndIconsAreParsed() throws Exception {
        HuntConfig config = parse("""
                families:
                  undead:
                    name: "Нежить"
                    shard: "Осколок кости"
                    key: "Ключ склепа"
                  nether:
                    name: "Нижний мир"
                    shard: "Осколок пламени"
                    key: "Ключ портала"
                mobs:
                  ZOMBIE:
                    family: undead
                    coins: [2, 5]
                    shard-chance: 15
                    shards: [1, 1]
                  SKELETON:
                    family: undead
                    coins: [2, 5]
                  BLAZE:
                    family: nether
                    coins: [4, 8]
                    boss: true
                bestiary:
                  enabled: true
                  full-coins: 5000
                  sections:
                    undead:
                      coins: 150
                      shards: 10
                      keys: 1
                    nether:
                      coins: -50
                      shards: 999999
                    missing:
                      coins: 10
                  icons:
                    ZOMBIE: ROTTEN_FLESH
                    UNKNOWN_MOB: BONE
                    SKELETON: NOT_A_MATERIAL
                """);
        HuntBestiary bestiary = config.bestiary();
        assertEquals(2, bestiary.sectionCount());
        assertEquals(3, bestiary.totalEntries());
        assertEquals(5000, bestiary.fullCoins());

        HuntBestiary.Section undead = bestiary.section("undead");
        assertNotNull(undead);
        assertEquals(new HuntBestiary.Reward(150, 10, 1), undead.reward());
        assertEquals(Material.ROTTEN_FLESH, bestiary.entry("zombie").icon(),
                "иконка из конфига переопределяет голову");
        assertEquals(Material.SKELETON_SPAWN_EGG, bestiary.entry("skeleton").icon(),
                "неизвестный материал — иконка по умолчанию");

        HuntBestiary.Section nether = bestiary.section("nether");
        assertNotNull(nether);
        assertEquals(0, nether.reward().coins(), "отрицательная награда зажимается в ноль");
        assertEquals(10_000, nether.reward().shards());
        assertTrue(nether.entries().get(0).boss());
    }

    @Test
    void bestiaryCanBeTurnedOff() throws Exception {
        HuntConfig config = parse("""
                families:
                  undead:
                    name: "Нежить"
                mobs:
                  ZOMBIE:
                    family: undead
                    coins: [2, 5]
                bestiary:
                  enabled: false
                  full-coins: 100
                """);
        assertTrue(config.bestiary().empty());
        assertEquals(0, config.bestiary().fullCoins());
        assertEquals(1, config.mobRules().size(), "охота работает и без бестиария");
    }

    @Test
    void oldConfigWithoutBestiaryGetsBuiltInRewards() throws Exception {
        HuntConfig config = parse("""
                families:
                  undead:
                    name: "Нежить"
                mobs:
                  ZOMBIE:
                    family: undead
                    coins: [2, 5]
                """);
        HuntBestiary bestiary = config.bestiary();
        assertEquals(1, bestiary.sectionCount());
        assertEquals(new HuntBestiary.Reward(150, 10, 1),
                bestiary.section("undead").reward(),
                "без секции bestiary действуют встроенные награды");
        assertEquals(1500, bestiary.fullCoins());
        assertEquals(5, HuntBestiary.defaultRewards().size());
    }

    @Test
    void emptyConfigHasEmptyBestiary() throws Exception {
        HuntConfig config = parse("");
        assertTrue(config.bestiary().empty());
        assertEquals(0, config.bestiary().totalEntries());
    }

    @Test
    void exchangeMinimumCannotExceedDailyLimit() throws Exception {
        HuntConfig config = parse("""
                limits:
                  exchange-per-day: 3
                  exchange-min: 10
                """);
        assertEquals(3, config.exchangePerDay());
        assertEquals(3, config.exchangeMin(), "минимум не должен быть больше суточного потолка");
    }

    @Test
    void familiesMobsShopAndTrophiesAreParsed() throws Exception {
        HuntConfig config = parse("""
                families:
                  undead:
                    name: "Нежить"
                    shard: "Осколок кости"
                    key: "Ключ склепа"
                    coin-multiplier: 99
                    shard-item: BONE
                    key-item: TRIPWIRE_HOOK
                    chest:
                      coins: [5, 10]
                      shards: [1, 2]
                      diamonds-chance: 500
                      diamonds: [1, 1]
                      loot:
                        - item: BONE
                          amount: [2, 4]
                          chance: 50
                        - item: NOT_A_MATERIAL
                          amount: [1, 1]
                          chance: 10
                mobs:
                  ZOMBIE:
                    family: undead
                    coins: [2, 5]
                    shard-chance: 15
                    shards: [1, 2]
                    key-chance: 2
                  UNKNOWN_MOB:
                    family: undead
                    coins: [1, 1]
                  COW:
                    family: missing
                    coins: [1, 1]
                  SKELETON:
                    family: missing
                    coins: [1, 1]
                  CREEPER:
                    family: undead
                    coins: [0, 0]
                    shard-chance: 0
                    key-chance: 0
                shop:
                  bread:
                    item: BREAD
                    amount: 999
                    price: 0
                    daily-limit: -1
                  broken:
                    item: NOT_A_MATERIAL
                    amount: 1
                    price: 1
                trophies:
                  skull:
                    family: undead
                    item: SKELETON_SKULL
                    name: "Череп"
                    lore: ["Строка"]
                    shards: 12
                    coins: 120
                  lost:
                    family: missing
                    item: BONE
                  bad_item:
                    family: undead
                    item: NOT_A_MATERIAL
                """);
        assertEquals(1, config.families().size());
        HuntFamily family = config.family("UNDEAD");
        assertNotNull(family);
        assertEquals("Нежить", family.displayName());
        assertEquals("Осколок кости", family.shardName());
        assertEquals("Ключ склепа", family.keyName());
        assertEquals(10.0, family.coinMultiplier(), 1.0e-9, "множитель зажимается в 10");
        assertEquals(Material.BONE, family.shardItem());
        assertEquals(Material.TRIPWIRE_HOOK, family.keyItem());
        assertTrue(family.hasChest());
        HuntChest chest = family.chest();
        assertNotNull(chest);
        assertEquals(5, chest.minCoins());
        assertEquals(10, chest.maxCoins());
        assertEquals(1, chest.minShards());
        assertEquals(2, chest.maxShards());
        assertEquals(100.0, chest.diamondChance(), 1.0e-9);
        assertEquals(1, chest.minDiamonds());
        assertEquals(1, chest.maxDiamonds());
        assertEquals(1, chest.loot().size(), "неизвестный материал пропущен");

        assertEquals(1, config.mobRules().size());
        MobRule zombie = config.ruleFor(EntityType.ZOMBIE);
        assertNotNull(zombie);
        assertEquals("undead", zombie.familyId());
        assertEquals(2, zombie.minCoins());
        assertEquals(5, zombie.maxCoins());
        assertEquals(15.0, zombie.shardChance(), 1.0e-9);
        assertEquals(2.0, zombie.keyChance(), 1.0e-9);
        assertNull(config.ruleFor(EntityType.COW), "правило с чужим семейством пропущено");
        assertNull(config.ruleFor(EntityType.CREEPER), "правило без награды пропущено");
        assertNull(config.ruleFor(null));

        assertEquals(1, config.shop().size());
        HuntShopOffer bread = config.offer("BREAD");
        assertNotNull(bread);
        assertEquals(64, bread.amount());
        assertEquals(1, bread.price());
        assertEquals(0, bread.dailyLimit());
        assertNull(config.offer(null));

        assertEquals(1, config.trophies().size());
        HuntTrophy skull = config.trophy("skull");
        assertNotNull(skull);
        assertEquals("undead", skull.familyId());
        assertEquals(Material.SKELETON_SKULL, skull.material());
        assertEquals(12, skull.shards());
        assertEquals(120, skull.coins());
        assertEquals(1, skull.lore().size());
        assertEquals(1, config.trophiesOf("undead").size());
        assertTrue(config.trophiesOf("missing").isEmpty());
    }

    @Test
    void ranksFollowThresholdsAndSkipBrokenEntries() throws Exception {
        HuntConfig config = parse("""
                ranks:
                  0: novice
                  3: helper
                  "-1": broken
                  10: ""
                """);
        assertEquals("novice", config.rankId(0L));
        assertEquals("novice", config.rankId(2L));
        assertEquals("helper", config.rankId(3L));
        assertEquals("helper", config.rankId(9L));
        assertEquals(3, config.nextRankThreshold(0L));
        assertEquals(-1, config.nextRankThreshold(3L));
        assertEquals("helper", config.nextRankId(0L));
        assertNull(config.nextRankId(3L));
        assertEquals(3, config.lastRankThreshold());
    }

    @Test
    void rangeAcceptsListNumberAndNull() {
        assertEquals(2, HuntConfig.range(List.of(5, 2), 1, 1)[0]);
        assertEquals(5, HuntConfig.range(List.of(5, 2), 1, 1)[1]);
        assertEquals(7, HuntConfig.range(7, 1, 1)[0]);
        assertEquals(7, HuntConfig.range(7, 1, 1)[1]);
        assertEquals(4, HuntConfig.range(List.of(4), 1, 1)[0]);
        assertEquals(4, HuntConfig.range(List.of(4), 1, 1)[1]);
        assertEquals(1, HuntConfig.range(null, 1, 3)[0]);
        assertEquals(3, HuntConfig.range(null, 1, 3)[1]);
    }
}
