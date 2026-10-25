package ru.cherdakmd.chrdkhunt;

import java.util.ArrayList;
import java.util.List;

/**
 * Сундук охотника: что выпадает за один ключ.
 *
 * <p>Монеты и осколки идут в кошелёк, ванильные вещи — в инвентарь (остаток
 * падает под ноги). Алмазы — редкая находка с отдельным шансом: именно этот
 * шанс и суточный потолок обмена держат экономику в узде.</p>
 *
 * @param familyId       чей сундук
 * @param minCoins       минимум монет
 * @param maxCoins       максимум монет
 * @param minShards      минимум осколков своего семейства
 * @param maxShards      максимум осколков
 * @param diamondChance  шанс алмазов в процентах
 * @param minDiamonds    минимум алмазов при срабатывании
 * @param maxDiamonds    максимум алмазов
 * @param loot           ванильные вещи
 */
public record HuntChest(String familyId, int minCoins, int maxCoins, int minShards, int maxShards,
                        double diamondChance, int minDiamonds, int maxDiamonds,
                        List<HuntLoot> loot) {

    /** Потолки сундука: ключ не должен заменять полноценный поход в шахту. */
    public static final int MAX_COINS = 5_000;
    public static final int MAX_SHARDS = 32;
    public static final int MAX_DIAMONDS = 8;

    public HuntChest {
        familyId = HuntFamily.normalize(familyId);
        minCoins = Math.clamp(minCoins, 0, MAX_COINS);
        maxCoins = Math.clamp(maxCoins, minCoins, MAX_COINS);
        minShards = Math.clamp(minShards, 0, MAX_SHARDS);
        maxShards = Math.clamp(maxShards, minShards, MAX_SHARDS);
        diamondChance = Double.isFinite(diamondChance)
                ? Math.clamp(diamondChance, 0.0, 100.0) : 0.0;
        minDiamonds = Math.clamp(minDiamonds, 0, MAX_DIAMONDS);
        maxDiamonds = Math.clamp(maxDiamonds, minDiamonds, MAX_DIAMONDS);
        if (diamondChance <= 0.0) {
            minDiamonds = 0;
            maxDiamonds = 0;
        }
        loot = loot == null ? List.of() : List.copyOf(new ArrayList<>(loot));
    }

    /** Сундук вообще что-то даёт. */
    public boolean usable() {
        return maxCoins > 0 || maxShards > 0 || maxDiamonds > 0 || !loot.isEmpty();
    }

    /** Может выдать алмазы. */
    public boolean givesDiamonds() {
        return maxDiamonds > 0;
    }
}
