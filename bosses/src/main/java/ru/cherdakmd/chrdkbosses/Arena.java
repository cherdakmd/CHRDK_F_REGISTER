package ru.cherdakmd.chrdkbosses;

/**
 * Арена босса (1.4.162).
 *
 * <p>Просто точка в мире и радиус: босс не уходит с арены, а игроки понимают,
 * где искать бой. Если высота не задана ({@code y < 0}), сервис ставит босса
 * на поверхность — так конфиг переживает смену рельефа.</p>
 *
 * @param world  имя мира
 * @param x      координата X центра
 * @param y      координата Y; отрицательная — «на поверхность»
 * @param z      координата Z центра
 * @param radius радиус арены в блоках
 */
public record Arena(String world, double x, double y, double z, double radius) {

    /** Потолок радиуса: арена не должна накрывать полмира. */
    public static final double MAX_RADIUS = 64.0;
    /** Значение Y, означающее «поставить на поверхность». */
    public static final double AUTO_HEIGHT = -1.0;

    public Arena {
        world = world == null ? "" : world.trim();
        x = Math.clamp(x, -30_000_000.0, 30_000_000.0);
        y = Math.clamp(y, -256.0, 320.0);
        z = Math.clamp(z, -30_000_000.0, 30_000_000.0);
        radius = Math.clamp(radius, 4.0, MAX_RADIUS);
    }

    /** Нужно ли искать высоту на месте. */
    public boolean autoHeight() {
        return y < 0;
    }

    /** Пригодна ли арена: без мира босса некуда ставить. */
    public boolean usable() {
        return !world.isEmpty();
    }

    /** Лежит ли точка внутри арены (по горизонтали и вертикали). */
    public boolean contains(String otherWorld, double otherX, double otherY, double otherZ) {
        if (otherWorld == null || !world.equalsIgnoreCase(otherWorld)) {
            return false;
        }
        double dx = otherX - x;
        double dz = otherZ - z;
        if (dx * dx + dz * dz > radius * radius) {
            return false;
        }
        return autoHeight() || Math.abs(otherY - y) <= radius;
    }

    /** Короткая подпись для меню и лога. */
    public String label() {
        return world + " " + Math.round(x) + " " + Math.round(y) + " " + Math.round(z)
                + " (r" + Math.round(radius) + ")";
    }
}
