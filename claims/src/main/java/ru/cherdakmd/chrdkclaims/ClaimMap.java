package ru.cherdakmd.chrdkclaims;

import java.util.ArrayList;
import java.util.List;

/**
 * АSCII-карта приватов вокруг игрока (как на «других серверах»): клетка = чанк.
 * Чистый класс — тестируется без Bukkit: соседство отдаёт функция lookup.
 */
public final class ClaimMap {

    /** Радиус по умолчанию: 11×11 чанков. */
    public static final int RADIUS = 5;
    /** Разумные границы радиуса карты (конфиг может сузить/расширить в этих пределах). */
    public static final int MIN_RADIUS = 2;
    public static final int MAX_RADIUS = 8;

    /** Минимальное описание клетки для карты. */
    public record Cell(boolean claimed, boolean mine, boolean forSale) {
        public static final Cell FREE = new Cell(false, false, false);
    }

    public interface Lookup {
        Cell cell(int chunkX, int chunkZ);
    }

    /** Рендер карты с радиусом по умолчанию. */
    public static List<String> render(int centerX, int centerZ, Lookup lookup) {
        return render(centerX, centerZ, RADIUS, lookup);
    }

    /**
     * Рендерит строки карты. Центр — текущий чанк (знак X поверх остального).
     * Верх карты — север (z меньше).
     *
     * @param radius количество чанков в каждую сторону; зажимается в
     *               {@link #MIN_RADIUS}..{@link #MAX_RADIUS}
     */
    public static List<String> render(int centerX, int centerZ, int radius, Lookup lookup) {
        int size = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
        List<String> lines = new ArrayList<>(size * 2 + 1);
        for (int dz = -size; dz <= size; dz++) {
            StringBuilder row = new StringBuilder(size * 2 + 1);
            for (int dx = -size; dx <= size; dx++) {
                Cell cell = lookup.cell(centerX + dx, centerZ + dz);
                char sign;
                if (cell == null || !cell.claimed()) {
                    sign = '.';
                } else if (cell.forSale()) {
                    sign = '$';
                } else if (cell.mine()) {
                    sign = '@';
                } else {
                    sign = '#';
                }
                if (dx == 0 && dz == 0) {
                    sign = 'X';
                }
                row.append(sign);
            }
            lines.add(row.toString());
        }
        return lines;
    }

    private ClaimMap() {
    }
}
