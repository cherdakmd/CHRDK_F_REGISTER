package ru.cherdakmd.chrdktreasures;

import java.util.Locale;

/**
 * Биомно-глубинные регионы кладов.
 *
 * <p>Клад выбирается по региону: пустыня даёт пустынные находки, Нижний мир —
 * свои, а всё, что глубже {@code deep-y}, считается «глубинами» независимо от
 * биома на поверхности. Так шахта под пустыней не выдаёт песчаные сокровища,
 * хотя формально находится в пустыне.</p>
 *
 * <p>Класс не зависит от Bukkit: регион вычисляется по трём примитивам —
 * измерению, ключу биома и координате Y. Это позволяет проверить всю таблицу
 * соответствий юнит-тестами без сервера.</p>
 */
public enum TreasureRegion {

    PLAINS("plains"),
    FOREST("forest"),
    TAIGA("taiga"),
    SNOW("snow"),
    DESERT("desert"),
    SAVANNA("savanna"),
    JUNGLE("jungle"),
    SWAMP("swamp"),
    MOUNTAIN("mountain"),
    OCEAN("ocean"),
    DEEP("deep"),
    DEEP_DARK("deep_dark"),
    NETHER("nether"),
    END("end");

    /** Измерение без Bukkit-типов. */
    public enum Dimension {
        OVERWORLD,
        NETHER,
        END
    }

    /** Ниже этой координаты поверхностный биом уступает «глубинам». */
    public static final int DEEP_Y = -8;

    private final String id;

    TreasureRegion(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /**
     * Регион по контексту добычи.
     *
     * @param dimension измерение (не {@code null})
     * @param biomeKey  ключ биома без пространства имён ({@code plains})
     * @param y         координата блока
     */
    public static TreasureRegion resolve(Dimension dimension, String biomeKey, int y) {
        Dimension dim = dimension == null ? Dimension.OVERWORLD : dimension;
        if (dim == Dimension.NETHER) {
            return NETHER;
        }
        if (dim == Dimension.END) {
            return END;
        }
        String key = biomeKey == null ? "" : biomeKey.toLowerCase(Locale.ROOT);
        int namespace = key.indexOf(':');
        if (namespace >= 0 && namespace + 1 < key.length()) {
            key = key.substring(namespace + 1);
        }
        if (key.equals("deep_dark")) {
            return DEEP_DARK;
        }
        // Вода и побережье важнее глубины: подводная шахта остаётся океанской.
        if (key.contains("ocean") || key.contains("river") || key.contains("beach")) {
            return OCEAN;
        }
        if (y <= DEEP_Y) {
            return DEEP;
        }
        if (key.contains("snow") || key.contains("frozen") || key.contains("ice")
                || key.equals("grove")) {
            return SNOW;
        }
        if (key.contains("desert") || key.contains("badlands")) {
            return DESERT;
        }
        if (key.contains("jungle") || key.contains("bamboo")) {
            return JUNGLE;
        }
        if (key.contains("swamp") || key.contains("mangrove")) {
            return SWAMP;
        }
        if (key.contains("savanna")) {
            return SAVANNA;
        }
        if (key.contains("taiga") || key.contains("old_growth")) {
            return TAIGA;
        }
        if (key.contains("peaks") || key.contains("meadow") || key.contains("slopes")
                || key.contains("stony")) {
            return MOUNTAIN;
        }
        if (key.contains("plains") || key.contains("sunflower")) {
            return PLAINS;
        }
        return FOREST;
    }

    public static TreasureRegion byId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (TreasureRegion region : values()) {
            if (region.id.equals(normalized) || region.name().equalsIgnoreCase(normalized)) {
                return region;
            }
        }
        return null;
    }
}
