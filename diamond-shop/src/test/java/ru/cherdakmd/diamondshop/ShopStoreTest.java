package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Хранилище магазина: балансы, запасы, статистика, round-trip через shop-data.json.
 */
class ShopStoreTest {

    private static ShopConfig.ShopItem item(String name, long ref) {
        return new ShopConfig.ShopItem(name, 1.0, ref, 0.25, 4.0);
    }

    private ShopStore newStore(Path dir) {
        return new ShopStore(dir, Logger.getAnonymousLogger());
    }

    @Test
    void balanceAddSpendAndInsufficiency(@TempDir Path dir) {
        ShopStore store = newStore(dir);
        UUID id = UUID.randomUUID();
        assertEquals(0, store.balanceOf(id), 1e-9);
        store.addBalance(id, 10.5);
        assertEquals(10.5, store.balanceOf(id), 1e-9);
        assertTrue(store.trySpend(id, 10.4));
        assertEquals(0.1, store.balanceOf(id), 1e-9);
        assertFalse(store.trySpend(id, 1), "нельзя уйти в минус");
        assertEquals(0.1, store.balanceOf(id), 1e-9);
    }

    @Test
    void stockInitializesToReferenceAndAdjusts(@TempDir Path dir) {
        ShopStore store = newStore(dir);
        ShopConfig.ShopItem stone = item("STONE", 1000);
        long now = System.currentTimeMillis();
        assertEquals(1000, store.stockOf(stone, now, 0.05));
        store.adjustStock(stone, -600, now, 0.05);
        assertEquals(400, store.stockOf(stone, now, 0.05));
        store.adjustStock(stone, 10_000, now, 0.05);
        assertEquals(10_400, store.stockOf(stone, now, 0.05));
        store.setStock(stone, 0, now);
        assertEquals(1, store.stockOf(stone, now, 0.05), "запас не опускается ниже 1");
    }

    @Test
    void stockRecoveryAppliesOverTime(@TempDir Path dir) {
        ShopStore store = newStore(dir);
        ShopConfig.ShopItem stone = item("STONE", 1000);
        long now = System.currentTimeMillis();
        store.adjustStock(stone, -400, now, 0);
        long later = now + 3_600_000L;
        long recovered = store.stockOf(stone, later, 0.5);
        assertTrue(recovered > 600, "запас подтянулся к эталону: " + recovered);
        assertTrue(recovered <= 1000);
    }

    @Test
    void tradeStatsAccumulate(@TempDir Path dir) {
        ShopStore store = newStore(dir);
        ShopConfig.ShopItem stone = item("STONE", 1000);
        store.recordTrade(stone, 10, true, 0.5);
        store.recordTrade(stone, 4, false, 0.8);
        ShopStore.ItemState state = store.snapshotOf("STONE");
        assertNotNull(state);
        assertEquals(10, state.soldByPlayers);
        assertEquals(4, state.boughtByPlayers);
        assertEquals(1.3, store.turnover(), 1e-9);
        assertNull(store.snapshotOf("GOLD_INGOT"), "нет товара — нет состояния");
    }

    @Test
    void persistsAndReloads(@TempDir Path dir) {
        ShopStore store = newStore(dir);
        UUID id = UUID.randomUUID();
        ShopConfig.ShopItem stone = item("STONE", 1000);
        long now = System.currentTimeMillis();
        store.addBalance(id, 42.25);
        store.adjustStock(stone, -300, now, 0);
        store.recordTrade(stone, 300, false, 300.0);
        store.saveNow();

        ShopStore reloaded = newStore(dir);
        reloaded.load();
        assertEquals(42.25, reloaded.balanceOf(id), 1e-9);
        assertEquals(700, reloaded.stockOf(stone, now, 0));
        ShopStore.ItemState state = reloaded.snapshotOf("STONE");
        assertEquals(300, state.boughtByPlayers);
        assertEquals(300.0, reloaded.turnover(), 1e-9);
    }

    @Test
    void priceHistoryAccumulatesWithIntervalDamping(@TempDir Path dir) {
        ShopStore store = newStore(dir);
        ShopConfig.ShopItem stone = item("STONE", 1000);
        long t0 = 1_800_000_000_000L;
        store.recordPrice(stone, 1.0, t0, 24, 3_600_000L);
        store.recordPrice(stone, 1.1, t0 + 60_000L, 24, 3_600_000L); // тот же тик → обновление
        assertEquals(1, store.historyOf("STONE").length);
        assertEquals(1.1, store.historyOf("STONE")[0], 1e-9);
        store.recordPrice(stone, 1.2, t0 + 3_600_000L, 24, 3_600_000L); // новый тик
        assertEquals(2, store.historyOf("STONE").length);
        for (int i = 0; i < 100; i++) {
            store.recordPrice(stone, 2.0 + i, t0 + 3_600_000L * (i + 2), 24, 3_600_000L);
        }
        assertEquals(24, store.historyOf("STONE").length, "история ограничена maxPoints");
    }

    @Test
    void priceHistoryPersists(@TempDir Path dir) {
        ShopStore store = newStore(dir);
        ShopConfig.ShopItem stone = item("STONE", 1000);
        long t0 = 1_800_000_000_000L;
        store.recordPrice(stone, 1.0, t0, 24, 3_600_000L);
        store.recordPrice(stone, 1.5, t0 + 3_600_000L, 24, 3_600_000L);
        store.saveNow();
        ShopStore reloaded = newStore(dir);
        reloaded.load();
        assertEquals(2, reloaded.historyOf("STONE").length);
        assertEquals(1.5, reloaded.historyOf("STONE")[1], 1e-9);
    }

    @Test
    void dailyTurnoverLimitResetsByCalendarDay(@TempDir Path dir) {
        ShopStore store = newStore(dir);
        UUID id = UUID.randomUUID();
        long day = 86_400_000L;
        long t0 = day * 21_500L + 1000; // середина суток
        assertTrue(store.tryRecordTurnover(id, 60, 100, t0));
        assertEquals(60, store.usedToday(id, t0), 1e-9);
        assertTrue(store.tryRecordTurnover(id, 40, 100, t0));
        assertFalse(store.tryRecordTurnover(id, 1, 100, t0), "сверх лимита — отказ");
        assertEquals(100, store.usedToday(id, t0), 1e-9);
        assertTrue(store.tryRecordTurnover(id, 1, 0, t0), "лимит 0 = выключен");
        // следующие сутки — окно сброшено
        assertTrue(store.tryRecordTurnover(id, 100, 100, t0 + day));
        assertEquals(100, store.usedToday(id, t0 + day), 1e-9);
    }

    @Test
    void corruptedFileDoesNotCrashLoad(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("shop-data.json");
        java.nio.file.Files.writeString(file, "{не json!!}");
        ShopStore store = newStore(dir);
        store.load(); // не должно падать
        assertEquals(0, store.balanceOf(UUID.randomUUID()), 1e-9);
    }

    @Test
    void configItemLookupIsCaseInsensitive() {
        ShopConfig.ShopConfigData data = new ShopConfig.ShopConfigData(
                Map.of("DIAMOND", 1), 0.05, 6400, 0.25, 4.0,
                List.of(item("STONE", 1000), item("IRON_INGOT", 6000)),
                10.0, 0, 24, 3_600_000L);
        assertTrue(data.item("stone").isPresent());
        assertTrue(data.item("Iron_Ingot").isPresent());
        assertTrue(data.item("bedrock").isEmpty());
    }
}
