package ru.cherdakmd.chrdktierforge;

import java.util.ArrayList;
import java.util.List;

/**
 * Разбиение увеличенного дропа на стопки (1.4.92).
 *
 * <p>«Земледел», «Лесник» и «Глубинник» удваивают дроп прямо в Item-сущности.
 * {@code ItemStack#setAmount} бросает {@code IllegalArgumentException} выше
 * {@code Material#getMaxStackSize()}, поэтому излишек выкладывается отдельными
 * стопками, а не теряется.
 *
 * <p>Чистая логика — покрыто {@link DropAmountsTest}.
 */
final class DropAmounts {

    private DropAmounts() {
    }

    /** Разбить {@code total} на части не больше {@code maxStackSize} (нулевой/минус — пусто). */
    static List<Integer> split(int total, int maxStackSize) {
        List<Integer> parts = new ArrayList<>();
        int max = Math.max(1, maxStackSize);
        int left = Math.max(0, total);
        while (left > 0) {
            int chunk = Math.min(max, left);
            parts.add(chunk);
            left -= chunk;
        }
        return parts;
    }
}
