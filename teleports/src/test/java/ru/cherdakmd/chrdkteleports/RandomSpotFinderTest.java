package ru.cherdakmd.chrdkteleports;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Точка в кольце [min..R], радиусы уважены, центр учитывается. */
class RandomSpotFinderTest {

    @Test
    void withinRingBounds() {
        Random random = new Random(42);
        for (int i = 0; i < 3000; i++) {
            long[] spot = RandomSpotFinder.pick(random, 0, 0, 10000, 250);
            double dist = Math.sqrt((double) spot[0] * spot[0] + (double) spot[1] * spot[1]);
            assertTrue(dist >= 250, "ближе min-radius: " + dist);
            assertTrue(dist <= 10000 + 2, "дальше R: " + dist);
        }
    }

    @Test
    void respectsCenterOffset() {
        Random random = new Random(7);
        long[] spot = RandomSpotFinder.pick(random, 5000, -8000, 0, 0);
        assertEquals(5000, spot[0]);
        assertEquals(-8000, spot[1]);
        for (int i = 0; i < 1000; i++) {
            spot = RandomSpotFinder.pick(random, 5000, -8000, 100, 0);
            double dx = spot[0] - 5000;
            double dz = spot[1] + 8000;
            double dist = Math.sqrt(dx * dx + dz * dz);
            assertTrue(dist <= 100 + 2);
        }
    }

    @Test
    void minBiggerThanRadiusClampedInside() {
        // защита в pick: при инвертированных границах не падаем и рисуем точно на границе
        Random random = new Random(1);
        long[] spot = RandomSpotFinder.pick(random, 0, 0, 100, 100);
        double dist = Math.sqrt((double) spot[0] * spot[0] + (double) spot[1] * spot[1]);
        assertTrue(dist <= 100 + 2);
    }
}
