package ru.cherdakmd.chrdkmobs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Математика тиров мобов: границы расстояний и прокачка. Свойства тиров
 * (здоровье, урон, награда, лут) живут в таблице конфига {@code tiers.table}
 * и проверяются {@link MobTierConfigTest}.
 *
 * <p>Границы по умолчанию дают 10 тиров; прокачка добавляет до max-bonus
 * здоровья и урона за 100 очков прогресса.</p>
 */
class MobTierMathTest {

    private static final List<Integer> DEFAULT = List.of(500, 1200, 2200, 3500, 5200,
            7200, 9600, 12500, 16000);

    @Test
    void tierFollowsDistanceBoundaries() {
        assertEquals(1, MobTierMath.tierFor(0, DEFAULT));
        assertEquals(1, MobTierMath.tierFor(499.9, DEFAULT));
        assertEquals(2, MobTierMath.tierFor(500, DEFAULT), "граница включительно — следующий тир");
        assertEquals(3, MobTierMath.tierFor(1200, DEFAULT));
        assertEquals(10, MobTierMath.tierFor(16000, DEFAULT), "девятая граница открывает десятый тир");
        assertEquals(10, MobTierMath.tierFor(99_999, DEFAULT), "дальше последней границы — последний тир");
    }

    @Test
    void emptyThresholdsKeepEveryoneTierOne() {
        assertEquals(1, MobTierMath.tierFor(123_456, List.of()));
        assertEquals(1, MobTierMath.tierFor(123_456, null));
        assertEquals(1, MobTierMath.tierFor(-5, DEFAULT), "отрицательное расстояние не даёт тира");
    }

    @Test
    void progressMetricSumsLevelsAndWeightedPrestige() {
        assertEquals(0, MobTierMath.progressMetric(0, 0, 5));
        assertEquals(500, MobTierMath.progressMetric(500, 0, 5));
        assertEquals(750, MobTierMath.progressMetric(500, 50, 5));
        assertEquals(1500, MobTierMath.progressMetric(1000, 100, 5), "максимум прокачки");
        assertEquals(1000, MobTierMath.progressMetric(1000, 100, 0), "вес 0 — престиж не считается");
        assertEquals(0, MobTierMath.progressMetric(-5, -5, -5), "отрицательное зажимается к нулю");
    }

    @Test
    void progressFactorGrowsPerHundredAndClamps() {
        assertEquals(1.0, MobTierMath.progressFactor(0, 0.25, 1.5));
        assertEquals(1.25, MobTierMath.progressFactor(100, 0.25, 1.5));
        assertEquals(2.5, MobTierMath.progressFactor(600, 0.25, 1.5), "потолок надбавки — max-bonus");
        assertEquals(11.0, MobTierMath.progressFactor(100_000, 10.0, 99.0), "потолок надбавки — 10");
        assertEquals(1.0, MobTierMath.progressFactor(1000, -1.0, 1.5), "отрицательный шаг не работает");
    }
}
