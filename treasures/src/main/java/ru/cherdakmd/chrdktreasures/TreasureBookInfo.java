package ru.cherdakmd.chrdktreasures;

import ru.cherdakmd.chrdkregister.api.ChrdkTreasureBookInfo;
import java.util.Arrays;
import java.util.function.Supplier;

/** Экспорт нормализованного runtime-config, не повторный разбор файла другим аддоном. */
final class TreasureBookInfo implements ChrdkTreasureBookInfo {
    private final Supplier<TreasuresConfig> config;
    TreasureBookInfo(Supplier<TreasuresConfig> config) { this.config = config; }
    @Override public Snapshot snapshot() {
        TreasuresConfig cfg = config.get(); var special = cfg.specials();
        var tiers = Arrays.stream(TreasureTier.values()).map(tier -> new Tier(tier.id(), special.enchantChance(tier), special.enchantLevelCap(tier))).toList();
        var offers = cfg.shards().offers().stream().filter(offer -> offer.kind() == TreasureShardShop.Kind.ENCHANT)
                .map(offer -> new Offer(offer.id(), offer.cost(), offer.level())).toList();
        return new Snapshot(cfg.enabled() && special.enabled() && special.enchantments(), special.curseBookPercent(),
                tiers, cfg.enabled() && cfg.shards().enabled(), offers);
    }
}
