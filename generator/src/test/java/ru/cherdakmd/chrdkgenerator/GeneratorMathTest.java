package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void menuRangeCoversCoreChunkAndSixteenBlockRadius() {
        // Ядро в блоке (100, 64, 200): его чанк — x 96..111, z 192..207.
        int coreX = 100, coreY = 64, coreZ = 200;
        int coreChunkX = coreX >> 4, coreChunkZ = coreZ >> 4;
        int range = GeneratorMath.DEFAULT_MENU_RANGE;
        // Тот же чанк: доступен даже в дальнем углу и по высоте — дальше радиуса.
        assertTrue(GeneratorMath.withinMenuRange(coreChunkX, coreChunkZ,
                111.5, 110.5, 207.5, coreX, coreY, coreZ, range));
        // Другой чанк, но ровно на границе радиуса 16 блоков от центра ядра.
        assertTrue(GeneratorMath.withinMenuRange(coreChunkX + 1, coreChunkZ,
                116.5, 64.5, 200.5, coreX, coreY, coreZ, range));
        assertFalse(GeneratorMath.withinMenuRange(coreChunkX + 1, coreChunkZ,
                116.51, 64.5, 200.5, coreX, coreY, coreZ, range));
        // Другой чанк и дальше радиуса — отказ.
        assertFalse(GeneratorMath.withinMenuRange(coreChunkX + 3, coreChunkZ,
                160.5, 64.5, 200.5, coreX, coreY, coreZ, range));
        // Высота участвует: смещение 12/6 ещё в радиусе, 12/12 — уже нет.
        assertTrue(GeneratorMath.withinMenuRange(coreChunkX + 1, coreChunkZ,
                112.5, 70.5, 200.5, coreX, coreY, coreZ, range));
        assertFalse(GeneratorMath.withinMenuRange(coreChunkX + 1, coreChunkZ,
                112.5, 76.5, 200.5, coreX, coreY, coreZ, range));
    }

    @Test
    void menuRangeUsesArithmeticChunkShiftForNegativeCoordinates() {
        // Ядро в блоке −1 (чанк −1): дальний угол того же чанка открыт без радиуса.
        assertTrue(GeneratorMath.withinMenuRange(-1, -1,
                -15.5, 64.5, -15.5, -1, 64, -1, 16));
        // Чанк 0 (блок 16) и расстояние 17 блоков — отказ.
        assertFalse(GeneratorMath.withinMenuRange(0, -1,
                16.5, 64.5, -0.5, -1, 64, -1, 16));
    }

    @Test
    void menuRangeHonorsConfiguredRadius() {
        // Радиус 32 доезжает до 24 блоков; радиус 8 — уже нет.
        assertTrue(GeneratorMath.withinMenuRange(1, 1,
                124.5, 64.5, 200.5, 100, 64, 200, 32));
        assertFalse(GeneratorMath.withinMenuRange(1, 1,
                124.5, 64.5, 200.5, 100, 64, 200, 8));
    }
}
