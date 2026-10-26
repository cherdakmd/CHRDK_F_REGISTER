package ru.cherdakmd.chrdkorders;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Хранилище доски: заказы, суточный сброс, репутация и персистентность
 * (1.4.154). Файл пишется в {@link TempDir}, поэтому тесты не трогают сервер.
 */
class OrdersStoreTest {

    private static final Logger LOGGER = Logger.getLogger("OrdersStoreTest");
    private static final long DAY = 86_400_000L;

    private static BotOrder order(String id, long created, long expires) {
        return new BotOrder(id, "grom", "Кузнец Гром", "ores", "IRON_INGOT", 10, 5, 0,
                0, 1, created, expires);
    }

    @Test
    void deliveryCountsReputationAndToday(@TempDir Path dir) {
        OrdersStore store = new OrdersStore(dir, LOGGER);
        UUID id = UUID.randomUUID();
        long now = 1_700_000_000_000L;

        store.recordDelivery(id, "Steve", now, 1);
        store.recordDelivery(id, "Steve", now + 1_000L, 2);

        assertEquals(2, store.completed(id));
        assertEquals(3, store.reputation(id));
        assertEquals(2, store.today(id, now + 1_000L));
        assertEquals("Steve", store.player(id).name());
        assertEquals(now + 1_000L, store.lastDelivery(id));
        assertEquals(2, store.totalCompleted());
        assertEquals(1, store.players());
        assertNull(store.player(UUID.randomUUID()));
        assertEquals(0, store.reputation(UUID.randomUUID()));
    }

    @Test
    void todayResetsOnUtcMidnight(@TempDir Path dir) {
        OrdersStore store = new OrdersStore(dir, LOGGER);
        UUID id = UUID.randomUUID();
        long firstDay = DAY * 10 + 1_000L;

        store.recordDelivery(id, "Steve", firstDay, 1);
        store.recordDelivery(id, "Steve", firstDay + 5_000L, 1);
        assertEquals(2, store.today(id, firstDay + 5_000L));
        assertEquals(2, store.completed(id));

        long nextDay = firstDay + DAY;
        assertEquals(0, store.today(id, nextDay));
        store.recordDelivery(id, "Steve", nextDay, 1);
        assertEquals(1, store.today(id, nextDay));
        assertEquals(3, store.completed(id));
        assertEquals(3, store.reputation(id));
    }

    @Test
    void expiredOrdersArePurged(@TempDir Path dir) {
        OrdersStore store = new OrdersStore(dir, LOGGER);
        long now = 1_000_000L;
        store.add(order("1", now - 2_000L, now + 5_000L));
        store.add(order("2", now - 2_000L, now - 1L));
        store.add(order("3", now - 2_000L, 0L));

        assertEquals(3, store.size());
        assertEquals(1, store.purgeExpired(now));
        assertNotNull(store.order("1"));
        assertNull(store.order("2"));
        assertNotNull(store.order("3"), "заказ без срока не должен сниматься");
        assertEquals(2, store.clear());
        assertEquals(0, store.size());
    }

    @Test
    void idsStayMonotonicAfterReload(@TempDir Path dir) {
        OrdersStore store = new OrdersStore(dir, LOGGER);
        store.add(order("7", 10L, 20L));
        store.flush();

        OrdersStore loaded = new OrdersStore(dir, LOGGER);
        loaded.load();
        assertEquals(1, loaded.size());
        assertEquals("8", loaded.reserveId());
        assertEquals("9", loaded.reserveId());
    }

    @Test
    void stateSurvivesReload(@TempDir Path dir) {
        OrdersStore store = new OrdersStore(dir, LOGGER);
        UUID id = UUID.randomUUID();
        long now = 5_000_000L;
        store.add(order("3", now, now + 60_000L));
        store.recordDelivery(id, "Steve", now, 2);
        store.setReputation(id, 9);
        store.flush();

        OrdersStore loaded = new OrdersStore(dir, LOGGER);
        loaded.load();
        BotOrder restored = loaded.order("3");
        assertNotNull(restored);
        assertEquals("grom", restored.botId());
        assertEquals("IRON_INGOT", restored.material());
        assertEquals(10, restored.amount());
        assertEquals(5, restored.rewardDiamonds());
        assertEquals(now + 60_000L, restored.expiresAt());
        assertEquals(9, loaded.reputation(id));
        assertEquals(1, loaded.completed(id));
        assertEquals(1, loaded.today(id, now));
        assertEquals("Steve", loaded.byName("steve").name());
        assertEquals(id, loaded.byName("STEVE").id());
    }

    @Test
    void unknownMaterialDoesNotBreakLoading(@TempDir Path dir) throws Exception {
        String json = """
                {
                  "version": 1,
                  "next-id": 4,
                  "orders": [
                    {
                      "id": "4",
                      "bot": "grom",
                      "bot-name": "Кузнец Гром",
                      "category": "ores",
                      "material": "NOT_A_MATERIAL",
                      "amount": 5,
                      "reward": 3,
                      "pass-hours": 0,
                      "min-reputation": 0,
                      "reputation": 1,
                      "created": 1,
                      "expires": 2
                    },
                    {
                      "id": "",
                      "material": "COAL",
                      "amount": 5
                    },
                    {
                      "id": "6",
                      "material": "",
                      "amount": 5
                    }
                  ],
                  "players": {}
                }
                """;
        Files.writeString(dir.resolve("orders.json"), json, StandardCharsets.UTF_8);
        OrdersStore store = new OrdersStore(dir, LOGGER);
        store.load();
        assertEquals(1, store.size(), "битые строки пропускаются");
        assertNull(store.order("4").materialOrNull());
        assertNull(store.order(""));
        assertNull(store.order("6"));
        assertEquals("5", store.reserveId(), "номер учитывает уже сохранённые заказы");
        assertEquals(0, store.players());
    }

    @Test
    void legacyPassDaysKeyIsReadAsHours(@TempDir Path dir) throws Exception {
        // Доска, сохранённая до 1.4.184: ключ pass-days. Значение читается как часы,
        // чтобы ожидающие заказы не потеряли награду при обновлении.
        String json = """
                {
                  "version": 1,
                  "next-id": 3,
                  "orders": [
                    {
                      "id": "2",
                      "bot": "grom",
                      "bot-name": "Кузнец Гром",
                      "category": "ores",
                      "material": "COAL",
                      "amount": 10,
                      "reward": 3,
                      "pass-days": 2,
                      "min-reputation": 0,
                      "reputation": 1,
                      "created": 1,
                      "expires": 2
                    }
                  ],
                  "players": {}
                }
                """;
        Files.writeString(dir.resolve("orders.json"), json, StandardCharsets.UTF_8);
        OrdersStore store = new OrdersStore(dir, LOGGER);
        store.load();
        assertEquals(2, store.order("2").rewardPassHours(),
                "старый ключ читается как часы, а не как сутки");
    }

    @Test
    void topSortsByCompletedThenReputation(@TempDir Path dir) {
        OrdersStore store = new OrdersStore(dir, LOGGER);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        long now = 10_000L;
        for (int roll = 0; roll < 3; roll++) {
            store.recordDelivery(first, "Аня", now, 1);
            store.recordDelivery(second, "Борис", now, 2);
        }
        store.recordDelivery(third, "Вера", now, 5);

        List<OrdersStore.Row> rows = store.top(2);
        assertEquals(2, rows.size());
        assertEquals("Борис", rows.get(0).name(), "при равенстве сдач выше репутация");
        assertEquals("Аня", rows.get(1).name());
        assertEquals(3, rows.get(0).completed());
        assertEquals(6, rows.get(0).reputation());
        assertEquals(0, store.top(0).size(), "нулевой лимит — пустой топ");
        assertEquals(0, store.top(-5).size(), "отрицательный лимит не ломает выборку");
        assertEquals(3, store.top(10).size());
    }

    @Test
    void namesAreUpdatedAndClamped(@TempDir Path dir) {
        OrdersStore store = new OrdersStore(dir, LOGGER);
        UUID id = UUID.randomUUID();
        store.recordDelivery(id, "Old", 1_000L, -5);
        assertEquals(0, store.reputation(id), "отрицательная репутация не начисляется");
        assertEquals("Old", store.player(id).name());
        store.recordDelivery(id, "New", 2_000L, 1);
        assertEquals("New", store.player(id).name());
        assertEquals(2, store.completed(id));
        assertEquals(1, store.reputation(id));
        store.setReputation(id, -100);
        assertEquals(0, store.reputation(id));
        assertEquals("New", store.byName("new").name());
        assertNull(store.byName(""));
        assertNull(store.byName("Нет такого"));
    }

    @Test
    void dayMathUsesUtcBoundaries() {
        assertEquals(0L, OrdersStore.dayOf(0L));
        assertEquals(1L, OrdersStore.dayOf(DAY));
        assertEquals(1L, OrdersStore.dayOf(DAY + 5L));
        assertEquals(DAY, OrdersStore.millisToNextDay(0L));
        assertEquals(1L, OrdersStore.millisToNextDay(DAY - 1L));
        assertEquals(DAY, OrdersStore.millisToNextDay(-50L));
    }

    @Test
    void saveNowWritesOnlyWhenDirty(@TempDir Path dir) {
        OrdersStore store = new OrdersStore(dir, LOGGER);
        assertFalse(Files.exists(store.file()));
        store.saveNow();
        assertFalse(Files.exists(store.file()), "без изменений файл не создаётся");
        store.add(order("1", 1L, 2L));
        assertTrue(store.isDirty());
        store.saveNow();
        assertTrue(Files.exists(store.file()));
        assertFalse(store.isDirty());
    }
}
