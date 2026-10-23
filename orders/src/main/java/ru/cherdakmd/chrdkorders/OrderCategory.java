package ru.cherdakmd.chrdkorders;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Категория заказов: «руда», «урожай», «добыча с мобов» и т.п.
 *
 * <p>Категория задаёт, насколько часто она попадает на доску ({@code weight}),
 * с какой репутации её берут, сколько репутации она даёт и может ли заказ
 * принести дни проходки. Список материалов — из {@code config.yml}.</p>
 *
 * @param id            ид из конфига ({@code ores}, {@code farm} …)
 * @param weight        вес при выборе категории (не проценты, нормализуется)
 * @param minReputation с какой репутации игрок вправе взять такой заказ
 * @param reputation    сколько репутации даёт сдача
 * @param passChance    шанс добавить дни проходки, проценты
 * @param passDays      сколько дней давать при срабатывании шанса
 * @param items         материалы категории
 */
public record OrderCategory(String id, double weight, int minReputation, int reputation,
                            double passChance, int passDays, List<OrderItem> items) {

    /** Больше 30 дней за один заказ не выдаётся: это уже не «спасибо». */
    public static final int MAX_PASS_DAYS = 30;

    public OrderCategory {
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        weight = Math.clamp(weight, 0.0, 1000.0);
        minReputation = Math.clamp(minReputation, 0, 100_000);
        reputation = Math.clamp(reputation, 0, 100);
        passChance = Math.clamp(passChance, 0.0, 100.0);
        passDays = Math.clamp(passDays, 0, MAX_PASS_DAYS);
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Категория годится для выдачи: есть вес и хотя бы один материал. */
    public boolean usable() {
        return weight > 0.0 && !items.isEmpty();
    }

    /** Случайный материал категории; без {@link Random} — первый. */
    public OrderItem pick(Random random) {
        if (items.isEmpty()) {
            return null;
        }
        return random == null ? items.get(0) : items.get(random.nextInt(items.size()));
    }
}
