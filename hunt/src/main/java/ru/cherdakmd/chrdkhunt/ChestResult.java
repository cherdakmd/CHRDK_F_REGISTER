package ru.cherdakmd.chrdkhunt;

import java.util.List;

/**
 * Итог открытия сундука охотника за один ключ.
 *
 * @param coins    монеты в кошелёк
 * @param shards   осколки семейства
 * @param diamonds алмазы через /bal
 * @param drops    ванильные вещи
 */
public record ChestResult(long coins, int shards, int diamonds, List<HuntDrop> drops) {

    public ChestResult {
        coins = Math.max(0L, coins);
        shards = Math.clamp(shards, 0, HuntChest.MAX_SHARDS);
        diamonds = Math.clamp(diamonds, 0, HuntChest.MAX_DIAMONDS);
        drops = drops == null ? List.of() : List.copyOf(drops);
    }

    /** Сундук оказался пустым (такое бывает только при пустом конфиге). */
    public boolean empty() {
        return coins <= 0L && shards <= 0 && diamonds <= 0 && drops.isEmpty();
    }
}
