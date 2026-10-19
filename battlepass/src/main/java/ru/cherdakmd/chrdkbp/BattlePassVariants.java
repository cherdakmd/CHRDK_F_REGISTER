package ru.cherdakmd.chrdkbp;

import java.util.Locale;

/**
 * Варианты блоков для заданий: «алмазная руда» должна засчитываться и для
 * глубокосланцевой руды, а обтёсанное бревно — как то же дерево.
 *
 * <p>Сопоставление намеренно узкое: одинаковым считается только тот же самый блок
 * в другой форме, а не «похожий». {@code DEEPSLATE_BRICKS} не равен {@code BRICKS},
 * {@code OAK_LOG} не равен {@code BIRCH_LOG} — иначе задание «добыть дуб» можно было
 * бы выполнить на берёзе.</p>
 */
final class BattlePassVariants {

    private BattlePassVariants() {
    }

    /** Совпадают ли материалы с учётом вариантов (регистр не важен). */
    static boolean sameMaterial(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.equalsIgnoreCase(right)) {
            return true;
        }
        return canonical(left).equals(canonical(right));
    }

    /**
     * Каноническая форма материала:
     * <ul>
     *   <li>глубокосланцевая руда → обычная руда ({@code deepslate_diamond_ore → diamond_ore});</li>
     *   <li>обтёсанное бревно/древесина → бревно ({@code stripped_oak_log → oak_log});</li>
     *   <li>древесина → бревно ({@code oak_wood → oak_log}).</li>
     * </ul>
     * Всё остальное остаётся как есть (в нижнем регистре).
     */
    static String canonical(String key) {
        if (key == null) {
            return "";
        }
        String result = key.trim().toLowerCase(Locale.ROOT);
        if (result.startsWith("stripped_")) {
            result = result.substring("stripped_".length());
        }
        // Только руды: DEEPSLATE_BRICKS не должен превращаться в BRICKS.
        if (result.startsWith("deepslate_") && result.endsWith("_ore")) {
            result = result.substring("deepslate_".length());
        }
        if (result.endsWith("_wood")) {
            result = result.substring(0, result.length() - "_wood".length()) + "_log";
        }
        return result;
    }
}
