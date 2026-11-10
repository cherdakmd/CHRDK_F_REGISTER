package ru.cherdakmd.chrdkregister;

/**
 * Потолок полной цены наковальни и альтернативная оплата алмазами с {@code /bal}.
 *
 * <p>Core снимает ванильное «Слишком дорого» от 40 уровней, чтобы кастомные
 * книги чар, Кузня и тиры списывали настоящую цену. Побочный эффект: обычная
 * ванильная ковка предмета с накопленным штрафом предыдущих работ (Purpur
 * {@code cumulative-cost} удваивает {@code RepairCost}) доходит до 10–16 тысяч
 * уровней. Выше {@code maxLevelPrice} опыт больше не списывается: операцию
 * можно оплатить алмазами с банковского счёта, а если такая оплата выключена —
 * результат скрывается как «слишком дорого».</p>
 *
 * <p>Цена в алмазах считается из ВСЕЙ полной цены, а не только из превышения:
 * иначе операция на 151 уровень стоила бы один алмаз и была бы дешевле операции
 * на 150 уровней. Потолок {@code maxDiamondPrice} не даёт старым предметам с
 * огромным {@code RepairCost} превратить альтернативу в такой же недостижимый
 * счёт. Накопленный штраф на самих предметах не переписывается.</p>
 */
public record AnvilSettings(int maxLevelPrice, int levelsPerDiamond,
                            int maxDiamondPrice, boolean currencyPayment) {

    /** 150 уровней, 10 уровней за алмаз, не дороже 64 алмазов. */
    public static final AnvilSettings DEFAULTS = new AnvilSettings(150, 10, 64, true);

    /** Для юнит-тестов старого контракта: без потолка и без валютной ветки. */
    public static final AnvilSettings UNLIMITED = new AnvilSettings(Integer.MAX_VALUE, 10, 64, false);

    public AnvilSettings {
        maxLevelPrice = Math.max(40, maxLevelPrice);
        levelsPerDiamond = Math.max(1, Math.min(1_000, levelsPerDiamond));
        maxDiamondPrice = Math.max(1, Math.min(10_000, maxDiamondPrice));
    }

    /** Цена выше потолка: уровни не списываются. */
    public boolean aboveLimit(int fullPrice) {
        return fullPrice > maxLevelPrice;
    }

    /**
     * {@code 0} — операция оплачивается уровнями; иначе число алмазов с
     * {@code /bal} за всю операцию. Всегда положительное и не больше потолка.
     */
    public long diamonds(int fullPrice) {
        if (!currencyPayment || !aboveLimit(fullPrice)) {
            return 0L;
        }
        long raw = Math.max(1L, ((long) fullPrice + levelsPerDiamond - 1L) / levelsPerDiamond);
        return Math.min(maxDiamondPrice, raw);
    }
}
