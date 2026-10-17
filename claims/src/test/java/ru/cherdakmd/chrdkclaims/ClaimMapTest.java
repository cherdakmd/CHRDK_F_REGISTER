package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimMapTest {

    @Test
    void rendersSquareWithCenterMarker() {
        List<String> rows = ClaimMap.render(0, 0, (x, z) -> ClaimMap.Cell.FREE);
        assertEquals(11, rows.size());
        for (String row : rows) {
            assertEquals(11, row.length());
        }
        assertEquals('X', rows.get(5).charAt(5), "центр карты — игрок");
        assertTrue(rows.get(0).chars().allMatch(c -> c == '.'), "пустая округ — точки");
    }

    @Test
    void radiusIsConfigurableAndClamped() {
        List<String> small = ClaimMap.render(0, 0, 1, (x, z) -> ClaimMap.Cell.FREE);
        assertEquals(3, small.size(), "радиус 1 — 3×3");
        assertEquals(3, small.get(0).length());

        List<String> wide = ClaimMap.render(0, 0, 100, (x, z) -> ClaimMap.Cell.FREE);
        assertEquals(ClaimMap.MAX_RADIUS * 2 + 1, wide.size(), "радиус зажат сверху");
        List<String> narrow = ClaimMap.render(0, 0, 0, (x, z) -> ClaimMap.Cell.FREE);
        assertEquals(ClaimMap.MIN_RADIUS * 2 + 1, narrow.size(), "радиус зажат снизу");
    }

    @Test
    void marksMineForeignAndForSale() {
        List<String> rows = ClaimMap.render(0, 0, (x, z) -> {
            if (x == 1 && z == 0) {
                return new ClaimMap.Cell(true, true, false);   // сосед справа — мой
            }
            if (x == -1 && z == 0) {
                return new ClaimMap.Cell(true, false, false);  // слева — чужой
            }
            if (x == 0 && z == 1) {
                return new ClaimMap.Cell(true, false, true);   // снизу — продаётся
            }
            return ClaimMap.Cell.FREE;
        });
        assertEquals('@', rows.get(5).charAt(6));
        assertEquals('#', rows.get(5).charAt(4));
        assertEquals('$', rows.get(6).charAt(5));
    }

    @Test
    void northIsUp() {
        // z меньше = строка выше
        List<String> rows = ClaimMap.render(0, 0, (x, z) ->
                z == -2 && x == 0 ? new ClaimMap.Cell(true, false, false) : ClaimMap.Cell.FREE);
        assertEquals('#', rows.get(3).charAt(5), "чанк на севере (z-2) — третья строка сверху");
    }
}
