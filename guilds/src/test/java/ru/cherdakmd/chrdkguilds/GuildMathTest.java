package ru.cherdakmd.chrdkguilds;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Расчёты гильдий (1.4.163): цена уровня, потолок состава, окно защиты и тексты.
 *
 * <p>Числа из меню и из проверки — одни и те же: если цена уровня «поедет», это
 * увидят все сразу, поэтому расчёт живёт в одном месте и проверяется здесь.</p>
 */
class GuildMathTest {

    @Test
    void upgradeCostGrowsAndStops() {
        assertEquals(64, GuildMath.upgradeCost(1));
        assertEquals(192, GuildMath.upgradeCost(2));
        assertEquals(384, GuildMath.upgradeCost(3));
        assertEquals(768, GuildMath.upgradeCost(4));
        assertEquals(0, GuildMath.upgradeCost(5), "с пятого уровня расти некуда");
        assertEquals(64, GuildMath.upgradeCost(-3), "мусорный уровень зажимается");
        assertEquals(0, GuildMath.upgradeCost(99));
    }

    @Test
    void memberCapMatchesLevels() {
        assertEquals(10, GuildMath.memberCap(1));
        assertEquals(14, GuildMath.memberCap(2));
        assertEquals(18, GuildMath.memberCap(3));
        assertEquals(22, GuildMath.memberCap(4));
        assertEquals(GuildMath.MAX_MEMBERS, GuildMath.memberCap(5));
        assertEquals(10, GuildMath.memberCap(0));
        assertEquals(GuildMath.MAX_MEMBERS, GuildMath.memberCap(9));
    }

    @Test
    void upgradeIsAvailableBelowMax() {
        assertTrue(GuildMath.canUpgrade(1));
        assertTrue(GuildMath.canUpgrade(4));
        assertFalse(GuildMath.canUpgrade(5));
        assertFalse(GuildMath.canUpgrade(6));
    }

    @Test
    void killPointsDependOnClaim() {
        assertEquals(1, GuildMath.killPoints(false));
        assertEquals(2, GuildMath.killPoints(true));
    }

    @Test
    void killWindowBlocksRepeats() {
        assertTrue(GuildMath.countsKill(0L, 1_000L, 300_000L), "первое убийство всегда идёт");
        assertFalse(GuildMath.countsKill(1_000L, 100_000L, 300_000L));
        assertTrue(GuildMath.countsKill(1_000L, 301_000L, 300_000L));
        assertTrue(GuildMath.countsKill(1_000L, 1_000L, 0L), "нулевое окно отключает защиту");
    }

    @Test
    void romansAndClockAndDiamonds() {
        assertEquals("I", GuildMath.roman(1));
        assertEquals("V", GuildMath.roman(5));
        assertEquals("V", GuildMath.roman(50));
        assertEquals("1 мин", GuildMath.clock(60));
        assertEquals("1 ч 5 мин", GuildMath.clock(3_900));
        assertEquals("12 с", GuildMath.clock(12));
        assertEquals("0 с", GuildMath.clock(-3));
        assertEquals("0", GuildMath.diamonds(0));
        assertEquals("999", GuildMath.diamonds(999));
        assertEquals("1 024", GuildMath.diamonds(1_024));
        assertEquals("1 000 000", GuildMath.diamonds(1_000_000));
        assertEquals("0", GuildMath.diamonds(-50));
    }

    @Test
    void keysAreCaseInsensitive() {
        assertEquals("орден", GuildMath.key("  Орден "));
        assertEquals("", GuildMath.key(null));
        assertEquals("AB", GuildMath.tag(" ab "));
    }
}
