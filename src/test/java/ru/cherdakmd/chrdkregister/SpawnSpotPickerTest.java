package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Точка в кольце [min..R]: границы, центр, равномерность по площади, лимит мира. */
class SpawnSpotPickerTest {

    @Test
    void staysInsideRing() {
        Random random = new Random(42);
        for (int i = 0; i < 3000; i++) {
            int[] spot = SpawnSpotPicker.pick(random, 0, 0, 3000, 200);
            double distance = Math.hypot(spot[0], spot[1]);
            assertTrue(distance >= 200 - 1, "ближе min-radius: " + distance);
            assertTrue(distance <= 3000 + 2, "дальше радиуса: " + distance);
        }
    }

    @Test
    void honoursCenter() {
        Random random = new Random(7);
        for (int i = 0; i < 1000; i++) {
            int[] spot = SpawnSpotPicker.pick(random, 5000, -8000, 100, 0);
            assertTrue(Math.abs(spot[0] - 5000) <= 102);
            assertTrue(Math.abs(spot[1] + 8000) <= 102);
        }
        int[] exact = SpawnSpotPicker.pick(random, 5000, -8000, 0, 0);
        assertEquals(5000, exact[0]);
        assertEquals(-8000, exact[1]);
    }

    @Test
    void minRadiusBiggerThanRadiusIsClamped() {
        Random random = new Random(3);
        int[] spot = SpawnSpotPicker.pick(random, 0, 0, 100, 500);
        double distance = Math.hypot(spot[0], spot[1]);
        assertTrue(distance <= 102, "точка должна остаться на границе радиуса: " + distance);
    }

    @Test
    void spreadsOverAreaNotAroundCenter() {
        // При равномерном по площади выборе половина точек лежит дальше ~0.707R — у центра их мало.
        Random random = new Random(11);
        int outside = 0;
        for (int i = 0; i < 2000; i++) {
            int[] spot = SpawnSpotPicker.pick(random, 0, 0, 1000, 0);
            if (Math.hypot(spot[0], spot[1]) > 700) {
                outside++;
            }
        }
        assertTrue(outside > 400, "точки скапливаются у центра: " + outside);
        Set<String> unique = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            int[] spot = SpawnSpotPicker.pick(random, 0, 0, 1000, 0);
            unique.add(spot[0] + ":" + spot[1]);
        }
        assertTrue(unique.size() > 190, "точки повторяются: " + unique.size());
    }

    @Test
    void clampsToWorldLimit() {
        assertEquals(SpawnSpotPicker.WORLD_LIMIT, SpawnSpotPicker.clamp(40_000_000));
        assertEquals(-SpawnSpotPicker.WORLD_LIMIT, SpawnSpotPicker.clamp(-40_000_000));
        assertEquals(0, SpawnSpotPicker.clamp(0.4));
    }
}
