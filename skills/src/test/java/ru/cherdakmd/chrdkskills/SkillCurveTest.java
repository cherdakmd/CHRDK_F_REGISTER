package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Кривая опыта: ступени тиров, стоимость уровней, границы и полоса прогресса.
 *
 * <p>Все ожидания — из формулы {@code (100 + 25 × (уровень − 1)) × (1 + 0,5 ×
 * тир)}, тир — каждые 10 уровней. Путь до 100-го уровня — 523 662 опыта;
 * {@code tierStep = 0} возвращает прежнюю линейную кривую (131 175). Тест
 * страхует и саму формулу, и то, что границы уровней не «съедают» игрока на
 * ровном счёте.</p>
 */
class SkillCurveTest {

    private static final SkillCurve.Params DEFAULT = SkillCurve.Params.DEFAULT;

    @Test
    void costGrowsByTierStairs() {
        assertEquals(100, SkillCurve.xpForNext(1, DEFAULT));
        assertEquals(125, SkillCurve.xpForNext(2, DEFAULT));
        assertEquals(325, SkillCurve.xpForNext(10, DEFAULT), "тир 0 — без надбавки");
        assertEquals(525, SkillCurve.xpForNext(11, DEFAULT), "скачок на 11-м: второй тир ×1.5");
        assertEquals(1_200, SkillCurve.xpForNext(21, DEFAULT), "третий тир ×2");
        assertEquals(14_163, SkillCurve.xpForNext(100, DEFAULT), "десятый тир ×5.5");
        assertEquals(100, SkillCurve.xpForNext(0, DEFAULT), "нулевой уровень считается первым");
    }

    @Test
    void tierOfCountsTenLevelBlocks() {
        assertEquals(0, SkillCurve.tierOf(1));
        assertEquals(0, SkillCurve.tierOf(10));
        assertEquals(1, SkillCurve.tierOf(11));
        assertEquals(9, SkillCurve.tierOf(100));
        assertEquals(0, SkillCurve.tierOf(0), "нулевой уровень живёт в первом тире");
    }

    @Test
    void tierStepZeroKeepsOldLinearCurve() {
        SkillCurve.Params linear = new SkillCurve.Params(100, 100, 25.0, 0.0);
        assertEquals(100, SkillCurve.xpForNext(1, linear));
        assertEquals(2_550, SkillCurve.xpForNext(99, linear));
        assertEquals(2_575, SkillCurve.xpForNext(100, linear));
        assertEquals(131_175, SkillCurve.totalForLevel(100, linear), "прежний путь 1..100");
    }

    @Test
    void totalForLevelIsSumOfPreviousCosts() {
        assertEquals(0, SkillCurve.totalForLevel(1, DEFAULT));
        assertEquals(100, SkillCurve.totalForLevel(2, DEFAULT));
        assertEquals(225, SkillCurve.totalForLevel(3, DEFAULT));
        assertEquals(523_662, SkillCurve.totalForLevel(100, DEFAULT));
        assertEquals(1_644_105, SkillCurve.totalForLevel(150, DEFAULT),
                "путь до 150-го уровня при потолке 150");
    }

    @Test
    void levelForHandlesEdges() {
        assertEquals(1, SkillCurve.levelFor(0, DEFAULT));
        assertEquals(1, SkillCurve.levelFor(-50, DEFAULT));
        assertEquals(1, SkillCurve.levelFor(99, DEFAULT));
        assertEquals(2, SkillCurve.levelFor(100, DEFAULT));
        assertEquals(2, SkillCurve.levelFor(224, DEFAULT));
        assertEquals(3, SkillCurve.levelFor(225, DEFAULT));
        assertEquals(60, SkillCurve.levelFor(131_175, DEFAULT), "прежний полный путь — теперь 60-й уровень");
        assertEquals(100, SkillCurve.levelFor(523_662, DEFAULT));
        assertEquals(150, SkillCurve.levelFor(999_999_999, DEFAULT), "потолок не пробивается");
    }

    @Test
    void progressShowsXpInsideLevel() {
        SkillCurve.LevelProgress progress = SkillCurve.progress(150, DEFAULT);
        assertEquals(2, progress.level());
        assertEquals(50, progress.into());
        assertEquals(125, progress.need());
        assertEquals(40, progress.percent());

        SkillCurve.LevelProgress mid = SkillCurve.progress(523_662, DEFAULT);
        assertEquals(100, mid.level());
        assertFalse(mid.maxed(), "100-й уровень — не потолок при max-level 150");

        SkillCurve.LevelProgress max = SkillCurve.progress(1_644_105, DEFAULT);
        assertEquals(150, max.level());
        assertEquals(100, max.percent(), "на максимуме полоса полная");
        assertEquals(0, max.need(), "после потолка опыт не нужен");
        assertTrue(max.maxed(), "МАКС вместо «305332/0 (100%)»");
        assertFalse(progress.maxed(), "на 2-м уровне опыт до следующего ещё нужен");

        assertEquals(0, SkillCurve.progress(0, DEFAULT).percent());
    }

    @Test
    void missingForLevelNeverNegative() {
        assertEquals(100, SkillCurve.missingForLevel(0, 2, DEFAULT));
        assertEquals(0, SkillCurve.missingForLevel(1_000, 2, DEFAULT));
        assertEquals(523_662, SkillCurve.missingForLevel(0, 100, DEFAULT));
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
        SkillCurve.Params tiny = new SkillCurve.Params(0, 0, -5.0, -1.0);
        assertEquals(2, tiny.maxLevel(), "минимум два уровня");
        assertEquals(1, tiny.baseXp());
        assertEquals(0.0, tiny.growth());
        assertEquals(0.0, tiny.tierStep(), "отрицательная ступень прижимается к нулю");

        SkillCurve.Params huge = new SkillCurve.Params(10_000, 100, 25, 99.0);
        assertEquals(500, huge.maxLevel(), "максимум 500 уровней");
        assertEquals(10.0, huge.tierStep(), "потолок ступени — 10");
    }

    @Test
    void fullPathTableMatchesLevelFormula() {
        long[] table = SkillCurve.table(DEFAULT);
        assertEquals(150, table.length, "таблица накоплений — на все уровни до потолка");
        assertEquals(0, table[0]);
        assertEquals(SkillCurve.totalForLevel(50, DEFAULT), table[49]);
    }
}
