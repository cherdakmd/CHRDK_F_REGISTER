package ru.cherdakmd.chrdktreasures;

import java.util.List;
import java.util.Random;

/**
 * Держатель содержимого клада: снаружи используется {@link Roll}, а статические
 * {@link #roll} и {@link #empty} — точка входа для слушателей.
 *
 * <p>Сначала решается, именная это находка или ванильная добыча: шанс именного
 * предмета растёт с редкостью (у обычного 45 %, у легендарного 90 %). Если
 * именной предмет выпал, ванильной добычи нет — иначе легендарный клад
 * превращался бы в «клинок плюс алмазы», и ценность предмета размывалась.</p>
 */
public final class TreasureRoll {

    private TreasureRoll() {
    }

    /** Пустой результат — на всякий случай для админских путей. */
    public static Roll empty(TreasureTier tier, TreasureRegion region) {
        return Roll.empty(tier, region);
    }

    /**
     * Бросает содержимое клада.
     *
     * @param itemChance шанс именного предмета 0..1
     */
    public static Roll roll(TreasureTier tier, TreasureRegion region,
                            double itemChance, Random random) {
        return Roll.roll(tier, region, itemChance, random, Long.MAX_VALUE);
    }

    /**
     * Бросает содержимое с учётом прогресса игрока: записи лут-таблицы с
     * порогом выше {@code mined} не выпадают.
     */
    public static Roll roll(TreasureTier tier, TreasureRegion region, double itemChance,
                            Random random, long mined) {
        return Roll.roll(tier, region, itemChance, random, mined);
    }

    /**
     * Бросает содержимое с долей арсенала: часть именных находок берётся из
     * пула, перенесённого из датапака.
     *
     * @param arsenalShare доля арсенала среди именных находок 0..1
     */
    public static Roll roll(TreasureTier tier, TreasureRegion region, double itemChance,
                            Random random, long mined, double arsenalShare) {
        return Roll.roll(tier, region, itemChance, random, mined, arsenalShare);
    }

    /**
     * Содержимое одного клада.
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
        return roll(tier, region, itemChance, random, Long.MAX_VALUE);
    }

    /**
     * @param mined сколько блоков добыл игрок: записи с большим порогом не выпадают
     */
    public static Roll roll(TreasureTier tier, TreasureRegion region,
                            double itemChance, Random random, long mined) {
        return roll(tier, region, itemChance, random, mined, 0.0);
    }

    /**
     * @param arsenalShare доля арсенала среди именных находок 0..1;
     *                     пустой пул арсенала всегда уступает классике
     */
    public static Roll roll(TreasureTier tier, TreasureRegion region, double itemChance,
                            Random random, long mined, double arsenalShare) {
        if (tier == null) {
            return null;
        }
        if (random == null) {
            return empty(tier, region);
        }
        double chance = Math.max(0.0, Math.min(1.0, itemChance));
        if (random.nextDouble() < chance) {
            TreasureItemDef item = pick(tier, region, random, arsenalShare);
            if (item != null) {
                return new Roll(tier, region, item, List.of());
            }
        }
        return new Roll(tier, region, null, TreasureLoot.roll(tier, region, random, mined));
    }

    /**
     * Выбирает именную находку: с вероятностью {@code arsenalShare} — из
     * арсенала, иначе из классического каталога. Пустой пул одной стороны
     * уступает другой, чтобы находка не терялась из-за настройки.
     */
    private static TreasureItemDef pick(TreasureTier tier, TreasureRegion region,
                                        Random random, double arsenalShare) {
        List<TreasureItemDef> classic = tier == TreasureTier.MYTHICAL
                ? TreasureCatalog.mythical()
                : singleton(TreasureCatalog.forRegionTier(region, tier));
        List<TreasureItemDef> arsenal = TreasureArsenal.pool(region, tier);
        double share = Double.isFinite(arsenalShare)
                ? Math.max(0.0, Math.min(1.0, arsenalShare)) : 0.0;
        boolean preferArsenal = !arsenal.isEmpty()
                && (classic.isEmpty() || random.nextDouble() < share);
        List<TreasureItemDef> pool = preferArsenal ? arsenal : classic;
        if (pool.isEmpty()) {
            pool = preferArsenal ? classic : arsenal;
        }
        if (pool.isEmpty()) {
            return null;
        }
        return pool.get(random.nextInt(pool.size()));
    }

    private static List<TreasureItemDef> singleton(TreasureItemDef def) {
        return def == null ? List.of() : List.of(def);
    }
}
}
