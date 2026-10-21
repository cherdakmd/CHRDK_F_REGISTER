package ru.cherdakmd.chrdktreasures;

import java.util.List;
import java.util.Random;

/**
 * Содержимое одного клада.
 *
 * <p>Сначала решается, именная это находка или ванильная добыча: шанс именного
 * предмета растёт с редкостью (у обычного 45 %, у легендарного 90 %). Если
 * именной предмет выпал, ванильной добычи нет — иначе легендарный клад
 * превращался бы в «клинок плюс алмазы», и ценность предмета размывалась.</p>
 *
 * @param tier   редкость
 * @param region регион (у мифического — регион находки, для статистики)
 * @param item   именной предмет или {@code null}
 * @param loot   ванильная добыча (пустая, если выпал предмет)
 */
public record Roll(TreasureTier tier, TreasureRegion region, TreasureItemDef item,
                   List<TreasureLoot.LootStack> loot) {

    public Roll {
        loot = List.copyOf(loot == null ? List.of() : loot);
    }

    public boolean named() {
        return item != null;
    }

    /** Пустой результат — на всякий случай для админских путей. */
    public static Roll empty(TreasureTier tier, TreasureRegion region) {
        return new Roll(tier, region, null, List.of());
    }

    /**
     * Бросает содержимое клада.
     *
     * @param itemChance шанс именного предмета 0..1
     */
    public static Roll roll(TreasureTier tier, TreasureRegion region,
                            double itemChance, Random random) {
        if (tier == null) {
            return null;
        }
        if (random == null) {
            return empty(tier, region);
        }
        double chance = Math.max(0.0, Math.min(1.0, itemChance));
        if (random.nextDouble() < chance) {
            TreasureItemDef item = tier == TreasureTier.MYTHICAL
                    ? pickMythical(random)
                    : TreasureCatalog.forRegionTier(region, tier);
            if (item != null) {
                return new Roll(tier, region, item, List.of());
            }
        }
        return new Roll(tier, region, null, TreasureLoot.roll(tier, region, random));
    }

    private static TreasureItemDef pickMythical(Random random) {
        List<TreasureItemDef> pool = TreasureCatalog.mythical();
        if (pool.isEmpty()) {
            return null;
        }
        return pool.get(random.nextInt(pool.size()));
    }
}
