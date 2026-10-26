package ru.cherdakmd.chrdkevents;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Воронка от звезды (1.4.176): форма и список материалов.
 *
 * <p>Тесты держат два обещания перед игроком: воронка маленькая (центр, кольца,
 * не больше двух слоёв) и не может сломать постройку — в списке природных
 * блоков нет досок, стекла, кирпича и сундуков.</p>
 */
class CraterTest {

    @Test
    void radiusZeroBreaksNothing() {
        assertTrue(Crater.columns(0).isEmpty());
        assertTrue(Crater.columns(-5).isEmpty());
    }

    @Test
    void centerGoesFirstAndEveryOffsetIsInsideRadius() {
        for (int radius = 1; radius <= MeteorSpawn.MAX_CRATER_RADIUS; radius++) {
            List<int[]> columns = Crater.columns(radius);
            assertFalse(columns.isEmpty());
            assertEquals(0, columns.get(0)[0], "первым идёт центр");
            assertEquals(0, columns.get(0)[1], "первым идёт центр");
            Set<String> unique = new HashSet<>();
            for (int[] column : columns) {
                int limit = radius * radius + radius;
                assertTrue(column[0] * column[0] + column[1] * column[1] <= limit,
                        "смещение вне воронки: " + column[0] + "," + column[1]);
                assertTrue(unique.add(column[0] + ":" + column[1]),
                        "колонка повторяется: " + column[0] + "," + column[1]);
            }
        }
        assertEquals(5, Crater.columns(1).size());
        assertEquals(13, Crater.columns(2).size());
    }

    @Test
    void onlyNaturalGroundIsBreakable() {
        assertTrue(Crater.natural(Material.STONE));
        assertTrue(Crater.natural(Material.GRASS_BLOCK));
        assertTrue(Crater.natural(Material.DIRT));
        assertTrue(Crater.natural(Material.DEEPSLATE));
        assertTrue(Crater.natural(Material.SAND));
        assertTrue(Crater.natural(Material.SNOW_BLOCK));

        assertFalse(Crater.natural(Material.OAK_PLANKS), "постройки не ломаем");
        assertFalse(Crater.natural(Material.STONE_BRICKS), "кирпич — уже постройка");
        assertFalse(Crater.natural(Material.GLASS));
        assertFalse(Crater.natural(Material.CHEST));
        assertFalse(Crater.natural(Material.OAK_LOG));
        assertFalse(Crater.natural(Material.AIR));
        assertFalse(Crater.natural(null));
    }

    @Test
    void depthIsShallow() {
        assertEquals(2, Crater.DEPTH, "воронка — след звезды, а не карьер");
    }
}
