package ru.cherdakmd.chrdkartifacts;

import java.util.function.BooleanSupplier;

/**
 * Правила безопасного телепорта «Ключа странника» и «Мембраны призрака».
 *
 * <p>Раньше «Ключ» строил 12 кандидатов, но телепортировал на первый же и
 * возвращал успех: остальные попытки были мёртвым кодом, а точка могла
 * оказаться в лаве, в воде, в кактусе или на крыше Нижнего мира. Здесь —
 * чистые проверки без Bukkit, чтобы поведение покрывалось тестами; сам
 * слушатель только собирает флаги блоков.</p>
 */
public final class TeleportSafety {

    /** Крыша Нижнего мира: выше этого Y начинается бедрок-потолок. */
    static final int NETHER_CEILING_Y = 120;
    /** Сколько кандидатов пробуем, прежде чем честно отказаться. */
    static final int ATTEMPTS = 16;

    private TeleportSafety() {
    }

    /** Один отказ — окончательный отказ активации, без обхода другим кандидатом. */
    static boolean perform(BooleanSupplier teleport, Runnable rejected) {
        if (teleport.getAsBoolean()) return true;
        rejected.run();
        return false;
    }

    /**
     * Годится ли колонна для приземления.
     *
     * @param feetFree   блок на уровне ног проходим и не жидкость
     * @param headFree   блок на уровне головы проходим и не жидкость
     * @param ground     под ногами твёрдый блок
     * @param groundHurt под ногами опасный блок (лава, магма, кактус, огонь…)
     */
    public static boolean isSafeSpot(boolean feetFree, boolean headFree,
                                     boolean ground, boolean groundHurt) {
        return feetFree && headFree && ground && !groundHurt;
    }

    /** Не выносит ли точка на крышу Нижнего мира (или выше потолка мира). */
    public static boolean isOnNetherRoof(boolean nether, int y) {
        return nether && y >= NETHER_CEILING_Y;
    }

    /** Y для приземления: поверхность, но не выше maxY-2 и не ниже minY+1. */
    public static int surfaceY(int highestGroundY, int minY, int maxY) {
        int feet = highestGroundY + 1;
        int top = maxY - 2;
        return Math.max(minY + 1, Math.min(top, feet));
    }

    /** Точка внутри границы мира (по X/Z). */
    public static boolean insideBorder(double x, double z,
                                       double minX, double minZ, double maxX, double maxZ) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}
