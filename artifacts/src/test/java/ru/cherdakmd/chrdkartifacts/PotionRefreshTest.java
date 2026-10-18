package ru.cherdakmd.chrdkartifacts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PotionRefreshTest {

    @Test
    void durationAlwaysOutlivesThePulse() {
        for (int refresh : new int[]{10, 40, 120, 200}) {
            int duration = PotionRefresh.durationTicks(refresh, false);
            assertTrue(duration > refresh,
                    "эффект на " + duration + " тиков должен пережить пульс " + refresh);
        }
    }

    @Test
    void durationBoundaries() {
        assertEquals(PotionRefresh.MIN_TICKS + PotionRefresh.BUFFER_TICKS,
                PotionRefresh.durationTicks(0, false));
        assertEquals(1, PotionRefresh.durationTicks(200, true), "мгновенный — раз за пульс");
        assertEquals(240, PotionRefresh.durationTicks(200, false));
        assertEquals(90, PotionRefresh.durationTicks(40, false));
    }

    @Test
    void strongerExternalEffectIsNotDowngraded() {
        assertFalse(PotionRefresh.shouldApply(true, 1, 400, 0, 300),
                "Speed II из зелья не понижается до Speed I артефакта");
        assertFalse(PotionRefresh.shouldApply(true, 2, 5, 1, 300));
    }

    @Test
    void sameOrWeakerEffectIsToppedUp() {
        assertTrue(PotionRefresh.shouldApply(false, 0, 0, 0, 300), "эффекта нет — вешаем");
        assertTrue(PotionRefresh.shouldApply(true, 0, 20, 0, 300), "свой эффект догоняем");
        assertTrue(PotionRefresh.shouldApply(true, 0, 400, 0, 300), "остаток меньше — обновляем");
        assertTrue(PotionRefresh.shouldApply(true, -1, 400, 0, 300), "чужой дебафф заменяем своим");
    }
}
