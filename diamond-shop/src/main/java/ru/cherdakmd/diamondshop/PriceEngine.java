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

    /* ------------------------------- спред bid/ask ------------------------------- */

    /** Цена ПОКУПКИ игроком (ask): mid + половина спреда. */
    public static double ask(double midPrice, double spreadPercent) {
        return round(midPrice * (1 + spreadPercent / 200d));
    }

    /** Цена ПРОДАЖИ игроком (bid): mid - половина спреда. */
    public static double bid(double midPrice, double spreadPercent) {
        return round(midPrice * (1 - spreadPercent / 200d));
    }

    /* ------------------------------- спарклайн истории ------------------------------- */

    private static final char[] BARS = {'▁', '▂', '▃', '▄', '▅', '▆', '▇'};

    /**
     * ASCII-спарклайн по точкам истории цен (▁▂▃▅▇…). Равные значения — плоская линия
     * средней высоты, пустая история — пустая строка. До 24 точек — иначе хвост.
     */
    public static String sparkline(double[] values) {
        if (values == null || values.length == 0) return "";
        int from = Math.max(0, values.length - 24);
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = from; i < values.length; i++) {
            min = Math.min(min, values[i]);
            max = Math.max(max, values[i]);
        }
        StringBuilder line = new StringBuilder(values.length - from);
        for (int i = from; i < values.length; i++) {
            int level = max - min < 1e-9 ? 3
                    : (int) Math.round((values[i] - min) / (max - min) * (BARS.length - 1));
            line.append(BARS[Math.clamp(level, 0, BARS.length - 1)]);
        }
        return line.toString();
    }

    /** Символ тренда между двумя последними точками: ▲ рост, ▼ падение, ═ без изменений. */
    public static String trend(double[] values) {
        if (values == null || values.length < 2) return "═";
        double prev = values[values.length - 2];
        double last = values[values.length - 1];
        if (Math.abs(last - prev) < 1e-9) return "═";
        return last > prev ? "▲" : "▼";
    }
}
