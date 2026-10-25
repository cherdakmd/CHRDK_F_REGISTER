package ru.cherdakmd.chrdkclaims;

import java.util.ArrayList;
import java.util.List;

/**
 * Геометрия телепорта к привату (1.4.120): центр чанка и порядок обхода
 * окрестности в поисках безопасной площадки.
 *
 * <p>Центр чанка — блок {@code x*16+8}: для чанка {@code -1} это {@code -8},
 * то есть точка {@code -7.5} в мире. Обход начинается с центра и идёт
 * кольцами по 1 блоку — так ближайшая безопасная колонка находится первой.</p>
 *
 * <p>Радиус ограничен семью блоками не случайно: центр смещён от края чанка
 * на восемь блоков, поэтому вся окрестность {@code center ± 7} остаётся внутри
 * привата. Телепорт не выйдет за границу чанка и не потребует загрузки соседних
 * чанков — достаточно одного, уже загруженного асинхронно.</p>
 *
 * <p>Чистая логика — покрыта {@link ClaimTeleportTest}.</p>
 */
public final class ClaimTeleport {

    /** Смещение центра чанка от его начала. */
    public static final int CENTER_OFFSET = 8;
    /** Размер чанка по горизонтали. */
    public static final int CHUNK_SIZE = 16;
    /** Только центр: искать по всей площади нельзя. */
    public static final int MIN_RADIUS = 0;
    /** Больше семи блоков — уже соседний чанк, поиск ограничен приватом. */
    public static final int MAX_RADIUS = 7;

    /** Колонка (мировые координаты блока), которую стоит проверить. */
    public record Column(int x, int z) {
    }

    /** Координата X центра чанка. */
    public static int centerX(int chunkX) {
        return chunkX * CHUNK_SIZE + CENTER_OFFSET;
    }

    /** Координата Z центра чанка. */
    public static int centerZ(int chunkZ) {
        return chunkZ * CHUNK_SIZE + CENTER_OFFSET;
    }

    /**
     * Колонки для проверки: центр, затем кольца радиуса 1..{@code radius}.
     * Радиус зажимается в {@link #MIN_RADIUS}..{@link #MAX_RADIUS}.
     */
    public static List<Column> searchOrder(int chunkX, int chunkZ, int radius) {
        int safeRadius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
        int centerX = centerX(chunkX);
        int centerZ = centerZ(chunkZ);
        List<Column> columns = new ArrayList<>((2 * safeRadius + 1) * (2 * safeRadius + 1));
        columns.add(new Column(centerX, centerZ));
        for (int ring = 1; ring <= safeRadius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue; // внутренние кольца уже проверены
                    }
                    columns.add(new Column(centerX + dx, centerZ + dz));
                }
            }
        }
        return columns;
    }

    private ClaimTeleport() {
    }
}
