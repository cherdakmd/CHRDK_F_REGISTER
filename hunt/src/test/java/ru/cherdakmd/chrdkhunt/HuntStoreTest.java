package ru.cherdakmd.chrdkhunt;

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
 * Кошелёк охоты (1.4.155): начисления и потолки, суточные счётчики по UTC,
 * покупки, рейтинг, персистентность и устойчивость к битому JSON.
 */
class HuntStoreTest {

    private static final Logger LOGGER = Logger.getLogger("HuntStoreTest");

    @Test
    void bestiaryKeepsCountsRewardsAndSurvivesReload(@TempDir Path dir) {
        HuntStore store = new HuntStore(dir, LOGGER);
        UUID id = UUID.randomUUID();
        assertEquals(0L, store.recordBestiaryKill(id, "Аня", ""));
        assertEquals(1L, store.recordBestiaryKill(id, "Аня", "ZOMBIE"));
        assertEquals(2L, store.recordBestiaryKill(id, "Аня", "zombie"));
        assertEquals(1L, store.recordBestiaryKill(id, "Аня", "skeleton"));
        assertEquals(2L, store.bestiaryKills(id, "ZOMBIE"));
        assertEquals(0L, store.bestiaryKills(id, "creeper"));
        assertEquals(2, store.bestiarySeen(id));
        assertEquals(2, store.bestiaryMap(id).size());
        assertEquals(0L, store.bestiaryKills(UUID.randomUUID(), "zombie"));
        assertEquals(0, store.bestiarySeen(UUID.randomUUID()));

        assertFalse(store.hasBestiaryAward(id, "undead"));
        assertTrue(store.grantBestiaryAward(id, "Аня", "UNDEAD"));
        assertTrue(store.hasBestiaryAward(id, "undead"));
        assertFalse(store.grantBestiaryAward(id, "Аня", "undead"), "награда не дублируется");
        assertTrue(store.grantBestiaryAward(id, "Аня", HuntBestiary.FULL_AWARD));
        assertEquals(2, store.bestiaryAwards(id).size());
        assertFalse(store.grantBestiaryAward(id, "Аня", ""));

        store.flush();
        HuntStore reloaded = new HuntStore(dir, LOGGER);
        reloaded.load();
        assertEquals(2L, reloaded.bestiaryKills(id, "zombie"));
        assertEquals(1L, reloaded.bestiaryKills(id, "skeleton"));
        assertEquals(2, reloaded.bestiaryAwards(id).size());
        assertTrue(reloaded.hasBestiaryAward(id, HuntBestiary.FULL_AWARD));
    }

    @Test
    void bonusCoinsIgnoreTheDailyCap(@TempDir Path dir) {
        HuntStore store = new HuntStore(dir, LOGGER);
        UUID id = UUID.randomUUID();
        long now = 20L * HuntStore.DAY_MILLIS + 1_000L;
        store.touchDay(id, "Аня", now);
        store.addCoins(id, "Аня", 100L);
        assertEquals(150L, store.addBonusCoins(id, "Аня", 150L), "возврат — сколько выдано");
        assertEquals(250L, store.coins(id));
        assertEquals(250L, store.earned(id), "стаж растёт вместе с кошельком");
        assertEquals(100L, store.coinsToday(id, now),
                "награда не съедает суточный лимит охоты");
        assertEquals(0L, store.addBonusCoins(id, "Аня", -10L));
        assertEquals(0L, store.addBonusCoins(UUID.randomUUID(), "Борис", 0L));
    }

    @Test
    void emptyStoreStartsAtZero(@TempDir Path dir) {
        HuntStore store = new HuntStore(dir, LOGGER);
        store.load();
        UUID id = UUID.randomUUID();
        assertEquals(0L, store.coins(id));
        assertEquals(0L, store.earned(id));
        assertEquals(0L, store.shards(id, "undead"));
        assertEquals(0L, store.keys(id, "undead"));
        assertEquals(0L, store.killsTotal(id));
        assertEquals(0, store.trophyCount(id));
        assertEquals(0, store.players());
        assertEquals(0L, store.totalEarned());
        assertNull(store.player(id));
        assertNull(store.byName("никого"));
        assertTrue(store.top(10).isEmpty());
        assertFalse(store.isDirty());
    }

    @Test
    void coinsAreCappedAndCountForTheDay(@TempDir Path dir) {
        HuntStore store = new HuntStore(dir, LOGGER);
        UUID id = UUID.randomUUID();
        long now = 20L * HuntStore.DAY_MILLIS + 1_000L;
        store.touchDay(id, "Аня", now);
        assertEquals(100L, store.addCoins(id, "Аня", 100L));
        assertEquals(100L, store.coins(id));
        assertEquals(100L, store.earned(id));
        assertEquals(100L, store.coinsToday(id, now));
        assertEquals(100L, store.totalEarned());
        long added = store.addCoins(id, "Аня", Long.MAX_VALUE);
        assertEquals(HuntStore.MAX_COINS - 100L, added);
        assertEquals(HuntStore.MAX_COINS, store.coins(id));
        assertEquals(0L, store.addCoins(id, "Аня", -50L), "отрицательное начисление игнорируется");
    }

    @Test
    void coinsCanBeSpentOnlyWhenEnough(@TempDir Path dir) {
        HuntStore store = new HuntStore(dir, LOGGER);
        UUID id = UUID.randomUUID();
        store.addCoins(id, "Аня", 100L);
        assertTrue(store.spendCoins(id, 40L));
        assertEquals(60L, store.coins(id));
        assertFalse(store.spendCoins(id, 1_000L));
        assertEquals(60L, store.coins(id));
        assertTrue(store.spendCoins(id, 0L), "нулевая трата всегда проходит");
        assertFalse(store.spendCoins(UUID.randomUUID(), 1L), "у незнакомого игрока пусто");
    }

    @Test
    void shardsAndKeysAreTrackedPerFamily(@TempDir Path dir) {
        HuntStore store = new HuntStore(dir, LOGGER);
        UUID id = UUID.randomUUID();
        assertEquals(5L, store.addShards(id, "Аня", "UNDEAD", 5L));
        assertEquals(2L, store.addShards(id, "Аня", "nether", 2L));
        assertEquals(5L, store.shards(id, "undead"));
        assertEquals(7L, store.totalShards(id));
        assertEquals(0L, store.addShards(id, "Аня", "", 5L), "пустое семейство не пишется");
        assertTrue(store.spendShards(id, "undead", 3L));
        assertEquals(2L, store.shards(id, "undead"));
        assertFalse(store.spendShards(id, "undead", 5L));
        assertFalse(store.spendShards(id, "missing", 1L));

        assertEquals(3L, store.addKeys(id, "Аня", "undead", 3L));
        assertEquals(3L, store.keys(id, "undead"));
        assertEquals(3L, store.totalKeys(id));
        assertTrue(store.spendKey(id, "undead"));
        assertEquals(2L, store.keys(id, "undead"));
        assertTrue(store.spendKey(id, "undead"));
        assertTrue(store.spendKey(id, "undead"));
        assertFalse(store.spendKey(id, "undead"));
        assertEquals(0L, store.totalKeys(id));
    }

    @Test
    void trophiesAreCollectedOnce(@TempDir Path dir) {
        HuntStore store = new HuntStore(dir, LOGGER);
        UUID id = UUID.randomUUID();
        assertTrue(store.addTrophy(id, "Аня", "UNDEAD_SKULL"));
        assertFalse(store.addTrophy(id, "Аня", "undead_skull"));
        assertTrue(store.hasTrophy(id, "undead_skull"));
        assertFalse(store.hasTrophy(id, "missing"));
        assertEquals(1, store.trophyCount(id));
        assertFalse(store.addTrophy(id, "Аня", ""));
        assertFalse(store.hasTrophy(UUID.randomUUID(), "undead_skull"));
    }

    @Test
    void killsAreCountedPerFamilyAndTotal(@TempDir Path dir) {
        HuntStore store = new HuntStore(dir, LOGGER);
        UUID id = UUID.randomUUID();
        store.registerKill(id, "Аня", "undead", 1_000L);
        store.registerKill(id, "Аня", "UNDEAD", 2_000L);
        store.registerKill(id, "Аня", "nether", 3_000L);
        assertEquals(2L, store.kills(id, "undead"));
        assertEquals(1L, store.kills(id, "nether"));
        assertEquals(0L, store.kills(id, "missing"));
        assertEquals(3L, store.killsTotal(id));
        assertEquals(3_000L, store.player(id).lastKill());
    }

    @Test
    void dailyCountersRollOverAtUtcMidnight(@TempDir Path dir) {
        HuntStore store = new HuntStore(dir, LOGGER);
        UUID id = UUID.randomUUID();
        long now = 20L * HuntStore.DAY_MILLIS + 1_000L;
        long tomorrow = now + HuntStore.DAY_MILLIS;
        store.touchDay(id, "Аня", now);
        store.addCoins(id, "Аня", 50L);
        assertEquals(50L, store.coinsToday(id, now));
        assertEquals(0L, store.coinsToday(id, tomorrow), "новые сутки — новый счётчик");

        store.recordExchange(id, "Аня", now, 3);
        assertEquals(3L, store.exchangeToday(id, now));
        assertEquals(0L, store.exchangeToday(id, tomorrow));

        store.recordPurchase(id, "Аня", "bread", now, 2);
        assertEquals(2, store.purchasedToday(id, "bread", now));
        assertEquals(0, store.purchasedToday(id, "bread", tomorrow));
        store.recordPurchase(id, "Аня", "bread", tomorrow, 1);
        assertEquals(1, store.purchasedToday(id, "bread", tomorrow));
        assertEquals(1, store.player(id).purchaseCount("bread"));
    }

    @Test
    void topSortsByEarnedThenKills(@TempDir Path dir) {
        HuntStore store = new HuntStore(dir, LOGGER);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        store.addCoins(first, "Аня", 100L);
        store.registerKill(first, "Аня", "undead", 1L);
        store.addCoins(second, "Борис", 300L);
        store.addCoins(third, "Вера", 300L);
        for (int kill = 0; kill < 5; kill++) {
            store.registerKill(third, "Вера", "nether", 1L);
        }
        List<HuntStore.Row> rows = store.top(2);
        assertEquals(2, rows.size());
        assertEquals("Вера", rows.get(0).name(), "при равенстве монет выше число побед");
        assertEquals("Борис", rows.get(1).name());
        assertEquals(300L, rows.get(0).earned());
        assertEquals(5L, rows.get(0).kills());
        assertEquals(0, store.top(0).size());
        assertEquals(3, store.top(10).size());
    }

    @Test
    void dataSurvivesReload(@TempDir Path dir) {
        HuntStore store = new HuntStore(dir, LOGGER);
        UUID id = UUID.randomUUID();
        long now = 20L * HuntStore.DAY_MILLIS + 5_000L;
        store.touchDay(id, "Аня", now);
        store.addCoins(id, "Аня", 123L);
        store.addShards(id, "Аня", "undead", 7L);
        store.addKeys(id, "Аня", "nether", 2L);
        store.addTrophy(id, "Аня", "undead_skull");
        store.registerKill(id, "Аня", "undead", now);
        store.recordExchange(id, "Аня", now, 2);
        store.recordPurchase(id, "Аня", "bread", now, 1);
        store.flush();
        assertTrue(Files.isRegularFile(dir.resolve("hunt.json")));

        HuntStore loaded = new HuntStore(dir, LOGGER);
        loaded.load();
        assertEquals(123L, loaded.coins(id));
        assertEquals(123L, loaded.earned(id));
        assertEquals(7L, loaded.shards(id, "undead"));
        assertEquals(2L, loaded.keys(id, "nether"));
        assertTrue(loaded.hasTrophy(id, "undead_skull"));
        assertEquals(1L, loaded.kills(id, "undead"));
        assertEquals(2L, loaded.exchangeToday(id, now));
        assertEquals(1, loaded.purchasedToday(id, "bread", now));
        assertEquals("Аня", loaded.player(id).name());
        assertNotNull(loaded.byName("аня"), "поиск по нику без учёта регистра");
    }

    @Test
    void brokenRowsAreSkippedOnLoad(@TempDir Path dir) throws Exception {
        UUID good = UUID.randomUUID();
        Files.writeString(dir.resolve("hunt.json"), """
                {
                  "version": 1,
                  "players": {
                    "%s": {"name": "Аня", "coins": 10, "shards": {"undead": 2}},
                    "not-a-uuid": {"name": "плохой"},
                    "%s": "совсем не запись"
                  }
                }
                """.formatted(good, UUID.randomUUID()), StandardCharsets.UTF_8);
        HuntStore store = new HuntStore(dir, LOGGER);
        store.load();
        assertEquals(1, store.players());
        assertEquals(10L, store.coins(good));
        assertEquals(2L, store.shards(good, "undead"));
        assertNull(store.byName("плохой"));
    }

    @Test
    void resetRemovesTheProfile(@TempDir Path dir) {
        HuntStore store = new HuntStore(dir, LOGGER);
        UUID id = UUID.randomUUID();
        store.addCoins(id, "Аня", 10L);
        assertTrue(store.reset(id));
        assertEquals(0, store.players());
        assertEquals(0L, store.coins(id));
        assertFalse(store.reset(id));
        assertFalse(store.reset(null));
    }

    @Test
    void saveNowWritesOnlyWhenDirty(@TempDir Path dir) {
        HuntStore store = new HuntStore(dir, LOGGER);
        store.saveNow();
        assertFalse(Files.exists(dir.resolve("hunt.json")), "чистое хранилище не создаёт файл");
        UUID id = UUID.randomUUID();
        store.addCoins(id, "Аня", 5L);
        assertTrue(store.isDirty());
        store.saveNow();
        assertTrue(Files.isRegularFile(dir.resolve("hunt.json")));
        assertFalse(store.isDirty());
    }
}
