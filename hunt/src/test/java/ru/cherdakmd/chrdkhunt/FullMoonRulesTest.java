package ru.cherdakmd.chrdkhunt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FullMoonRulesTest {
    private FullMoonRules.Settings settings(int global, int personal, int nearby) {
        return FullMoonRules.Settings.bounded(true, 25, global, personal, nearby, 4, 2, 8, 24, 36, 5, 720, 128, 40);
    }
    @Test void calendarUsesRealVanillaFullMoonNotEveryNight() {
        assertTrue(FullMoonRules.fullMoon(0)); assertTrue(FullMoonRules.fullMoon(8));
        assertFalse(FullMoonRules.fullMoon(1)); assertFalse(FullMoonRules.fullMoon(7)); assertFalse(FullMoonRules.fullMoon(-8));
        assertEquals(8, FullMoonRules.day(8 * 24000L + 13000));
        assertEquals(13000, FullMoonRules.phase(8 * 24000L + 13000));
    }
    @Test void warningBlocksEarlySleepButOtherNightsAndDawnDoNot() {
        assertFalse(FullMoonRules.blocksSleep(11999)); assertTrue(FullMoonRules.warning(12000));
        assertTrue(FullMoonRules.blocksSleep(12542)); assertFalse(FullMoonRules.night(12999));
        assertTrue(FullMoonRules.night(13000)); assertTrue(FullMoonRules.blocksSleep(22999));
        assertFalse(FullMoonRules.blocksSleep(23000)); assertFalse(FullMoonRules.night(23000));
    }
    @Test void hostileConfigurationCannotCreateUnboundedWork() {
        var s = FullMoonRules.Settings.bounded(true, Double.NaN, Integer.MAX_VALUE, 9999, 9999, 9999, 9999, 9999, -1, 9999, -1, 9999, -1, Double.POSITIVE_INFINITY);
        assertEquals(128, s.globalCap()); assertEquals(12, s.perPlayer()); assertEquals(24, s.nearbyCap());
        assertEquals(8, s.attempts()); assertEquals(4, s.spawnBudget()); assertEquals(16, s.cleanupBudget());
        assertEquals(20, s.radiusMin()); assertEquals(64, s.radiusMax()); assertEquals(5, s.spawnEvery());
        assertEquals(900, s.maxSeconds()); assertEquals(64, s.spawnProtection()); assertEquals(40, s.msptLimit());
        assertEquals(25, s.chance());
    }
    @Test void globalPersonalAndGroupBudgetsAreIndependentAndStrict() {
        var s = settings(64, 8, 16);
        assertTrue(FullMoonRules.room(63, 7, 15, s));
        assertFalse(FullMoonRules.room(64, 0, 0, s));
        assertFalse(FullMoonRules.room(0, 8, 0, s));
        assertFalse(FullMoonRules.room(0, 0, 16, s));
        var tiny = settings(1, 12, 24);
        assertEquals(1, tiny.perPlayer()); assertEquals(1, tiny.nearbyCap());
    }
    @Test void wallClockDeadlineCannotBeExtendedByLagOrClockRollback() {
        assertFalse(FullMoonRules.expired(0, 99999999, 720));
        assertFalse(FullMoonRules.expired(1000, 720999, 720));
        assertTrue(FullMoonRules.expired(1000, 721000, 720));
        assertTrue(FullMoonRules.expired(1000, 999, 720));
    }
}
