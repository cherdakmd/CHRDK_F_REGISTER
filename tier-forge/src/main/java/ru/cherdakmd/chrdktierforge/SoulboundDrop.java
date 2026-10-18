package ru.cherdakmd.chrdktierforge;

import java.util.function.IntPredicate;

/**
 * Выбор безопасной точки для выкладки вещей «привязки души» (1.4.88).
 *
 * <p>Если игрок вышел с экрана смерти (или сервер выключился), спасённые вещи
 * выкладываются в мир — но точка смерти может быть в лаве, в воде или в пустоте
 * Нижнего мира. Хелпер подбирает Y в границах мира и проверяет кандидатов
 * предикатом «можно стоять»; Bukkit-часть (блоки) живёт в
 * {@code ForgeUpgradeListener}.
 *
 * <p>Чистая логика — покрыта {@link SoulboundDropTest}.
 */
final class SoulboundDrop {

    private SoulboundDrop() {
    }

    /** Y в допустимых границах мира (не ниже minHeight+1 и не выше maxHeight−1). */
    static int clampY(int y, int minHeight, int maxHeight) {
        int min = minHeight + 1;
        int max = Math.max(min, maxHeight - 1);
        return Math.max(min, Math.min(max, y));
    }

    /**
     * Первый безопасный Y: сначала предпочтительный, затем по одному блоку вверх
     * и вниз (до {@code radius}). Если ничего не подошло — возвращается
     * предпочтительный Y, зажатый в границы мира.
     */
    static int safeY(int preferred, int minHeight, int maxHeight, int radius, IntPredicate safe) {
        int start = clampY(preferred, minHeight, maxHeight);
        if (safe.test(start)) {
            return start;
        }
        for (int dy = 1; dy <= Math.max(0, radius); dy++) {
            int up = start + dy;
            if (up <= maxHeight - 1 && safe.test(up)) {
                return up;
            }
            int down = start - dy;
            if (down >= minHeight + 1 && safe.test(down)) {
                return down;
            }
        }
        return start;
    }
}
