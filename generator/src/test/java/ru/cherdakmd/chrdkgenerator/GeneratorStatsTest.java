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
}
