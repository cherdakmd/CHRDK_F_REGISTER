package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Атрибуты персонажа: очки за прокачку, пороги тиров, давление зоны и бонусы.
 * Проверяются границы: штраф давления и компенсация пробивания не растут
 * бесконечно, бонус против сильных работает только вверх по тирам.
 */
class AttributeMathTest {

    @Test
    void pointsComeFromLevelsAndPrestige() {
        assertEquals(0, AttributeMath.points(0, 0));
        assertEquals(10, AttributeMath.points(50, 0), "каждые 5 уровней — очко");
        assertEquals(14, AttributeMath.points(50, 2), "ступень престижа — два очка");
        assertEquals(0, AttributeMath.points(-5, -5), "отрицательное зажимается к нулю");
    }

    @Test
    void thresholdStartsAtTierSix() {
        assertEquals(0, AttributeMath.threshold(1));
        assertEquals(0, AttributeMath.threshold(5), "до тира 6 порога нет");
        assertEquals(20, AttributeMath.threshold(6));
        assertEquals(60, AttributeMath.threshold(8));
        assertEquals(100, AttributeMath.threshold(10));
    }

    @Test
    void pressurePenalizesUnderThresholdButKeepsCeiling() {
        assertEquals(0.0, AttributeMath.pressure(8, 60), 1e-9, "порог закрыт — без штрафа");
        assertEquals(0.05, AttributeMath.pressure(8, 50), 1e-9, "недобор 10 очков — шаг штрафа");
        assertEquals(0.15, AttributeMath.pressure(8, 0), 1e-9);
        assertEquals(0.25, AttributeMath.pressure(10, 0), 1e-9, "потолок давления — 25%");
    }

    @Test
    void strongFoeFactorOnlyForHigherTiers() {
        assertEquals(4, AttributeMath.attrRank(80));
        assertEquals(1.5, AttributeMath.strongFoeFactor(5, 80), 1e-9,
                "мобы выше ранга усиливают бонусы атаки");
        assertEquals(1.0, AttributeMath.strongFoeFactor(4, 80), 1e-9);
        assertEquals(1.5, AttributeMath.strongFoeFactor(2, 0), 1e-9, "без атрибутов ранг 0");
    }

    @Test
    void attackAndSupportFactorsScaleWithInvestment() {
        assertEquals(1.0, AttributeMath.meleeFactor(0, 0, 1, 0), 1e-9);
        assertEquals(1.40, AttributeMath.meleeFactor(50, 50, 1, 100), 1e-9,
                "сила и хитрость складываются без бонуса против сильных");
        assertEquals(1.0 + 0.40 * 1.5, AttributeMath.meleeFactor(50, 50, 12, 100), 1e-9,
                "против мобов выше ранга бонус усилен в 1.5 раза");
        assertEquals(1.2, AttributeMath.xpFactor(40), 1e-9, "мудрость: +0.5% опыта за очко");
        assertEquals(10.0, AttributeMath.bonusHealth(40), 1e-9, "стойкость: +0.25 здоровья за очко");
        assertEquals(0.1, AttributeMath.mitigationBonus(400), 1e-9,
                "компенсация пробивания не выше 10%");
        assertTrue(AttributeMath.attackSpeed(40) > 0.0);
        assertTrue(AttributeMath.moveSpeed(40) > 0.0);
    }
}
