package ru.cherdakmd.chrdkregister.api;

import java.util.List;

/** Только чтение действующего снимка кладов; не выдаёт книги и не раскрывает данные игроков. */
public interface ChrdkTreasureBookInfo {
    record Tier(String id, double chancePercent, int maxLevel) { }
    record Offer(String id, int shards, int level) { }
    record Snapshot(boolean enabled, int cursePercent, List<Tier> tiers, boolean exchangeEnabled, List<Offer> offers) {
        public Snapshot { tiers = List.copyOf(tiers); offers = List.copyOf(offers); }
    }
    Snapshot snapshot();
}
