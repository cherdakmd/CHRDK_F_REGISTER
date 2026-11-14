package ru.cherdakmd.chrdkmobs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Математика тиров мобов: границы расстояний, надбавки и награды.
 *
 * <p>Границы по умолчанию 1000/3000/7000/15000 дают тиры 1..5; надбавки
 * 0.5 здоровья и 0.25 урона за тир, награда 1 алмаз за тир выше первого.</p>
 */
class MobTierMathTest {

    private static final List<Integer> DEFAULT = List.of(1000, 3000, 7000, 15000);

    @Test
    void tierFollowsDistanceBoundaries() {
        assertEquals(1, MobTierMath.tierFor(0, DEFAULT));
        assertEquals(1, MobTierMath.tierFor(999.9, DEFAULT));
        assertEquals(2, MobTierMath.tierFor(1000, DEFAULT), "граница включительно — следующий тир");
        assertEquals(2, MobTierMath.tierFor(2999, DEFAULT));
        assertEquals(3, MobTierMath.tierFor(3000, DEFAULT));
        assertEquals(5, MobTierMath.tierFor(99_999, DEFAULT), "дальше последней границы — последний тир");
    }

    @Test
    void emptyThresholdsKeepEveryoneTierOne() {
        assertEquals(1, MobTierMath.tierFor(123_456, List.of()));
        assertEquals(1, MobTierMath.tierFor(123_456, null));
        assertEquals(1, MobTierMath.tierFor(-5, DEFAULT), "отрицательное расстояние не даёт тира");
    }

    @Test
    void factorsGrowPerTierAndClamp() {
        assertEquals(1.0, MobTierMath.healthFactor(1, 0.5));
        assertEquals(1.5, MobTierMath.healthFactor(2, 0.5));
        assertEquals(2.5, MobTierMath.healthFactor(4, 0.5));
        assertEquals(1.25, MobTierMath.damageFactor(2, 0.25));
        assertEquals(1.0, MobTierMath.damageFactor(3, -1.0), "отрицательная надбавка прижимается к нулю");
        assertEquals(31.0, MobTierMath.healthFactor(4, 99.0), "потолок надбавки — 10 за тир");
    }

    @Test
    void rewardGrowsByTierAboveFirst() {
        assertEquals(0, MobTierMath.rewardDiamonds(1, 1));
        assertEquals(1, MobTierMath.rewardDiamonds(2, 1));
        assertEquals(4, MobTierMath.rewardDiamonds(5, 1));
        assertEquals(8, MobTierMath.rewardDiamonds(5, 2), "награда масштабируется настройкой");
        assertEquals(0, MobTierMath.rewardDiamonds(2, 0), "нулевая ставка — без наград");
        assertEquals(0, MobTierMath.rewardDiamonds(2, -5), "отрицательная ставка не платит");
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

    @Test
    void eliteRewardPaysLikeHigherTiers() {
        assertEquals(2, MobTierMath.eliteRewardDiamonds(1, 1, 2), "элита тира 1 платит тоже");
        assertEquals(6, MobTierMath.eliteRewardDiamonds(5, 1, 2));
        assertEquals(4, MobTierMath.eliteRewardDiamonds(3, 2, 0), "нулевые ступени — обычная награда");
        assertEquals(0, MobTierMath.eliteRewardDiamonds(1, 0, 2), "нулевая ставка — без наград");
    }
}
