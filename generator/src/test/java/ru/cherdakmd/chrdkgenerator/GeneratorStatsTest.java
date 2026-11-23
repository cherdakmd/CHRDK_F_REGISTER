package ru.cherdakmd.chrdkgenerator;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Значения меню и плейсхолдеров: тир, заполненность, цикл. */
class GeneratorStatsTest {

    private static GeneratorConfig config() {
        InputStream in = GeneratorStatsTest.class.getResourceAsStream("/config.yml");
        assertNotNull(in, "config.yml упакован в модуль");
        return GeneratorConfig.load(YamlConfiguration.loadConfiguration(
                new InputStreamReader(in, StandardCharsets.UTF_8)));
    }

    private static GeneratorStore.Entry entry(int tier, Map<String, Integer> modules, long bunker) {
        return new GeneratorStore.Entry(java.util.UUID.randomUUID(), 0, java.util.UUID.randomUUID(),
                "world", 0, 64, 0, tier, modules, bunker, 0L, 0, 0L, 0L, 0L);
    }

    @Test void tierAndCycleFollowModules() {
        GeneratorConfig config = config();
        assertEquals(0, GeneratorStats.tier(null));
        assertEquals(3, GeneratorStats.tier(entry(3, Map.of(), 0)));
        assertEquals(30, GeneratorStats.cycleSeconds(entry(1, Map.of(), 0), config));
        assertEquals(18, GeneratorStats.cycleSeconds(entry(1, Map.of("speed", 5), 0), config),
                "скорость 5: 30×(1−0.40) = 18 с");
    }

    @Test void fillPercentUsesCapacityTimesOfflineHours() {
        GeneratorConfig config = config();
        assertEquals(0, GeneratorStats.fillPercent(null, config));
        // Потолок базы 256 × 8 ч = 2048; половина — 50%.
        GeneratorStore.Entry half = entry(1, Map.of(), 1_024);
        assertEquals(50, GeneratorStats.fillPercent(half, config));
        assertEquals(2_048L, GeneratorStats.capacityUnits(half, config));
        // Ёмкость 5: 256×2.25 = 576, ×8 = 4608.
        GeneratorStore.Entry big = entry(1, Map.of("capacity", 5), 4_608);
        assertEquals(100, GeneratorStats.fillPercent(big, config));
        assertEquals(4_608L, GeneratorStats.capacityUnits(big, config));
        GeneratorStore.Entry over = entry(1, Map.of(), 9_999);
        assertEquals(100, GeneratorStats.fillPercent(over, config), "переполнение зажимается в 100");
    }

    private static GeneratorStore.Entry moneyEntry(int tier, long money) {
        return new GeneratorStore.Entry(java.util.UUID.randomUUID(), 0, java.util.UUID.randomUUID(),
                "world", 0, 64, 0, tier, Map.of(), 0L, 0L, 0, 0L, money, 0L);
    }

    @Test void tierProgressFollowsPiggyBankAgainstStepPrice() {
        GeneratorConfig config = config();
        assertEquals(0, GeneratorStats.tierProgressPercent(null, config));
        assertEquals(100, GeneratorStats.tierProgressPercent(
                moneyEntry(GeneratorConfig.TIERS, 0), config), "на максимуме прогресс полный");
        // Шаг 3→4 стоит 1250; половина копилки — 50%.
        assertEquals(50, GeneratorStats.tierProgressPercent(moneyEntry(3, 625), config));
    }

    @Test void etaCountsTimeUntilBunkerFull() {
        GeneratorConfig config = config();
        assertEquals(-1L, GeneratorStats.etaSeconds(null, config));
        assertEquals(-1L, GeneratorStats.etaSeconds(entry(1, Map.of(), 2_048), config),
                "полный бункер — ETA нет");
        // Тир 1: 1 ед. за 30 с; осталась 1 единица (2047 из 2048) — 30 с.
        GeneratorStore.Entry oneLeft = entry(1, Map.of(), 2_047);
        assertEquals(30L, GeneratorStats.etaSeconds(oneLeft, config));
        assertEquals("1м", GeneratorStats.etaText(oneLeft, config), "30 с округляются до минуты");
        // 2048 ед. × 30 с = 61440 с = 17ч 04м.
        assertEquals("17ч 04м", GeneratorStats.etaText(entry(1, Map.of(), 0), config));
    }
}
