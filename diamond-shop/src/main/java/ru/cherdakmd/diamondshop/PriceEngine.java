package ru.cherdakmd.diamondshop;

/**
 * Динамическое ценообразование магазина (чистая математика — без Bukkit, покрыто тестами).
 *
 * <p>Модель: у каждого товара виртуальный запас {@code stock} и эталон {@code refStock}.
 * Мультипликатор цены = {@code refStock / stock}, зажатый в {@code [minMult, maxMult]}:
 * игроки продают товар магазину → запас растёт → цена падает; скупают → запас падает → цена растёт.
 * К эталону запас возвращается со скоростью {@code recoveryPerHour} от разницы за час.</p>
 */
public final class PriceEngine {

    private PriceEngine() {
    }

    /** Множитель цены для текущего запаса (stock зажат снизу 1, чтобы не делить на 0). */
    public static double multiplier(long stock, double refStock, double minMult, double maxMult) {
        if (refStock <= 0) return 1.0;
        double raw = refStock / Math.max(1, stock);
        return Math.clamp(raw, Math.min(minMult, 1.0), Math.max(maxMult, 1.0));
    }

    /** Цена единицы товара в алмазах, округлённая до 4 знаков. */
    public static double price(double basePrice, long stock, double refStock, double minMult, double maxMult) {
        return round(basePrice * multiplier(stock, refStock, minMult, maxMult));
    }

    /** Округление цен и сумм: 4 знака после запятой. */
    public static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    /**
     * Шаг возврата запаса к эталону за {@code elapsedMillis}: геометрическое
     * приближение (ref - stock) * rate * hours, не более чем до самого эталона.
     */
    public static long recover(long stock, double refStock, double recoveryPerHour, long elapsedMillis) {
        if (recoveryPerHour <= 0 || refStock <= 0) return stock;
        double hours = elapsedMillis / 3_600_000d;
        double gap = refStock - stock;
        long step = Math.round(gap * recoveryPerHour * hours);
        if (step == 0) return stock;
        long next = stock + step;
        // не перелетаем через эталон
        if ((gap > 0) == (refStock - next < 0)) return (long) refStock;
        return next;
    }

    /** Эквивалент товара в алмазах при продаже {@code units} штук по текущей цене. */
    public static double revenue(double pricePerUnit, long units) {
        return round(pricePerUnit * units);
    }
}
