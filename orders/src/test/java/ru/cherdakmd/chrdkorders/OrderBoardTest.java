package ru.cherdakmd.chrdkorders;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Генератор доски: веса категорий, границы наград и сроков (1.4.154).
 *
 * <p>Случайность подаётся зерном, поэтому проверки не «мигают» между
 * прогонами: если формула награды или распределение изменятся, тест упадёт.</p>
 */
class OrderBoardTest {

    private static OrderItem item(Material material, int min, int max, double price) {
        return new OrderItem(material, min, max, price);
    }

    private static OrderCategory category(String id, double weight, int minRep, int rep,
                                          double passChance, int passHours, OrderItem... items) {
        return new OrderCategory(id, weight, minRep, rep, passChance, passHours, List.of(items));
    }

    private static Map<String, OrderCategory> map(OrderCategory... categories) {
        Map<String, OrderCategory> out = new LinkedHashMap<>();
        for (OrderCategory category : categories) {
            out.put(category.id(), category);
        }
        return out;
    }

    private static OrderBot bot(String id, String... categories) {
        return new OrderBot(id, "Бот " + id, List.of(categories), List.of("спасибо"));
    }

    @Test
    void draftStaysInsideBotCategoriesAndItemBounds() {
        OrderCategory ores = category("ores", 1.0, 0, 1, 0, 0,
                item(Material.COAL, 10, 20, 0.05));
        OrderCategory farm = category("farm", 1.0, 0, 1, 0, 0,
                item(Material.WHEAT, 5, 9, 0.10));
        Map<String, OrderCategory> categories = map(ores, farm);
        OrderBot miner = bot("miner", "ores");
        OrderBot farmer = bot("farmer", "farm");
        Map<String, OrderCategory> board = map(ores, farm);
        Random random = new Random(11);

        for (int roll = 0; roll < 500; roll++) {
            OrderBoard.Draft draft = OrderBoard.draft(List.of(miner, farmer), board,
                    1.25, 20, 1, 64, random);
            assertNotNull(draft);
            assertTrue(draft.bot().categories().contains(draft.category().id()));
            assertEquals(draft.category(), board.get(draft.category().id()));
            assertTrue(draft.amount() >= draft.item().minAmount());
            assertTrue(draft.amount() <= draft.item().maxAmount());
            assertTrue(draft.rewardDiamonds() >= 1);
            assertTrue(draft.rewardDiamonds() <= 64);
        }
    }

    @Test
    void draftIsNullWithoutUsableBots() {
        OrderCategory empty = category("empty", 1.0, 0, 1, 0, 0);
        assertNull(OrderBoard.draft(List.of(), map(empty), 1.25, 20, 1, 64, new Random(1)));
        OrderBot broken = bot("broken", "empty");
        assertNull(OrderBoard.draft(List.of(broken), map(empty), 1.25, 20, 1, 64,
                new Random(1)));
        assertNull(OrderBoard.draft(List.of(bot("orphan", "missing")), map(empty), 1.25,
                20, 1, 64, new Random(1)));
        assertNull(OrderBoard.draft(null, map(empty), 1.25, 20, 1, 64, new Random(1)));
    }

    @Test
    void categoryWeightsAreRespected() {
        OrderCategory common = category("common", 3.0, 0, 1, 0, 0,
                item(Material.COAL, 1, 1, 0.1));
        OrderCategory rare = category("rare", 1.0, 0, 1, 0, 0,
                item(Material.DIAMOND, 1, 1, 0.1));
        OrderBot bot = bot("bot", "common", "rare");
        Map<String, OrderCategory> board = map(common, rare);
        Random random = new Random(2026);
        int hits = 0;
        int total = 20_000;
        for (int roll = 0; roll < total; roll++) {
            OrderCategory picked = OrderBoard.pickCategory(bot, board, random);
            if (picked.id().equals("common")) {
                hits++;
            }
        }
        double share = hits / (double) total;
        assertTrue(share > 0.70 && share < 0.80, "доля common: " + share);
    }

    @Test
    void rewardAppliesPremiumAndRounds() {
        OrderItem iron = item(Material.IRON_INGOT, 10, 10, 0.5);
        // 10 × 0.5 = 5 «сырьевых»; с наценкой 1.25 — 6.25, округление вниз до 6.
        assertEquals(6, OrderBoard.reward(iron, 10, 1.25, 0, 1, 64, new Random(1)));
        assertEquals(5, OrderBoard.reward(iron, 10, 1.0, 0, 1, 64, new Random(1)));
    }

    @Test
    void rewardIsClampedToConfiguredBounds() {
        OrderItem cheap = item(Material.COAL, 1, 1, 0.1);
        assertEquals(10, OrderBoard.reward(cheap, 1, 1.0, 0, 10, 64, new Random(1)));
        OrderItem rich = item(Material.NETHERITE_SCRAP, 100, 100, 5.0);
        assertEquals(64, OrderBoard.reward(rich, 100, 1.0, 0, 1, 64, new Random(1)));
        assertEquals(1, OrderBoard.reward(cheap, 1, 0.0, 0, 0, 0, new Random(1)));
        assertEquals(2, OrderBoard.reward(cheap, 1, -5.0, 0, 2, 2, new Random(1)));
    }

    @Test
    void jitterStaysWithinPercent() {
        OrderItem gold = item(Material.GOLD_INGOT, 100, 100, 0.1);
        Random random = new Random(99);
        for (int roll = 0; roll < 2_000; roll++) {
            int reward = OrderBoard.reward(gold, 100, 1.0, 20, 1, 500, random);
            assertTrue(reward >= 8 && reward <= 12, "награда: " + reward);
        }
        assertEquals(10, OrderBoard.reward(gold, 100, 1.0, 0, 1, 500, new Random(1)));
    }

    @Test
    void passHoursFollowCategoryChance() {
        OrderCategory noPass = category("no", 1.0, 0, 1, 0, 5,
                item(Material.COAL, 1, 1, 0.1));
        OrderCategory always = category("always", 1.0, 0, 2, 100, 7,
                item(Material.GOLD_INGOT, 1, 1, 0.1));
        assertEquals(0, OrderBoard.passHours(noPass, new Random(1)));
        assertEquals(7, OrderBoard.passHours(always, new Random(1)));
        assertEquals(0, OrderBoard.passHours(null, new Random(1)));
    }

    @Test
    void orderGetsIdAndTimeToLive() {
        OrderCategory category = category("ores", 1.0, 5, 2, 0, 0,
                item(Material.IRON_INGOT, 10, 10, 0.5));
        OrderBot bot = new OrderBot("grom", "Кузнец Гром", List.of("ores"),
                List.of("спасибо"));
        OrderBoard.Draft draft = new OrderBoard.Draft(bot, category,
                category.items().get(0), 10, 8, 1);
        long ttl = 8L * 3_600_000L;
        long now = 1_700_000_000_000L;
        BotOrder order = OrderBoard.toOrder(draft, "42", now, ttl, new Random(5));
        assertNotNull(order);
        assertEquals("42", order.id());
        assertEquals("grom", order.botId());
        assertEquals("Кузнец Гром", order.botName());
        assertEquals("IRON_INGOT", order.material());
        assertEquals(10, order.amount());
        assertEquals(8, order.rewardDiamonds());
        assertEquals(1, order.rewardPassHours());
        assertEquals(5, order.minReputation());
        assertEquals(2, order.reputation());
        assertEquals(now, order.createdAt());
        assertTrue(order.expiresAt() >= now + (long) (ttl * 0.75));
        assertTrue(order.expiresAt() <= now + (long) (ttl * 1.25) + 1L);
        assertFalse(order.expired(now));
        assertTrue(order.expired(order.expiresAt()));
        assertEquals(0L, order.millisLeft(order.expiresAt()));
        assertNull(OrderBoard.toOrder(null, "1", now, ttl, new Random(1)));
    }

    @Test
    void durationIsHumanReadable() {
        assertEquals("", OrderBoard.formatDuration(0));
        assertEquals("", OrderBoard.formatDuration(-5));
        assertEquals("меньше минуты", OrderBoard.formatDuration(59_000));
        assertEquals("1 мин", OrderBoard.formatDuration(60_000));
        assertEquals("1 ч", OrderBoard.formatDuration(3_600_000));
        assertEquals("1 ч 30 мин", OrderBoard.formatDuration(5_400_000));
        assertEquals("7 ч 20 мин", OrderBoard.formatDuration(7 * 3_600_000L + 20 * 60_000L));
    }

    @Test
    void orderRecordsClampBrokenInput() {
        BotOrder order = new BotOrder(" 7 ", " GROM ", " Гром ", " ORES ", " iron_ingot ",
                -4, 10_000, 99, -3, 500, -1L, -1L);
        assertEquals("7", order.id());
        assertEquals("grom", order.botId());
        assertEquals("IRON_INGOT", order.material());
        assertEquals(Material.IRON_INGOT, order.materialOrNull());
        assertEquals(1, order.amount());
        assertEquals(576, order.rewardDiamonds());
        assertEquals(24, order.rewardPassHours(), "потолок награды — сутки");
        assertEquals(0, order.minReputation());
        assertEquals(100, order.reputation());
        assertEquals(0L, order.createdAt());
        assertFalse(order.expired(System.currentTimeMillis()));
        assertEquals(0L, order.millisLeft(System.currentTimeMillis()));
        assertTrue(new BotOrder("1", "b", "B", "c", "NOT_A_MATERIAL", 1, 1, 0, 0, 0, 1, 2)
                .materialOrNull() == null);
    }

    @Test
    void orderItemsKeepAmountInRange() {
        OrderItem stack = new OrderItem(Material.COAL, 50, 50, 0.02);
        assertEquals(50, stack.amount(null));
        Random random = new Random(3);
        List<Integer> seen = new ArrayList<>();
        for (int roll = 0; roll < 200; roll++) {
            seen.add(stack.amount(random));
        }
        assertTrue(seen.stream().allMatch(value -> value == 50));
        OrderItem ranged = new OrderItem(Material.WHEAT, 10, 20, 0.02);
        for (int roll = 0; roll < 200; roll++) {
            int amount = ranged.amount(random);
            assertTrue(amount >= 10 && amount <= 20);
        }
        assertEquals(5.0, stack.rawReward(250), 1.0e-9);
    }
}
