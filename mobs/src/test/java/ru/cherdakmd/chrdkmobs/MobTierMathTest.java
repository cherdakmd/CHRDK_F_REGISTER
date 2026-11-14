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
}
