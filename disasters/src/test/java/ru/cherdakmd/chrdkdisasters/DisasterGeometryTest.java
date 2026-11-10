package ru.cherdakmd.chrdkdisasters;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Геометрия deterministic и остаётся внутри радиуса. */
final class DisasterGeometryTest {

    private static List<String> keys(List<int[]> points) {
        return points.stream().map(Arrays::toString).toList();
    }

    @Test
    void spiralIsDeterministicInsideRadiusAndWithoutDuplicates() {
        List<int[]> first = DisasterGeometry.spiral(12, 30, 3);
        List<int[]> second = DisasterGeometry.spiral(12, 30, 3);
        assertEquals(keys(first), keys(second));
        assertFalse(first.isEmpty());
        assertEquals(first.size(), Set.copyOf(keys(first)).size(), "точки не повторяются");
        for (int[] point : first) {
            assertTrue(point[0] * point[0] + point[1] * point[1] <= 12 * 12,
                    "смерч не выходит за радиус");
        }
        List<int[]> rotated = DisasterGeometry.spiral(12, 30, 4);
        assertFalse(keys(first).equals(keys(rotated)), "на каждом тике воронка поворачивается");
    }

    @Test
    void scatterFillsCircleWithoutDuplicates() {
        List<int[]> points = DisasterGeometry.scatter(8, 20, 12345L);
        assertTrue(points.size() >= 12 && points.size() <= 20,
                "круг даёт достаточно уникальных колонок");
        assertEquals(points.size(), Set.copyOf(keys(points)).size());
        for (int[] point : points) {
            assertTrue(point[0] * point[0] + point[1] * point[1] <= 8 * 8);
        }
        assertEquals(keys(points), keys(DisasterGeometry.scatter(8, 20, 12345L)),
                "тот же seed — та же карта");
        assertEquals(0, DisasterGeometry.scatter(4, 0, 1L).size());
    }

    @Test
    void ringAndParticlesRespectTheirLimits() {
        List<int[]> ring = DisasterGeometry.ring(10, 16, 0);
        assertFalse(ring.isEmpty());
        assertEquals(ring.size(), Set.copyOf(keys(ring)).size());
        for (int[] point : ring) {
            assertTrue(point[0] * point[0] + point[1] * point[1] <= 10 * 10 + 2);
        }
        assertTrue(DisasterGeometry.particles(5, false)
                > DisasterGeometry.particles(5, true));
        assertEquals(7, DisasterGeometry.particles(1, true));
        assertEquals(7, DisasterGeometry.particles(0, true), "уровень зажат снизу");
    }

    @Test
    void floodColumnsNeverEscapeTheRequestedCount() {
        Set<String> all = new HashSet<>();
        for (int step = 0; step < 8; step++) {
            List<int[]> columns = DisasterGeometry.scatter(6, 6, step * 31L);
            assertTrue(columns.size() <= 6, "лимит колонок на тик");
            for (int[] point : columns) {
                assertTrue(point[0] * point[0] + point[1] * point[1] <= 36);
                all.add(Arrays.toString(point));
            }
        }
        assertTrue(all.size() > 6, "за восемь тиков заливка сдвигается по площади");
    }
}
