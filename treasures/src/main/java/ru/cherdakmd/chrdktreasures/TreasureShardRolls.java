package ru.cherdakmd.chrdktreasures;

import java.util.Random;

/**
 * Бросок осколков за найденный клад (1.4.166).
 *
 * <p>Чистая логика: шанс и диапазон по редкости, без Bukkit и без профилей.
 * Осколки — «мелочь», которая копится на обменник, поэтому даже обычный клад
 * иногда что-то приносит: пустая находка ощущалась бы обиднее, чем есть.</p>
 */
final class TreasureShardRolls {

    private TreasureShardRolls() {
    }

    /**
     * Сколько осколков даёт клад этой редкости.
     *
     * @return 0 — сегодня без осколков (шанс не сработал или бочка/клад выключены)
     */
    static int roll(TreasuresConfig.Shards settings, TreasureTier tier, Random random) {
        if (settings == null || !settings.enabled() || tier == null) {
            return 0;
        }
        TreasuresConfig.Shards.Range range = settings.range(tier);
        if (range.maxAmount() <= 0) {
            return 0;
        }
        if (range.chancePercent() <= 0.0) {
            return 0;
        }
        if (range.chancePercent() < 100.0
                && random.nextDouble() * 100.0 >= range.chancePercent()) {
            return 0;
        }
        int spread = range.maxAmount() - range.minAmount();
        return range.minAmount() + (spread <= 0 ? 0 : random.nextInt(spread + 1));
    }
}
