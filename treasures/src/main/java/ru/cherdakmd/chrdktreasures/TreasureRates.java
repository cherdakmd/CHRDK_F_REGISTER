package ru.cherdakmd.chrdktreasures;

import java.util.Map;
import java.util.Random;

/**
 * Математика шансов: какой клад выпадет за блок.
 *
 * <p>Бросок идёт от самой редкой редкости к обычной. Если бы обычный клад
 * проверялся первым, его высокий шанс «съедал» бы окно легендарного; обратный
 * порядок даёт честные вероятности: мифический сначала, обычный — в конце.</p>
 *
 * <p>Множители:</p>
 * <ul>
 *   <li>блок — руда ценнее камня ({@code blocks.<материал>});</li>
 *   <li>глубина — каждый блок ниже нуля добавляет долю к шансу, но не больше
 *       потолка: шахта на −60 выгоднее, но не в разы;</li>
 *   <li>прогрессия — пока добыто меньше {@link TreasureTier#minMined()},
 *       редкость не выпадает вовсе.</li>
 * </ul>
 *
 * <p>Класс чистый: случай передаётся снаружи, шансы — картой из конфига.</p>
 */
public final class TreasureRates {

    private TreasureRates() {
    }

    /**
     * Итоговый шанс редкости на один блок, 0..1.
     *
     * @param chancePercent  базовый шанс из конфига (в процентах)
     * @param blockMultiplier множитель блока
     * @param depthBonus     множитель глубины (1.0 — без бонуса)
     */
    public static double chance(double chancePercent, double blockMultiplier, double depthBonus) {
        double value = Math.max(0.0, chancePercent) / 100.0
                * Math.max(0.0, blockMultiplier)
                * Math.max(0.0, depthBonus);
        return Math.min(1.0, value);
    }

    /**
     * Множитель глубины: ниже нуля каждый блок добавляет {@code perBlock}
     * (доля), но суммарно не больше {@code maxBonus}.
     */
    public static double depthBonus(int y, double perBlock, double maxBonus) {
        if (y >= 0 || perBlock <= 0.0 || maxBonus <= 0.0) {
            return 1.0;
        }
        double bonus = Math.min(maxBonus, (-y) * perBlock);
        return 1.0 + bonus;
    }

    /**
     * Выбирает редкость клада или {@code null}, если в этот раз ничего нет.
     *
     * @param minedBlocks всего добыто игроком (для прогрессии)
     */
    public static TreasureTier pick(Random random, Map<TreasureTier, Double> chancePercent,
                                    double blockMultiplier, double depthBonus, long minedBlocks) {
        if (random == null || chancePercent == null || chancePercent.isEmpty()) {
            return null;
        }
        for (TreasureTier tier : TreasureTier.fromRarest()) {
            if (tier.minMined() > minedBlocks) {
                continue;
            }
            Double base = chancePercent.get(tier);
            if (base == null || base <= 0.0) {
                continue;
            }
            if (random.nextDouble() < chance(base, blockMultiplier, depthBonus)) {
                return tier;
            }
        }
        return null;
    }

    /** Базовые шансы из значений по умолчанию — для конфига и тестов. */
    public static Map<TreasureTier, Double> defaults() {
        Map<TreasureTier, Double> out = new java.util.EnumMap<>(TreasureTier.class);
        for (TreasureTier tier : TreasureTier.values()) {
            out.put(tier, tier.defaultChancePercent());
        }
        return out;
    }
}
