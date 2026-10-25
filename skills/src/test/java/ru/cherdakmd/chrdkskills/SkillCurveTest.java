package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Кривая опыта: стоимость уровней, границы и полоса прогресса.
 *
 * <p>Все ожидания — из формулы {@code 100 + 25 × (уровень − 1)}. Путь до
 * 100-го уровня — 131 175 опыта; тест страхует и саму формулу, и то, что
 * границы уровней не «съедают» игрока на ровном счёте.</p>
 */
class SkillCurveTest {

    private static final SkillCurve.Params DEFAULT = SkillCurve.Params.DEFAULT;

    @Test
    void costGrowsLinearly() {
        assertEquals(100, SkillCurve.xpForNext(1, DEFAULT));
        assertEquals(125, SkillCurve.xpForNext(2, DEFAULT));
        assertEquals(2_575, SkillCurve.xpForNext(100, DEFAULT));
        assertEquals(100, SkillCurve.xpForNext(0, DEFAULT), "нулевой уровень считается первым");
    }

    @Test
    void totalForLevelIsSumOfPreviousCosts() {
        assertEquals(0, SkillCurve.totalForLevel(1, DEFAULT));
        assertEquals(100, SkillCurve.totalForLevel(2, DEFAULT));
        assertEquals(225, SkillCurve.totalForLevel(3, DEFAULT));
        assertEquals(131_175, SkillCurve.totalForLevel(100, DEFAULT));
    }

    @Test
    void levelForHandlesEdges() {
        assertEquals(1, SkillCurve.levelFor(0, DEFAULT));
        assertEquals(1, SkillCurve.levelFor(-50, DEFAULT));
        assertEquals(1, SkillCurve.levelFor(99, DEFAULT));
        assertEquals(2, SkillCurve.levelFor(100, DEFAULT));
        assertEquals(2, SkillCurve.levelFor(224, DEFAULT));
        assertEquals(3, SkillCurve.levelFor(225, DEFAULT));
        assertEquals(100, SkillCurve.levelFor(131_175, DEFAULT));
        assertEquals(100, SkillCurve.levelFor(999_999_999, DEFAULT), "потолок не пробивается");
    }

    @Test
    void progressShowsXpInsideLevel() {
        SkillCurve.LevelProgress progress = SkillCurve.progress(150, DEFAULT);
        assertEquals(2, progress.level());
        assertEquals(50, progress.into());
        assertEquals(125, progress.need());
        assertEquals(40, progress.percent());

        SkillCurve.LevelProgress max = SkillCurve.progress(131_175, DEFAULT);
        assertEquals(100, max.level());
        assertEquals(100, max.percent(), "на максимуме полоса полная");
        assertEquals(0, max.need(), "после 100-го уровня опыт не нужен");

        assertEquals(0, SkillCurve.progress(0, DEFAULT).percent());
    }

    @Test
    void missingForLevelNeverNegative() {
        assertEquals(100, SkillCurve.missingForLevel(0, 2, DEFAULT));
        assertEquals(0, SkillCurve.missingForLevel(1_000, 2, DEFAULT));
        assertEquals(131_175, SkillCurve.missingForLevel(0, 100, DEFAULT));
    }

    @Test
    void barHasExactWidth() {
        String bar = SkillCurve.bar(40, 10);
        assertEquals(10, bar.length());
        assertEquals("████░░░░░░", bar);
        assertEquals(10, SkillCurve.bar(0, 10).length());
        assertTrue(SkillCurve.bar(100, 10).chars().noneMatch(ch -> ch == '░'));
        assertEquals(10, SkillCurve.bar(-10, 10).length(), "отрицательный процент не ломает ширину");
    }

    @Test
    void paramsAreClamped() {
        SkillCurve.Params tiny = new SkillCurve.Params(0, 0, -5.0);
        assertEquals(2, tiny.maxLevel(), "минимум два уровня");
        assertEquals(1, tiny.baseXp());
        assertEquals(0.0, tiny.growth());

        SkillCurve.Params huge = new SkillCurve.Params(10_000, 100, 25);
        assertEquals(500, huge.maxLevel(), "максимум 500 уровней");
    }

    @Test
    void fullPathTableMatchesLevelFormula() {
        long[] table = SkillCurve.table(DEFAULT);
        assertEquals(100, table.length);
        assertEquals(0, table[0]);
        assertEquals(SkillCurve.totalForLevel(50, DEFAULT), table[49]);
    }
}
