package ru.cherdakmd.chrdkprofessions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Кривая уровней и потолки прибавок (1.4.165).
 *
 * <p>Уровень выводится из опыта, поэтому главное здесь — что кривая не
 * разъезжается: ровно 25 уровней, 0 опыта на потолке и проценты, которые
 * нельзя обойти настройкой.</p>
 */
class ProfessionMathTest {

    @Test
    void curveGrowsAndStopsAtMaxLevel() {
        assertEquals(120, ProfessionMath.needed(1));
        assertEquals(180, ProfessionMath.needed(2));
        assertEquals(1_500, ProfessionMath.needed(24));
        assertEquals(0, ProfessionMath.needed(25));
        assertEquals(120, ProfessionMath.needed(0), "уровень ниже первого считается первым");
        assertEquals(0, ProfessionMath.needed(99), "выше потолка опыта не нужно");

        int total = 0;
        for (int level = 1; level < ProfessionMath.MAX_LEVEL; level++) {
            total += ProfessionMath.needed(level);
        }
        assertEquals(total, ProfessionMath.totalTo(ProfessionMath.MAX_LEVEL));
        assertEquals(19_440, ProfessionMath.totalTo(25), "полный путь с 1-го по 25-й");
    }

    @Test
    void levelFollowsExperience() {
        assertEquals(1, ProfessionMath.levelFor(0));
        assertEquals(1, ProfessionMath.levelFor(119));
        assertEquals(2, ProfessionMath.levelFor(120));
        assertEquals(2, ProfessionMath.levelFor(299));
        assertEquals(3, ProfessionMath.levelFor(300));
        assertEquals(25, ProfessionMath.levelFor(ProfessionMath.totalTo(25)));
        assertEquals(25, ProfessionMath.levelFor(1_000_000), "выше потолка не растёт");

        assertEquals(30, ProfessionMath.progress(150));
        assertEquals(150, ProfessionMath.xpToNext(150), "30 из 180 — значит до уровня 150");
        assertEquals(0, ProfessionMath.xpToNext(ProfessionMath.totalTo(25)));
        assertEquals(50, ProfessionMath.progressPercent(60));
        assertEquals(0, ProfessionMath.progressPercent(0));
        assertEquals(100, ProfessionMath.progressPercent(ProfessionMath.totalTo(25)));
    }

    @Test
    void rankRaisesLevelCapAndPerkCap() {
        // Ранг V даёт +10 уровней: 25 → 35, и прибавки продолжают расти (1.4.167).
        int cap = ProfessionMath.maxLevel(10);
        assertEquals(35, cap);
        assertEquals(1_560, ProfessionMath.needed(25, cap), "кривая продолжается тем же шагом");
        assertEquals(0, ProfessionMath.needed(35, cap), "на своём потолке опыта больше не нужно");
        assertEquals(35, ProfessionMath.levelFor(1_000_000, cap), "потолок ранга работает");
        assertEquals(25, ProfessionMath.levelFor(1_000_000), "без ранга потолок прежний");

        assertEquals(40, ProfessionMath.smithDiscount(20, cap));
        assertEquals(60, ProfessionMath.smithDiscount(35, cap), "скидка растёт до 40 + 10×2");
        assertEquals(30, ProfessionMath.alchemistChance(30, cap), "20 + 10 уровней");
        assertEquals(3, ProfessionMath.cookSaturation(30, cap), "+3 насыщения на 30-м уровне");
        assertEquals(25, ProfessionMath.masonChance(30, cap), "15 + 10 уровней");
    }

    @Test
    void perkCeilingsHold() {
        assertEquals(2, ProfessionMath.smithDiscount(1));
        assertEquals(40, ProfessionMath.smithDiscount(20));
        assertEquals(40, ProfessionMath.smithDiscount(25), "скидка не растёт выше 40 %");

        assertEquals(1, ProfessionMath.alchemistChance(1));
        assertEquals(20, ProfessionMath.alchemistChance(25));

        assertEquals(0, ProfessionMath.cookSaturation(1), "до 5-го уровня прибавки нет");
        assertEquals(0, ProfessionMath.cookSaturation(4));
        assertEquals(1, ProfessionMath.cookSaturation(5), "1.4.178: первый шаг с 5-го уровня");
        assertEquals(1, ProfessionMath.cookSaturation(14));
        assertEquals(2, ProfessionMath.cookSaturation(15));
        assertEquals(2, ProfessionMath.cookSaturation(25));

        assertEquals(15, ProfessionMath.masonChance(15));
        assertEquals(15, ProfessionMath.masonChance(25));

        assertEquals(0, ProfessionMath.perkValue(null, 10));
        assertEquals(8, ProfessionMath.perkValue(Profession.SMITH, 4));
    }

    @Test
    void ranksChangeOnThresholds() {
        assertEquals("rank.novice", ProfessionMath.rankKey(1));
        assertEquals("rank.novice", ProfessionMath.rankKey(4));
        assertEquals("rank.journeyman", ProfessionMath.rankKey(5));
        assertEquals("rank.master", ProfessionMath.rankKey(10));
        assertEquals("rank.magister", ProfessionMath.rankKey(20));
        assertEquals("rank.magister", ProfessionMath.rankKey(25));

        assertEquals(5, ProfessionMath.nextRankAt(1));
        assertEquals(10, ProfessionMath.nextRankAt(5));
        assertEquals(20, ProfessionMath.nextRankAt(10));
        assertEquals(25, ProfessionMath.nextRankAt(20));
        assertEquals(25, ProfessionMath.nextRankAt(25));
    }

    @Test
    void clockReadsAsRussianTime() {
        assertEquals("0 с", ProfessionMath.clock(0));
        assertEquals("30 с", ProfessionMath.clock(30));
        assertEquals("1 мин", ProfessionMath.clock(60));
        assertEquals("1 ч", ProfessionMath.clock(3_600));
        assertEquals("1 ч 1 мин", ProfessionMath.clock(3_660));
        assertEquals("1 дн.", ProfessionMath.clock(86_400));
        assertEquals("1 дн. 1 ч", ProfessionMath.clock(90_000));
        assertEquals("1 дн. 4 ч", ProfessionMath.clock(100_800));
        assertEquals("0 с", ProfessionMath.clock(-10));
    }

    @Test
    void levelsStayInsideBounds() {
        for (int xp = 0; xp < 20_000; xp += 37) {
            int level = ProfessionMath.levelFor(xp);
            assertTrue(level >= 1 && level <= ProfessionMath.MAX_LEVEL, "уровень " + level);
            assertTrue(ProfessionMath.progress(xp) >= 0);
            assertTrue(ProfessionMath.progressPercent(xp) >= 0
                    && ProfessionMath.progressPercent(xp) <= 100);
        }
    }
}
