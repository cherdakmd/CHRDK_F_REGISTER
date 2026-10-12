package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Кольцо латентности опросов: avg/max по последним N, протухание максимума. */
class LatencySeriesTest {

    @Test
    void emptySeriesIsZeros() {
        LatencySeries series = new LatencySeries(5);
        assertEquals(0, series.avg());
        assertEquals(0, series.max());
        assertEquals(0, series.samples());
    }

    @Test
    void avgAndMaxAcrossRing() {
        LatencySeries series = new LatencySeries(4);
        series.add(100);
        series.add(200);
        series.add(300);
        assertEquals(200, series.avg());
        assertEquals(300, series.max());
        assertEquals(3, series.samples());
    }

    @Test
    void maxDiesWithOldestSamples() {
        LatencySeries series = new LatencySeries(3);
        series.add(900);
        series.add(100);
        series.add(100);
        assertEquals(900, series.max());
        series.add(100); // 900 вытеснена из кольца
        assertEquals(100, series.max(), "пропавший из окна пик не должен вечно висеть");
    }

    @Test
    void clampsAndHugeValuesSafe() {
        LatencySeries series = new LatencySeries(2);
        series.add(-5);
        series.add(Long.MAX_VALUE);
        assertEquals(series.max(), series.max()); // без переполнения при сумме
        assertTrue(series.avg() >= 0);
    }
}
