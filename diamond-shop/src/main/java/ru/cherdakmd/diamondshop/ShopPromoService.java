package ru.cherdakmd.diamondshop;

import java.util.UUID;

/**
 * Ежедневная горячая скупка пяти ресурсов; набор меняется каждые шесть часов UTC.
 *
 * <p>Бонус всегда вычисляется ДО коммита сделки и добавляется к её выплате,
 * а не выдаётся отдельным переводом: WAL, {@link ShopJournal}, дневной оборот
 * и запрет нулевой выплаты остаются единственным источником денег.</p>
 *
 * <p>Главный инвариант: выплата за продажу (база + бонус) никогда не превышает
 * цену покупки того же числа партий. Иначе «купил в магазине → сразу продал»
 * стал бы бесконечным источником алмазов.</p>
 */
final class ShopPromoService {

    /** Решение по конкретной сделке; флаги означают, что разовая награда уже засчитана. */
    record Bonus(long amount, boolean contractPaid, boolean seriesPaid) {
        static Bonus none() { return new Bonus(0, false, false); }

        Bonus withAmount(long value) {
            return new Bonus(Math.max(0, value), contractPaid, seriesPaid);
        }
    }

    private final ShopPromoConfig.Settings settings;
    private final ShopPromoStore store;

    ShopPromoService(ShopPromoConfig.Settings settings, ShopPromoStore store) {
        this.settings = settings == null ? ShopPromoConfig.Settings.disabled() : settings;
        this.store = store;
    }

    boolean enabled() { return settings.active() && store != null; }

    ShopPromoMath.Plan plan(long nowMillis) {
        long day = ShopPromoMath.dayIndex(nowMillis);
        long window = ShopPromoMath.windowIndex(nowMillis);
        return new ShopPromoMath.Plan(ShopPromoMath.Type.HOT_BUYBACK, day,
                ShopPromoMath.featured(settings.pool(), settings.featuredCount(), window));
    }

    String typeLabel(long nowMillis) { return plan(nowMillis).type().label(); }

    String featuredLabel(long nowMillis) {
        return String.join(", ", plan(nowMillis).featured());
    }

    long remaining(UUID player, long nowMillis) {
        if (!enabled()) {
            return 0;
        }
        return Math.max(0, settings.maxBonusPerPlayerPerDay()*100L
                - store.snapshot(player, nowMillis).bonus());
    }

    /**
     * Сколько алмазов добавить к выплате за продажу.
     *
     * @param baseMoney         выплата по обычной котировке (без бонуса)
     * @param askTotal          цена покупки того же объёма — потолок против арбитража
     * @param turnoverHeadroom  сколько ещё алмазов влезает в персональный дневной оборот
     */
    Bonus quote(UUID player, ShopConfig.ShopItem item, long baseMoney, long askTotal,
                long units, long turnoverHeadroom, long nowMillis) {
        if (!enabled() || player == null || item == null || !item.sellEnabled()
                || baseMoney <= 0 || units <= 0) {
            return Bonus.none();
        }
        ShopPromoMath.Plan plan = plan(nowMillis);
        if (plan.featured().isEmpty()) {
            return Bonus.none();
        }
        ShopPromoStore.Snapshot progress = store.snapshot(player, nowMillis);
        long dailyLeft = Math.max(0, settings.maxBonusPerPlayerPerDay()*100L - progress.bonus());
        if (dailyLeft == 0) {
            return Bonus.none();
        }
        String material = item.material();
        if (!plan.featured().contains(material)) {
            return Bonus.none();
        }
        long desired = ShopPromoMath.percentOf(baseMoney, settings.hotBuybackPercent());
        long noArbitrage = Math.max(0, askTotal - baseMoney);
        long amount = ShopPromoMath.cap(desired, noArbitrage, settings.maxBonusPerTrade()*100L,
                dailyLeft, turnoverHeadroom);
        if (amount <= 0) {
            return Bonus.none();
        }
        return new Bonus(amount, false, false);
    }

    /**
     * Вызывается после успешного коммита КАЖДОЙ продажи. Прогресс заказа/серии
     * должен расти и на сделках без бонуса, иначе условие никогда не наберётся.
     */
    void record(UUID player, String material, long units, Bonus bonus, long nowMillis) {
        if (!enabled() || bonus == null) {
            return;
        }
        store.commit(player, nowMillis, material, units, Math.max(0, bonus.amount()),
                bonus.contractPaid(), bonus.seriesPaid());
    }
}
