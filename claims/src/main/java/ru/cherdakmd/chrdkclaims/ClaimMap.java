package ru.cherdakmd.chrdkclaims;

import java.util.ArrayList;
import java.util.List;

/**
 * АSCII-карта приватов вокруг игрока (как на «других серверах»): клетка = чанк.
 * Чистый класс — тестируется без Bukkit: соседство отдаёт функция lookup.
 */
public final class ClaimMap {

    public static final int RADIUS = 5; // 11×11

    /** Минимальное описание клетки для карты. */
    public record Cell(boolean claimed, boolean mine, boolean forSale) {
        public static final Cell FREE = new Cell(false, false, false);
    }

    public interface Lookup {
        Cell cell(int chunkX, int chunkZ);
    }

    /**
     * Рендерит строки карты. Центр — текущий чанк (знак X поверх остального).
     * Верх карты — север (z меньше).
     */
    public static List<String> render(int centerX, int centerZ, Lookup lookup) {
        List<String> lines = new ArrayList<>();
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            StringBuilder row = new StringBuilder();
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
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
