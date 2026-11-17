package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneratorMathTest {

    @Test
    void cycleShortensWithSpeedLevelAndFloorsAtTwentyPercent() {
        assertEquals(30, GeneratorMath.cycleSeconds(30, 0));
        assertEquals(18, GeneratorMath.cycleSeconds(30, 5));
        assertEquals(6, GeneratorMath.cycleSeconds(30, 100));
        assertEquals(1, GeneratorMath.cycleSeconds(1, 5));
    }

    @Test
    void bunkerGrowsByQuarterPerCapacityLevel() {
        assertEquals(256, GeneratorMath.bunkerCapacity(256, 0));
        assertEquals(512, GeneratorMath.bunkerCapacity(256, 4));
        assertEquals(1, GeneratorMath.bunkerCapacity(0, 0));
    }

    @Test
    void payoutCapScalesWithTierAndRankStepAndClampsHard() {
        assertEquals(384, GeneratorMath.payoutDailyCap(384, 64, 0, 32, 0, 4096));
        assertEquals(800, GeneratorMath.payoutDailyCap(384, 64, 5, 32, 3, 4096));
        assertEquals(4096, GeneratorMath.payoutDailyCap(4000, 64, 10, 32, 10, 4096));
        assertEquals(1, GeneratorMath.payoutDailyCap(-5, 64, -1, 32, -1, 4096));
    }

    @Test
    void rebirthBonusDecaysLikeSkillsAndCapsAtFiftyPercent() {
        assertEquals(0.0, GeneratorMath.rebirthBonusPercent(5.0, 0.9, 50.0, 0), 0.001);
        assertEquals(5.0, GeneratorMath.rebirthBonusPercent(5.0, 0.9, 50.0, 1), 0.001);
        assertEquals(32.57, GeneratorMath.rebirthBonusPercent(5.0, 0.9, 50.0, 10), 0.01);
        assertEquals(50.0, GeneratorMath.rebirthBonusPercent(5.0, 0.9, 50.0, 500), 0.001);
        assertEquals(25.0, GeneratorMath.rebirthBonusPercent(5.0, 1.0, 50.0, 5), 0.001);
    }
}
