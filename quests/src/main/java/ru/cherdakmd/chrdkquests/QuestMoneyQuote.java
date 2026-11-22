package ru.cherdakmd.chrdkquests;

import java.util.Map;
import java.util.function.LongUnaryOperator;
import ru.cherdakmd.chrdkregister.api.RewardMarketQuote;

/** Converts the configured reward before an immutable journal reservation. */
final class QuestMoneyQuote {
    private static final Map<String,Long> FALLBACK=Map.ofEntries(
            Map.entry("BAKED_POTATO",40L),Map.entry("CRYING_OBSIDIAN",300L),
            Map.entry("DRIED_KELP_BLOCK",150L),Map.entry("ENCHANTED_GOLDEN_APPLE",15000L),
            Map.entry("ENDER_EYE",500L),Map.entry("EXPERIENCE_BOTTLE",200L),
            Map.entry("FIRE_CHARGE",100L),Map.entry("GLOW_BERRIES",25L),
            Map.entry("HONEYCOMB",50L),Map.entry("IRON_NUGGET",15L),
            Map.entry("SOUL_TORCH",20L),Map.entry("SWEET_BERRIES",20L),
            Map.entry("TURTLE_HELMET",3500L),Map.entry("TURTLE_SCUTE",700L));
    static long quote(QuestReward reward,RewardMarketQuote shop,LongUnaryOperator rate) {
        long items=0;
        for(var item:reward.items()) {
            if(shop==null)throw new IllegalStateException("shop unavailable");
            var q=shop.sell(item.material().name(),item.amount());
            if(q==null||q.status()==RewardMarketQuote.Status.UNAVAILABLE)throw new IllegalStateException("shop quote unavailable");
            long value;
            if(q.status()==RewardMarketQuote.Status.NOT_BOUGHT) {
                var unit=FALLBACK.get(item.material().name());
                if(unit==null)throw new IllegalStateException("no price for "+item.material());
                value=Math.multiplyExact(unit,item.amount());
            } else value=q.minor();
            items=Math.addExact(items,value);
        }
        long scaled=rate.applyAsLong(items);if(scaled<0)throw new IllegalArgumentException("rate");
        return Math.addExact(Math.addExact(scaled,reward.xp()),Math.multiplyExact((long)reward.reputation(),10));
    }
    private QuestMoneyQuote(){}
}
