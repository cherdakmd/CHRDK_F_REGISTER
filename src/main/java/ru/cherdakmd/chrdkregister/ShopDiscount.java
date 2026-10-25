package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Скидка подписчикам Boosty в каталоге товаров.
 *
 * <p>Витрина TradeMC продаёт по фиксированной цене карточки, поэтому «−15 %»
 * на сайте показать нельзя. Честный эквивалент, который целиком считается на
 * сервере: подписчик получает <b>больше дней за ту же цену</b>. Процент и потолок
 * задаются в конфиге, а срок всё равно режется общим потолком каталога
 * ({@code shop.max-total-days}).</p>
 *
 * <p>Что даёт скидка: только удобство и оформление — то же, что продаётся в
 * каталоге. Игровой силы подписка не добавляет.</p>
 *
 * <p>Класс чистый (Bukkit — только для чтения конфига), поэтому арифметика и
 * окно подтверждения проверяются юнит-тестами.</p>
 */
public record ShopDiscount(boolean enabled, int percent, int maxBonusDays) {

    /** Потолок процента: выше — уже не скидка, а второй товар в подарок. */
    public static final int MAX_PERCENT = 50;
    /** Потолок бонусных дней на одну покупку, если не задан свой. */
    public static final int DEFAULT_MAX_BONUS_DAYS = 30;
    /** Максимальное окно подтверждения подписки. */
    public static final int MAX_GRACE_HOURS = 168;

    public static final ShopDiscount DISABLED = new ShopDiscount(false, 0, 0);

    /**
     * Разбор секции {@code shop.boosty-bonus}. Битые значения зажимаются, а не
     * роняют плагин: скидка — приятный бонус, а не критичная настройка.
     */
    public static ShopDiscount from(FileConfiguration config) {
        if (config == null) {
            return DISABLED;
        }
        return of(config.getBoolean("shop.boosty-bonus.enabled", false),
                config.getInt("shop.boosty-bonus.percent", 15),
                config.getInt("shop.boosty-bonus.max-days", DEFAULT_MAX_BONUS_DAYS));
    }

    /**
     * @param enabled включает бонус
     * @param percent процент дней; {@code 0} — выключено, больше {@value #MAX_PERCENT} — срезается
     * @param maxDays потолок бонуса за одну покупку; {@code 0} — без отдельного потолка
     */
    public static ShopDiscount of(boolean enabled, int percent, int maxDays) {
        int safePercent = Math.clamp(percent, 0, MAX_PERCENT);
        int safeMax = Math.clamp(maxDays, 0, 365);
        return new ShopDiscount(enabled && safePercent > 0, safePercent, safeMax);
    }

    /** Скидка реально действует: включена и процент больше нуля. */
    public boolean usable() {
        return enabled && percent > 0;
    }

    /**
     * Сколько бонусных дней добавить к покупке.
     *
     * <p>Округление вверх: подписчик видит ровные «+5 дней» вместо «+4.5».
     * Бонус не может превышать сам срок покупки (иначе короткий товар
     * удваивался бы) и отдельный потолок {@code max-days}.</p>
     *
     * @param baseDays срок товара или заказ {@code shopbuy ... <дней>}
     */
    public int bonusFor(int baseDays) {
        if (!usable() || baseDays <= 0) {
            return 0;
        }
        long raw = ((long) baseDays * percent + 99) / 100; // ceil
        int cap = maxBonusDays > 0 ? Math.min(maxBonusDays, baseDays) : baseDays;
        return (int) Math.min(raw, cap);
    }

    /**
     * Подтверждена ли подписка настолько, чтобы давать скидку.
     *
     * <p>Boosty проверяется фоновой перепроверкой раз в {@code recheck-hours}.
     * Скидка живёт ещё {@code graceHours} после последнего успешного
     * подтверждения: одна пропущенная проверка (сеть, 502) не должна отбирать
     * бонус у честного подписчика. Просроченная подписка перестаёт давать
     * скидку после этого окна, а не мгновенно.</p>
     *
     * @param enabled   {@code boosty.enabled}
     * @param dryRun    {@code boosty.dry-run}: холостой прогон ничего не подтверждает
     * @param confirmedAt момент последнего подтверждения (0 — не подтверждалась)
     * @param nowMillis текущее время
     * @param graceHours окно после подтверждения
     */
    public static boolean confirmed(boolean enabled, boolean dryRun, long confirmedAt,
                                    long nowMillis, int graceHours) {
        if (!enabled || dryRun || confirmedAt <= 0) {
            return false;
        }
        long window = (long) Math.clamp(graceHours, 1, MAX_GRACE_HOURS) * 3_600_000L;
        return nowMillis - confirmedAt <= window;
    }
}
