package ru.cherdakmd.chrdktierforge;

import java.util.List;

/**
 * Титулы коллекции кузни (1.4.151).
 *
 * <p>Титул выдаётся за полностью закрытый раздел: 7 редкостей, 30 аффиксов,
 * 105 свойств или вся кузня (142). Если закрыто несколько — показывается
 * самый весомый. Класс чистый: только счётчики и пороги, поэтому выбор титула
 * проверяется юнит-тестами, а текст берётся из {@code messages.title.<id>}.</p>
 */
public final class TierCollectionTitles {

    /**
     * Сила титулов: от самого весомого к простому. Порядок осознанный —
     * вся кузня весомее свойств, свойства весомее аффиксов, аффиксы весомее
     * одной лишь лестницы редкостей.
     */
    public static final List<String> STRENGTH = List.of("all", "properties", "affixes", "tiers");

    private TierCollectionTitles() {
    }

    /** Самый весомый закрытый раздел или {@code null}, если титула ещё нет. */
    public static String best(TierCollectionBook.Progress progress) {
        if (progress == null) {
            return null;
        }
        for (String section : STRENGTH) {
            if (progress.complete(section)) {
                return section;
            }
        }
        return null;
    }

    /**
     * Ближайший незакрытый раздел — для подсказки в витрине.
     *
     * <p>Сравнивается доля собранного: раздел, до которого осталось меньше
     * всего, и есть «следующий титул». При равенстве выигрывает раздел с
     * меньшим порогом — новичку полезнее видеть ближайшую цель.</p>
     *
     * @return ид раздела или {@code null}, если закрыто всё
     */
    public static String next(TierCollectionBook.Progress progress) {
        if (progress == null) {
            return null;
        }
        String candidate = null;
        long bestNumerator = -1;
        long bestDenominator = 1;
        int bestThreshold = Integer.MAX_VALUE;
        for (String section : STRENGTH) {
            int threshold = TierCollectionConfig.threshold(section);
            int count = progress.count(section);
            if (threshold <= 0 || count >= threshold) {
                continue;
            }
            // Сравниваем дроби count/threshold без double: a/b > c/d ⇔ a*d > c*b.
            long numerator = count;
            long denominator = threshold;
            boolean better = candidate == null
                    || numerator * bestDenominator > bestNumerator * denominator
                    || (numerator * bestDenominator == bestNumerator * denominator
                    && threshold < bestThreshold);
            if (better) {
                candidate = section;
                bestNumerator = numerator;
                bestDenominator = denominator;
                bestThreshold = threshold;
            }
        }
        return candidate;
    }
}
