package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Расчёты классов (1.4.164): уровень по навыкам, очки и форматы текстов.
 *
 * <p>Уровень и очки — производные величины, поэтому здесь проверяется главное
 * свойство: они не зависят от файла и не могут «накопиться» дважды.</p>
 */
class ClassMathTest {

    @Test
    void levelGrowsWithSkillLevels() {
        assertEquals(1, ClassMath.level(0, 30));
        assertEquals(1, ClassMath.level(29, 30));
        assertEquals(2, ClassMath.level(30, 30));
        assertEquals(2, ClassMath.level(59, 30));
        assertEquals(34, ClassMath.level(1_000, 30), "десять навыков по 100 — потолок");
        assertEquals(ClassMath.MAX_LEVEL, ClassMath.level(5_000, 30), "выше потолка не растёт");
        assertEquals(1, ClassMath.level(-50, 30), "мусорный вход зажимается");
    }

    @Test
    void levelStepIsClamped() {
        // Шаг меньше пяти уровней навыков превратил бы класс в халяву.
        assertEquals(ClassMath.level(30, 5), ClassMath.level(30, 0));
        assertEquals(7, ClassMath.level(30, 5));
        assertEquals(ClassMath.level(1_000, 100), ClassMath.level(1_000, 999));
        assertEquals(11, ClassMath.level(1_000, 100));
    }

    @Test
    void pointsGrowFromLevel() {
        assertEquals(1, ClassMath.points(1, 1, 1));
        assertEquals(10, ClassMath.points(10, 1, 1));
        assertEquals(34, ClassMath.points(34, 1, 1));
        assertEquals(10 + 33 * 5, ClassMath.points(34, 10, 5));
        assertEquals(0, ClassMath.points(1, 0, 0));
    }

    @Test
    void availablePointsNeverGoNegative() {
        assertEquals(6, ClassMath.available(10, 4));
        assertEquals(0, ClassMath.available(2, 5));
        assertEquals(5, ClassMath.available(5, -3));
    }

    @Test
    void nextLevelCountsRestOfStep() {
        assertEquals(30, ClassMath.nextLevelIn(0, 30));
        assertEquals(30, ClassMath.nextLevelIn(30, 30));
        assertEquals(1, ClassMath.nextLevelIn(59, 30));
        assertEquals(0, ClassMath.nextLevelIn(1_000, 30), "потолок достигнут");
    }

    @Test
    void effectsAreClampedByTheirCaps() {
        assertEquals(10.0, ClassMath.total(EffectKind.MAX_HEALTH, 99.0));
        assertEquals(0.0, ClassMath.total(EffectKind.MELEE_DAMAGE, -1.0));
        assertEquals(0.12, ClassMath.total(EffectKind.MOVEMENT_SPEED, 0.12));
        assertEquals(0.0, ClassMath.total(null, 5.0));
    }

    @Test
    void formatsAreHumanReadable() {
        assertEquals("12,5", ClassMath.percent(0.125));
        assertEquals("25", ClassMath.percent(0.25));
        assertEquals("0,5", ClassMath.amount(EffectKind.MAX_HEALTH, 1.0));
        assertEquals("2", ClassMath.amount(EffectKind.MAX_HEALTH, 4.0));
        assertEquals("3", ClassMath.amount(EffectKind.ARMOR, 3.0));
        assertEquals("0", ClassMath.decimal(0.0));
        assertEquals("0 с", ClassMath.clock(0));
        assertEquals("30 с", ClassMath.clock(30));
        assertEquals("1 мин", ClassMath.clock(90));
        assertEquals("1 ч", ClassMath.clock(3_600));
        assertEquals("1 ч 5 мин", ClassMath.clock(3_900));
        assertEquals("1 дн. 1 ч", ClassMath.clock(90_000));
    }

    @Test
    void millisecondCooldownsAreCeiledDownToSeconds() {
        assertEquals("0 с", ClassMath.clockMillis(0L));
        assertEquals("0 с", ClassMath.clockMillis(999L), "меньше секунды — «0 с», без дробей");
        assertEquals("20 с", ClassMath.clockMillis(20_000L));
        assertEquals("1 мин", ClassMath.clockMillis(60_000L));
        assertEquals("0 с", ClassMath.clockMillis(-5_000L), "минус не должен печататься");
    }
}
