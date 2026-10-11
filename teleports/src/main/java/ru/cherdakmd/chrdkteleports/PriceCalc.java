package ru.cherdakmd.chrdkteleports;

/**
 * Итоговая стоимость телепорта: vip-скидка в процентах, округление в пользу игрока
 * (базовое целочисленное деление). Чистая функция.
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
        return Math.max(0, cost * (100 - clamped) / 100);
    }
}
