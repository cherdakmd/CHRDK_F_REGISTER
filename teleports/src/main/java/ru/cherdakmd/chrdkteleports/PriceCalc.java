package ru.cherdakmd.chrdkteleports;

/** Итог услуги: скидка ранга, округление вверх и минимум 10 после скидки. */
public final class PriceCalc {
    public static final int MINIMUM = 10;
    private PriceCalc() { }
    /** Ноль разрешён только для явно бесплатной установки точки, не для перемещения. */
    public static int apply(int cost, boolean vip, int discountPercent) {
        if (cost <= 0) return 0;
        int percent = vip ? Math.clamp(discountPercent, 0, 100) : 0;
        long amount = ((long) cost * (100 - percent) + 99) / 100;
        return (int) Math.max(MINIMUM, amount);
    }
}
