package ru.cherdakmd.chrdkdaily;

import java.util.Map;
import java.util.function.LongUnaryOperator;
import ru.cherdakmd.chrdkregister.api.RewardMarketQuote;

/** Quote before the claim checkpoint; no inventory, market or bank mutation. */
final class DailyMoneyQuote {
    private static final Map<String, Long> FALLBACK = Map.ofEntries(
            Map.entry("BAKED_POTATO", 40L), Map.entry("CRYING_OBSIDIAN", 300L),
            Map.entry("DRIED_KELP_BLOCK", 150L), Map.entry("ENCHANTED_GOLDEN_APPLE", 15000L),
            Map.entry("ENDER_EYE", 500L), Map.entry("EXPERIENCE_BOTTLE", 200L),
            Map.entry("FIRE_CHARGE", 100L), Map.entry("GLOW_BERRIES", 25L),
            Map.entry("HONEYCOMB", 50L), Map.entry("IRON_NUGGET", 15L),
            Map.entry("SOUL_TORCH", 20L), Map.entry("SWEET_BERRIES", 20L),
            Map.entry("TURTLE_HELMET", 3500L), Map.entry("TURTLE_SCUTE", 700L));

    static long quote(DailyConfig.DayReward reward, boolean vip, RewardMarketQuote shop, LongUnaryOperator rate) {
        long base = 0;
        for (var item : reward.itemsFor(vip)) {
            int currency = DailyStore.moneyUnit(item.material());
            long value;
            if (currency > 0) value = Math.multiplyExact(Math.multiplyExact((long) currency, item.amount()), 100L);
            else {
                if (shop == null) throw new IllegalStateException("shop quote unavailable");
                var quote = shop.sell(item.material(), item.amount());
                if (quote == null || quote.status() == RewardMarketQuote.Status.UNAVAILABLE)
                    throw new IllegalStateException("shop quote unavailable: " + item.material());
                if (quote.status() == RewardMarketQuote.Status.NOT_BOUGHT) {
                    Long fallback = FALLBACK.get(item.material());
                    if (fallback == null) throw new IllegalStateException("no reward valuation: " + item.material());
                    value = Math.multiplyExact(fallback, item.amount());
                } else value = quote.minor();
            }
            base = Math.addExact(base, value);
        }
        long scaled = rate.applyAsLong(base);
        if (scaled < 0) throw new IllegalStateException("invalid exchange rate");
        return Math.addExact(scaled, Math.multiplyExact(vip ? reward.vipPassHours() : 0, 15_000L));
    }
    private DailyMoneyQuote() { }
}
