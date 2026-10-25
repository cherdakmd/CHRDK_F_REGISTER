package ru.cherdakmd.chrdkteleports;

/**
 * Итоговая стоимость телепорта: vip-скидка в процентах, округление в пользу игрока
 * (базовое целочисленное деление). Чистая функция.
 *
 * <p>Скидка режет цену, но не обнуляет её: платный телепорт стоит минимум один
 * алмаз. Иначе 50 % от одного алмаза превращали переход в бесплатный, и скидка
 * ранга работала как «телепорт за так».</p>
 */
public final class PriceCalc {

    private PriceCalc() {
    }

    public static int apply(int cost, boolean vip, int discountPercent) {
        if (cost <= 0) {
            return 0;
        }
        if (!vip || discountPercent <= 0) {
            return cost;
        }
        int clamped = Math.min(discountPercent, 100);
        int discounted = cost * (100 - clamped) / 100;
        // Минимум один алмаз: бесплатных телепортов нет даже со скидкой 100 %.
        return Math.max(1, discounted);
    }
}
