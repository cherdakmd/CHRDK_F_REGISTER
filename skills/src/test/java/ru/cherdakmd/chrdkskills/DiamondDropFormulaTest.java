package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Баланс физического дропа алмазов: уровень, престиж и платный ранг влияют на
 * шанс и количество, но потолки контракта не пробиваются кривым конфигом.
 *
 * <p>Тест без Bukkit и без сети: бросок приходит числом {@code dice}.</p>
 */
class DiamondDropFormulaTest {

    private static final DiamondDrops.Settings S = DiamondDrops.Settings.DEFAULT;

    private static DiamondDrops.Result hit(int level, int prestige, int rank,
                                           int xpPercent, int dropPercent) {
        return DiamondDrops.roll(level, 100, prestige, rank, xpPercent, dropPercent,
                0L, false, 0.0, S);
    }

    @Test
    void chanceGrowsWithLevelRankAndPrestige() {
        double novice = hit(1, 0, 0, 0, 0).chance();
        double master = hit(100, 0, 0, 0, 0).chance();
        double ranked = hit(100, 0, 3, 30, 15).chance();
        double prestige = hit(100, 3, 3, 30, 15).chance();

        assertEquals(0.01, novice, 1e-9);
        assertEquals(0.05, master, 1e-9);
        assertTrue(ranked > master, "ранг должен повышать шанс");
        assertTrue(prestige > ranked, "престиж должен повышать шанс");
        assertTrue(prestige <= S.maxChance() + 1e-9);
    }

    @Test
    void rankAffectsBothChanceAndAmount() {
        DiamondDrops.Result free = hit(100, 0, 0, 0, 0);
        DiamondDrops.Result paid = hit(100, 0, 5, 50, 25);
        assertTrue(paid.chance() > free.chance());
        assertTrue(paid.amount() > free.amount(),
                "старший ранг должен давать больше алмазов");
        assertEquals(S.maxAmount(), paid.amount(), "количество ограничено потолком");
    }

    @Test
    void levelIncreasesAmountWithinCap() {
        assertEquals(1, hit(1, 0, 0, 0, 0).amount());
        assertTrue(hit(50, 0, 0, 0, 0).amount() >= 2);
        assertTrue(hit(100, 0, 0, 0, 0).amount() >= 3);
        assertTrue(hit(100, 0, 0, 0, 0).amount() <= S.maxAmount());
    }

    @Test
    void mobsAreRarerButRicher() {
        DiamondDrops.Result block = DiamondDrops.roll(100, 100, 0, 0, 0, 0,
                0L, false, 0.0, S);
        DiamondDrops.Result mob = DiamondDrops.roll(100, 100, 0, 0, 0, 0,
                0L, true, 0.0, S);
        assertTrue(mob.chance() > block.chance());
        assertTrue(mob.amount() >= block.amount());
    }

    @Test
    void dailyCapTruncatesAndThenStops() {
        long almost = S.dailyCap() - 1;
        DiamondDrops.Result last = DiamondDrops.roll(100, 100, 0, 5, 50, 25,
                almost, false, 0.0, S);
        assertEquals(1, last.amount(), "выдаём только остаток дневного бюджета");

        DiamondDrops.Result exhausted = DiamondDrops.roll(100, 100, 0, 5, 50, 25,
                S.dailyCap(), false, 0.0, S);
        assertFalse(exhausted.dropped());
        assertEquals("daily-cap", exhausted.reason());
    }

    @Test
    void missAndDisabledAreExplicit() {
        DiamondDrops.Result miss = DiamondDrops.roll(100, 100, 0, 5, 50, 25,
                0L, false, 0.999, S);
        assertFalse(miss.dropped());
        assertEquals("miss", miss.reason());

        DiamondDrops.Settings off = new DiamondDrops.Settings(
                false, 0.01, 0.04, 0.01, 0.03, 0.01, 0.20,
                1, 2, 1, 4, 64, 1.5, 1);
        assertFalse(DiamondDrops.roll(100, 100, 0, 0, 0, 0, 0L, false, 0.0, off).dropped());
        assertEquals("disabled",
                DiamondDrops.roll(100, 100, 0, 0, 0, 0, 0L, false, 0.0, off).reason());
    }

    @Test
    void configCannotBreakContractCaps() {
        DiamondDrops.Settings wild = new DiamondDrops.Settings(
                true, 99.0, 99.0, 99.0, 99.0, 99.0, 99.0,
                999, 999, 999, 999, 99999, 99.0, 999);
        assertEquals(DiamondDrops.MAX_CHANCE_LIMIT, wild.maxChance(), 1e-9);
        assertEquals(DiamondDrops.MAX_AMOUNT_LIMIT, wild.maxAmount());
        assertEquals(DiamondDrops.MAX_DAILY_CAP, wild.dailyCap());
        assertEquals(1.0, wild.baseChance(), 1e-9, "базовый шанс всё равно ≤ 1");

        DiamondDrops.Result result = DiamondDrops.roll(100, 100, 99, 5, 50, 25,
                0L, true, 0.0, wild);
        assertTrue(result.chance() <= DiamondDrops.MAX_CHANCE_LIMIT + 1e-9);
        assertTrue(result.amount() <= DiamondDrops.MAX_AMOUNT_LIMIT);
    }

    @Test
    void levelRatioIsBounded() {
        assertEquals(0.0, DiamondDrops.levelRatio(1, 100), 1e-9);
        assertEquals(1.0, DiamondDrops.levelRatio(100, 100), 1e-9);
        assertEquals(1.0, DiamondDrops.levelRatio(500, 100), 1e-9);
        assertEquals(0.0, DiamondDrops.levelRatio(-5, 100), 1e-9);
        assertTrue(DiamondDrops.levelRatio(50, 100) > 0.4);
        assertTrue(DiamondDrops.levelRatio(50, 100) < 0.6);
    }
}
