package ru.cherdakmd.chrdktreasures;

/**
 * Подсказка к клад-карте: направление, дистанция и «вы уже в зоне» (1.4.153).
 *
 * <p>Отдельный чистый класс, чтобы правило компаса проверялось без сервера.
 * В Minecraft север — это {@code -Z}, восток — {@code +X}; игроку привычнее
 * видеть «СВ» и «312», чем сырые координаты, поэтому подсказка считается от
 * направления и округляется до восьми румбов.</p>
 *
 * @param sameWorld карта ведёт в текущий мир игрока
 * @param inZone    игрок уже стоит в зоне раскопок
 * @param distance  расстояние до точки тайника в блоках (0 — другой мир)
 * @param direction румб ({@code С}, {@code СВ}, {@code В} …); пусто — другой мир
 */
public record TreasureMapHint(boolean sameWorld, boolean inZone, int distance, String direction) {

    /** Румбы по часовой стрелке от востока — порядок важен для индексации. */
    public static final String[] COMPASS = {"В", "СВ", "С", "СЗ", "З", "ЮЗ", "Ю", "ЮВ"};

    /** Подсказка для карты из другого мира: направление бессмысленно. */
    public static TreasureMapHint otherWorld() {
        return new TreasureMapHint(false, false, 0, "");
    }

    /**
     * Считает подсказку от позиции игрока.
     *
     * @param map      карта
     * @param world    имя мира игрока
     * @param playerX  координата игрока по X
     * @param playerZ  координата игрока по Z
     */
    public static TreasureMapHint of(TreasureMap map, String world, int playerX, int playerZ) {
        if (map == null || world == null || !world.equals(map.world())) {
            return otherWorld();
        }
        int dx = map.x() - playerX;
        int dz = map.z() - playerZ;
        return new TreasureMapHint(true, map.inZone(world, playerX, playerZ),
                distanceBlocks(dx, dz), compass(dx, dz));
    }

    /** Румб по смещению. Совпадение точки с игроком — «здесь». */
    public static String compass(int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return "здесь";
        }
        // atan2(-dz, dx): север (-Z) даёт 90°, восток — 0°, юг — -90°.
        double degrees = Math.toDegrees(Math.atan2(-dz, dx));
        int sector = (int) Math.round(degrees / 45.0);
        sector = ((sector % 8) + 8) % 8;
        return COMPASS[sector];
    }

    /** Расстояние в блоках с округлением; переполнение int не страшно. */
    public static int distanceBlocks(int dx, int dz) {
        return (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
    }

    /**
     * Человеческое время до сгорания: «3 ч 40 мин», «12 мин», «меньше минуты».
     * Для бессрочной карты возвращает пустую строку — её и показывать не надо.
     */
    public static String duration(long millis) {
        if (millis <= 0L) {
            return "";
        }
        long minutes = millis / 60_000L;
        if (minutes <= 0L) {
            return "меньше минуты";
        }
        if (minutes < 60L) {
            return minutes + " мин";
        }
        long hours = minutes / 60L;
        long rest = minutes % 60L;
        return rest == 0L ? hours + " ч" : hours + " ч " + rest + " мин";
    }
}
