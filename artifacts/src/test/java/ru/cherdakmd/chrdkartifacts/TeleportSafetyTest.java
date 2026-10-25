package ru.cherdakmd.chrdkartifacts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeleportSafetyTest {

    @Test
    void safeColumnNeedsFreeFeetHeadSolidGroundAndNoHazard() {
        assertTrue(TeleportSafety.isSafeSpot(true, true, true, false));
        assertFalse(TeleportSafety.isSafeSpot(false, true, true, false), "ноги в блоке");
        assertFalse(TeleportSafety.isSafeSpot(true, false, true, false), "голова в блоке");
        assertFalse(TeleportSafety.isSafeSpot(true, true, false, false), "под ногами пустота");
        assertFalse(TeleportSafety.isSafeSpot(true, true, true, true), "лава/кактус под ногами");
    }

    @Test
    void netherRoofIsRefused() {
        assertTrue(TeleportSafety.isOnNetherRoof(true, 120));
        assertTrue(TeleportSafety.isOnNetherRoof(true, 128));
        assertFalse(TeleportSafety.isOnNetherRoof(true, 119));
        assertFalse(TeleportSafety.isOnNetherRoof(false, 300), "в верхнем мире крыши нет");
    }

    @Test
    void surfaceStaysInsideWorld() {
        // обычный случай: поверхность +1
        assertEquals(65, TeleportSafety.surfaceY(64, -64, 320));
        // выше потолка — прижимаем к maxY-2
        assertEquals(318, TeleportSafety.surfaceY(400, -64, 320));
        // ниже дна — прижимаем к minY+1
        assertEquals(-63, TeleportSafety.surfaceY(-80, -64, 320));
    }

    @Test
    void borderCheckIsInclusive() {
        assertTrue(TeleportSafety.insideBorder(0, 0, -100, -100, 100, 100));
        assertTrue(TeleportSafety.insideBorder(100, -100, -100, -100, 100, 100));
        assertFalse(TeleportSafety.insideBorder(101, 0, -100, -100, 100, 100));
        assertFalse(TeleportSafety.insideBorder(0, -101, -100, -100, 100, 100));
    }

    @Test
    void attemptBudgetIsFiniteButGenerous() {
        assertTrue(TeleportSafety.ATTEMPTS >= 8, "слишком мало попыток — частые отказы");
        assertTrue(TeleportSafety.ATTEMPTS <= 32, "слишком много — лаг на клик");
    }
}
