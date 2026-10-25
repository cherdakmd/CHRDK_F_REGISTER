package ru.cherdakmd.chrdkhunt;

import java.util.List;

/**
 * Итог убийства моба: сколько монет и осколков уйдёт в кошелёк и что упадёт
 * обычными вещами.
 *
 * @param coins  монеты
 * @param shards осколки семейства
 * @param keys   ключи (0 или 1)
 * @param drops  ванильный дроп
 */
public record KillResult(long coins, int shards, int keys, List<HuntDrop> drops) {

    public KillResult {
        coins = Math.max(0L, coins);
        shards = Math.clamp(shards, 0, MobRule.MAX_SHARDS);
        keys = Math.clamp(keys, 0, 8);
        drops = drops == null ? List.of() : List.copyOf(drops);
    }

    /** Убийство не дало ничего: мобы без награды не шумят в чате. */
    public boolean empty() {
        return coins <= 0L && shards <= 0 && keys <= 0 && drops.isEmpty();
    }
}
