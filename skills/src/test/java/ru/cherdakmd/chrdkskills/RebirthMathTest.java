package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Математика перерождений (п.30): готовность, множитель, вехи, конвертация. */
class RebirthMathTest {

    @Test
    void readyRequiresAllTenSkillsAtCap() {
        List<Integer> tenAtCap = Collections.nCopies(RebirthMath.SKILLS, 100);
        assertTrue(RebirthMath.ready(tenAtCap, 100));
        assertFalse(RebirthMath.ready(tenAtCap, 120), "потолок задаётся кривой");
        List<Integer> oneShort = new java.util.ArrayList<>(tenAtCap);
        oneShort.set(3, 99);
        assertFalse(RebirthMath.ready(oneShort, 100),
                "даже один недокачанный навык закрывает перерождение");
        assertFalse(RebirthMath.ready(List.of(100), 100), "нужны все десять");
    }

    @Test
    void xpBonusIsDiminishingAndCapped() {
        assertEquals(0.0, RebirthMath.xpBonusPercent(0, 5.0, 0.9, 50.0), 1e-9);
        assertEquals(5.0, RebirthMath.xpBonusPercent(1, 5.0, 0.9, 50.0), 1e-9);
        // 5 + 4.5 + 4.05 + ... ≈ 32.57 % на десятом перерождении.
        assertEquals(32.566, RebirthMath.xpBonusPercent(10, 5.0, 0.9, 50.0), 0.001);
        assertTrue(RebirthMath.xpBonusPercent(1000, 5.0, 0.9, 50.0) <= 50.0 + 1e-9,
                "потолок +50 % не пробивается");
        assertEquals(1.325, RebirthMath.xpFactor(10, 5.0, 0.9, 50.0), 0.001);
    }

    @Test
    void rankClampsToTenAndUnlockIsFirstRebirth() {
        assertEquals(1, RebirthMath.rank(0));
        assertEquals(1, RebirthMath.rank(1));
        assertEquals(10, RebirthMath.rank(10));
        assertEquals(10, RebirthMath.rank(77), "ранг свыше 10 зажат потолком");
        assertEquals(RebirthMath.UNLOCK_REBIRTH, SkillAbility.UNLOCK_REBIRTH,
                "порог раскрытия приёмов совпадает с первым перерождением");
    }

    @Test
    void titlesGrowWithRomanNumerals() {
        assertEquals("Перерождение I", RebirthMath.title(1));
        assertEquals("Перерождение IV", RebirthMath.title(4));
        assertEquals("Перерождение X", RebirthMath.title(10));
        assertEquals("Перерождение 11", RebirthMath.title(11));
    }

    @Test
    void milestonesAreFiveTenTwentyFiftyThenEveryTwentyFive() {
        assertTrue(RebirthMath.milestone(5));
        assertTrue(RebirthMath.milestone(10));
        assertFalse(RebirthMath.milestone(15));
        assertTrue(RebirthMath.milestone(25));
        assertTrue(RebirthMath.milestone(50));
        assertFalse(RebirthMath.milestone(51));
        assertTrue(RebirthMath.milestone(75));
        assertTrue(RebirthMath.milestone(100));
        assertEquals(List.of(5, 10, 25, 50), RebirthMath.milestoneThresholds());
    }

    @Test
    void diamondRewardsAreClamped() {
        assertEquals(32, RebirthMath.rebirthDiamonds(32));
        assertEquals(256, RebirthMath.rebirthDiamonds(9999), "потолок награды за сброс");
        assertEquals(128, RebirthMath.milestoneDiamonds(128));
        assertEquals(2048, RebirthMath.milestoneDiamonds(9999));
    }

    @Test
    void legacyStepsConvertTenToOne() {
        assertEquals(0, RebirthMath.fromLegacySteps(0));
        assertEquals(0, RebirthMath.fromLegacySteps(9));
        assertEquals(1, RebirthMath.fromLegacySteps(10));
        assertEquals(10, RebirthMath.fromLegacySteps(100), "ветеран 100 ступеней — 10 перерождений");
        assertEquals(10, RebirthMath.fromLegacySteps(109), "хвост ступеней не даёт лишнего");
    }
}
