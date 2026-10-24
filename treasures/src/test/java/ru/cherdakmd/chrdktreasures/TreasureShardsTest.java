package ru.cherdakmd.chrdktreasures;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Осколки кладов (1.4.166): каталог обменника, разбор конфига и сам бросок.
 *
 * <p>Проверяется без сервера: цены и виды предложений читаются из YAML, а
 * диапазоны осколков считаются обычным {@link Random} с фиксированным зерном.
 * Именно поэтому здесь нет ни одного Bukkit-вызова — только чистые данные.</p>
 */
class TreasureShardsTest {

    private static final Logger LOGGER = Logger.getLogger("TreasureShardsTest");

    private static YamlConfiguration shipped() {
        try (var stream = TreasureShardsTest.class.getClassLoader()
                .getResourceAsStream("config.yml")) {
            assertNotNull(stream, "config.yml не найден в ресурсах");
            try (var reader = new java.io.InputStreamReader(stream,
                    java.nio.charset.StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (Exception exception) {
            throw new AssertionError("не удалось прочитать config.yml", exception);
        }
    }

    @Test
    void shippedConfigCarriesSevenOffersAndFiveRanges() {
        TreasuresConfig config = TreasuresConfig.parse(shipped(), LOGGER);
        TreasuresConfig.Shards shards = config.shards();
        assertTrue(shards.enabled());
        assertTrue(shards.shopReady());
        assertEquals(7, shards.offers().size());
        for (TreasureTier tier : TreasureTier.values()) {
            TreasuresConfig.Shards.Range range = shards.range(tier);
            assertTrue(range.active(), "редкость без осколков: " + tier.id());
            assertTrue(range.minAmount() <= range.maxAmount(), tier.id());
        }
        assertEquals(1, shards.range(TreasureTier.COMMON).minAmount());
        assertEquals(18, shards.range(TreasureTier.MYTHICAL).maxAmount());
    }

    @Test
    void emptyConfigFallsBackToBuiltInShop() {
        TreasuresConfig config = TreasuresConfig.parse(new YamlConfiguration(), LOGGER);
        assertTrue(config.shards().enabled());
        assertTrue(config.shards().shopReady());
        assertEquals(TreasuresConfig.defaultOffers().size(), config.shards().offers().size());
        assertEquals(15, config.shards().offers().get(0).cost());
    }

    @Test
    void invalidRowsAreSkippedWithoutKillingTheShop() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("shards.shop", List.of(
                Map.of("id", "ok", "type", "named", "tier", "rare", "cost", 30),
                Map.of("id", "free", "type", "named", "tier", "rare", "cost", 0),
                Map.of("id", "broken-type", "type", "нет-такого", "cost", 10),
                Map.of("id", "bad-material", "type", "item", "material", "НЕТ_ТАКОГО", "cost", 5),
                Map.of("id", "book", "type", "enchant", "level", 9, "cost", 70),
                Map.of("id", "ok", "type", "item", "material", "AMETHYST_SHARD",
                        "amount", 500, "cost", 12)));
        TreasuresConfig config = TreasuresConfig.parse(cfg, LOGGER);
        List<TreasureShardShop.Offer> offers = config.shards().offers();
        assertEquals(3, offers.size(), "должны остаться только три годных предложения");
        assertEquals("ok", offers.get(0).id());
        assertEquals(TreasureShardShop.Kind.NAMED, offers.get(0).kind());
        assertEquals(TreasureTier.RARE, offers.get(0).tier());
        assertEquals(5, offers.get(1).level(), "уровень книги клампится до V");
        assertEquals(64, offers.get(2).amount(), "пачка клампится до 64");
        assertEquals("ok-2", offers.get(2).id(), "повторяющийся ид получает суффикс");
    }

    @Test
    void costAndAmountAreAlwaysSane() {
        TreasureShardShop.Offer offer = new TreasureShardShop.Offer(
                " ", TreasureShardShop.Kind.ITEM, -40, null, 0, "amethyst_shard", 0, null);
        assertEquals("offer", offer.id());
        assertEquals(1, offer.cost());
        assertEquals(1, offer.amount());
        assertEquals(TreasureTier.COMMON, offer.tier());
        assertEquals("AMETHYST_SHARD", offer.material());
        assertNotNull(offer.itemMaterial());

        TreasureShardShop.Offer capped = new TreasureShardShop.Offer(
                "big", TreasureShardShop.Kind.NAMED, 1_000_000, TreasureTier.MYTHICAL,
                9, null, 99, null);
        assertEquals(TreasureShardShop.MAX_COST, capped.cost());
        assertEquals(5, capped.level());
        assertEquals(64, capped.amount());
        assertEquals("named/mythical", capped.describe());
        assertNull(capped.itemMaterial(), "у именной находки нет материала");
    }

    @Test
    void rollRespectsChanceAndRange() {
        Map<TreasureTier, TreasuresConfig.Shards.Range> ranges =
                new EnumMap<>(TreasureTier.class);
        ranges.put(TreasureTier.COMMON, new TreasuresConfig.Shards.Range(2, 4, 100.0));
        ranges.put(TreasureTier.MYTHICAL, new TreasuresConfig.Shards.Range(12, 13, 0.0));
        TreasuresConfig.Shards shards = new TreasuresConfig.Shards(true, ranges, List.of());

        Random random = new Random(42L);
        for (int attempt = 0; attempt < 200; attempt++) {
            int common = TreasureShardRolls.roll(shards, TreasureTier.COMMON, random);
            assertTrue(common >= 2 && common <= 4, "вне диапазона: " + common);
            assertEquals(0, TreasureShardRolls.roll(shards, TreasureTier.MYTHICAL, random),
                    "нулевой шанс не даёт осколков");
        }
    }

    @Test
    void rollSkipsDisabledAndVanishesWithoutChance() {
        Map<TreasureTier, TreasuresConfig.Shards.Range> ranges =
                new EnumMap<>(TreasureTier.class);
        for (TreasureTier tier : TreasureTier.values()) {
            ranges.put(tier, new TreasuresConfig.Shards.Range(5, 5, 50.0));
        }
        Random random = new Random(7L);
        TreasuresConfig.Shards off = new TreasuresConfig.Shards(false, ranges, List.of());
        assertEquals(0, TreasureShardRolls.roll(off, TreasureTier.COMMON, random));

        TreasuresConfig.Shards on = new TreasuresConfig.Shards(true, ranges, List.of());
        assertEquals(0, TreasureShardRolls.roll(on, null, random), "без редкости нельзя");

        int hits = 0;
        for (int attempt = 0; attempt < 400; attempt++) {
            if (TreasureShardRolls.roll(on, TreasureTier.RARE, random) > 0) {
                hits++;
            }
        }
        assertTrue(hits > 120 && hits < 280, "шанс 50 % дал " + hits + " попаданий из 400");
    }

    @Test
    void rangesAreClampedAndSorted() {
        TreasuresConfig.Shards.Range broken = new TreasuresConfig.Shards.Range(500, -5, 500.0);
        assertEquals(64, broken.minAmount());
        assertEquals(64, broken.maxAmount());
        assertEquals(100.0, broken.chancePercent(), 1e-9);

        TreasuresConfig.Shards.Range inverted = new TreasuresConfig.Shards.Range(9, 3, 10.0);
        assertEquals(3, inverted.minAmount());
        assertEquals(9, inverted.maxAmount());
        assertTrue(inverted.active());

        TreasuresConfig.Shards.Range dead = new TreasuresConfig.Shards.Range(0, 0, 100.0);
        assertFalse(dead.active());
    }

    @Test
    void shopWithoutAnyOffersIsNotReady() {
        TreasuresConfig.Shards shards = new TreasuresConfig.Shards(true, Map.of(), List.of());
        assertFalse(shards.shopReady());
        assertTrue(shards.enabled());
    }
}
