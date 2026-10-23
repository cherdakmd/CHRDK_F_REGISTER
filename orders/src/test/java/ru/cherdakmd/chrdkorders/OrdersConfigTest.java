package ru.cherdakmd.chrdkorders;

import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор config.yml: клампы, отбраковка опечаток и звания (1.4.154).
 */
class OrdersConfigTest {

    private static final Logger LOGGER = Logger.getLogger("OrdersConfigTest");

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(text);
        return config;
    }

    private static OrdersConfig parse(String text) throws Exception {
        return OrdersConfig.parse(yaml(text), LOGGER);
    }

    @Test
    void emptyConfigFallsBackToDefaults() throws Exception {
        OrdersConfig config = parse("");
        assertTrue(config.enabled());
        assertEquals("ru", config.locale());
        assertEquals(10, config.topSize());
        assertEquals(7, config.boardSize());
        assertEquals(8, config.orderHours());
        assertEquals(8L * 3_600_000L, config.orderTtlMillis());
        assertEquals(1.25, config.premium(), 1.0e-9);
        assertEquals(20, config.jitterPercent());
        assertEquals(1, config.minReward());
        assertEquals(64, config.maxReward());
        assertEquals(20, config.maxPerDay());
        assertEquals(30, config.cooldownSeconds());
        assertEquals(0, config.bots().size());
        assertEquals(0, config.categories().size());
        assertEquals("novice", config.rankId(0));
        assertEquals("master", config.rankId(1_000));
        assertEquals(-1, config.nextRankThreshold(1_000));
        assertEquals(OrdersConfig.MAX_BOARD_SIZE, 14);
        assertEquals(OrdersConfig.parse(null, LOGGER).boardSize(), config.boardSize());
    }

    @Test
    void valuesAreClampedToSafeBounds() throws Exception {
        OrdersConfig config = parse("""
                general:
                  enabled: false
                  locale: en
                  top-size: 500
                board:
                  size: 500
                  order-hours: 0
                rewards:
                  premium: 99
                  jitter-percent: 200
                  min: 40
                  max: 5
                limits:
                  max-per-day: -5
                  cooldown-seconds: 99999
                """);
        assertFalse(config.enabled());
        assertEquals("en", config.locale());
        assertEquals(50, config.topSize());
        assertEquals(14, config.boardSize());
        assertEquals(1, config.orderHours());
        assertEquals(3_600_000L, config.orderTtlMillis());
        assertEquals(3.0, config.premium(), 1.0e-9);
        assertEquals(50, config.jitterPercent());
        assertEquals(40, config.minReward());
        assertEquals(40, config.maxReward(), "максимум не может быть ниже минимума");
        assertEquals(0, config.maxPerDay());
        assertEquals(3600, config.cooldownSeconds());
    }

    @Test
    void unknownMaterialsAndDeadCategoriesAreSkipped() throws Exception {
        OrdersConfig config = parse("""
                categories:
                  ores:
                    weight: 10
                    min-reputation: -4
                    reputation: 999
                    pass-days-chance: 500
                    pass-days: 100
                    items:
                      COAL:
                        amount-min: 10
                        amount-max: 20
                        reward-per-item: 0.05
                      NOT_A_MATERIAL:
                        amount-min: 1
                        amount-max: 2
                        reward-per-item: 1.0
                  zero:
                    weight: 0
                    items:
                      COAL:
                        amount-min: 1
                        amount-max: 1
                        reward-per-item: 0.1
                  empty:
                    weight: 5
                    items: {}
                """);
        assertEquals(1, config.categories().size());
        OrderCategory ores = config.category("ORES");
        assertNotNull(ores);
        assertEquals(0, ores.minReputation());
        assertEquals(100, ores.reputation());
        assertEquals(100.0, ores.passChance(), 1.0e-9);
        assertEquals(OrderCategory.MAX_PASS_DAYS, ores.passDays());
        assertEquals(1, ores.items().size());
        assertEquals(Material.COAL, ores.items().get(0).material());
        assertEquals(10, ores.items().get(0).minAmount());
        assertEquals(20, ores.items().get(0).maxAmount());
        assertEquals(0.05, ores.items().get(0).rewardPerItem(), 1.0e-9);
        assertNull(config.category("missing"));
        assertNull(config.category(null));
    }

    @Test
    void botsWithoutCategoriesAreSkipped() throws Exception {
        OrdersConfig config = parse("""
                bots:
                  grom:
                    name: "Кузнец Гром"
                    categories: [ores, smithing]
                    thanks:
                      - "Спасибо"
                  broken:
                    name: "Без категорий"
                categories:
                  ores:
                    weight: 1
                    items:
                      COAL:
                        amount-min: 1
                        amount-max: 2
                        reward-per-item: 0.1
                """);
        assertEquals(1, config.bots().size());
        OrderBot bot = config.bots().get(0);
        assertEquals("grom", bot.id());
        assertEquals("Кузнец Гром", bot.name());
        assertEquals(java.util.List.of("ores", "smithing"), bot.categories());
        assertEquals(java.util.List.of("Спасибо"), bot.thanks());
    }

    @Test
    void ranksFollowThresholdsAndSkipBrokenEntries() throws Exception {
        OrdersConfig config = parse("""
                reputation:
                  ranks:
                    0: novice
                    3: helper
                    "-1": broken
                    10: ""
                """);
        assertEquals("novice", config.rankId(0));
        assertEquals("novice", config.rankId(2));
        assertEquals("helper", config.rankId(3));
        assertEquals("helper", config.rankId(9));
        assertEquals(3, config.nextRankThreshold(0));
        assertEquals(-1, config.nextRankThreshold(3));
        assertEquals("helper", config.nextRankId(0));
        assertNull(config.nextRankId(3));
        assertEquals(3, config.lastRankThreshold());
        assertEquals(5, OrdersConfig.defaultRanks().size());
        assertEquals("trusted", OrdersConfig.defaultRanks().get(5));
    }

    @Test
    void rewardsKeepOrderInBounds() {
        OrderItem item = new OrderItem(Material.DIAMOND, 0, 100_000, -5.0);
        assertEquals(1, item.minAmount());
        assertEquals(OrderItem.MAX_AMOUNT, item.maxAmount());
        assertEquals(0.0, item.rewardPerItem(), 1.0e-9);
        OrderCategory category = new OrderCategory(null, -1, -1, -1, -1, -1, null);
        assertEquals("", category.id());
        assertEquals(0.0, category.weight(), 1.0e-9);
        assertEquals(0, category.minReputation());
        assertEquals(0, category.reputation());
        assertEquals(0.0, category.passChance(), 1.0e-9);
        assertEquals(0, category.passDays());
        assertFalse(category.usable());
        assertNull(category.pick(null));
        assertTrue(new OrderBot(null, null, null, null).id().isEmpty());
    }
}
