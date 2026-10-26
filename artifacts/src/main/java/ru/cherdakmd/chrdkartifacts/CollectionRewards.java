package ru.cherdakmd.chrdkartifacts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Пороги коллекции артефактов: доля открытого каталога (%) → награда.
 *
 * <p>Логика чистая (без Bukkit-типов), чтобы проверяться юнит-тестами:
 * «сколько процентов собрано», «какие пороги уже достигнуты» и «какие ещё не
 * выданы». Выдача самих наград — в {@link CollectionService}.</p>
 *
 * <p>1.4.130: награда состоит из часов ранга и алмазов. Оба поля
 * необязательны: порог с нулевой наградой просто отмечается как полученный.
 * Это осознанно: администратор может оставить порог-веху без материального
 * приза, не ломая расчёт.</p>
 */
public final class CollectionRewards {

    /** Награда за порог: процент открытых артефактов, дни ранга и алмазы. */
    public record Tier(int percent, int passHours, int diamonds) {

        /** Значения клампятся в конструкторе: кривой конфиг не должен ломать прогресс. */
        public Tier {
            percent = Math.max(1, Math.min(100, percent));
            // 1.4.184: награда в часах, одна награда — не больше суток.
            passHours = Math.max(0, Math.min(24, passHours));
            diamonds = Math.max(0, Math.min(576, diamonds));
        }

        /** Пустая награда (только отметка о достижении). */
        public boolean empty() {
            return passHours <= 0 && diamonds <= 0;
        }
    }

    private CollectionRewards() {
    }

    /** Награды по умолчанию — ровно те, что лежат в config.yml. */
    public static List<Tier> defaults() {
        return List.of(new Tier(25, 1, 8), new Tier(50, 2, 16),
                new Tier(75, 3, 24), new Tier(100, 7, 32));
    }

    /**
     * Приводит список к каноническому виду: сортировка по проценту, без дублей.
     * При совпадении процентов побеждает первая запись — так список из конфига
     * не может дважды выдать награду за один и тот же порог.
     */
    public static List<Tier> normalize(Collection<Tier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return List.of();
        }
        List<Tier> sorted = new ArrayList<>(tiers);
        sorted.sort(Comparator.comparingInt(Tier::percent));
        Set<Integer> seen = new LinkedHashSet<>();
        List<Tier> out = new ArrayList<>(sorted.size());
        for (Tier tier : sorted) {
            if (seen.add(tier.percent())) {
                out.add(tier);
            }
        }
        return List.copyOf(out);
    }

    /** Процент собранного каталога (0..100), округление вниз — как в прогрессе GUI. */
    public static int percentOf(int found, int total) {
        if (total <= 0) {
            return 0;
        }
        long percent = (long) Math.max(0, found) * 100L / total;
        return (int) Math.max(0L, Math.min(100L, percent));
    }

    /**
     * Достигнутые, но ещё не выданные пороги (в порядке возрастания).
     *
     * @param tiers   пороги из конфига
     * @param claimed проценты, награда за которые уже выдана
     * @param found   сколько артефактов открыто
     * @param total   размер каталога
     */
    public static List<Tier> claimable(List<Tier> tiers, Set<Integer> claimed,
                                       int found, int total) {
        if (tiers == null || tiers.isEmpty() || total <= 0) {
            return List.of();
        }
        int percent = percentOf(found, total);
        List<Tier> out = new ArrayList<>();
        for (Tier tier : normalize(tiers)) {
            if (tier.percent() > percent) {
                continue;
            }
            if (claimed != null && claimed.contains(tier.percent())) {
                continue;
            }
            out.add(tier);
        }
        return List.copyOf(out);
    }

    /** Полоса прогресса без разметки: 10 сегментов, заполнено слева направо. */
    public static String bar(int percent, int width) {
        int segments = Math.max(1, Math.min(40, width));
        int filled = Math.round(Math.max(0, Math.min(100, percent)) * segments / 100.0f);
        StringBuilder out = new StringBuilder(segments);
        for (int index = 0; index < segments; index++) {
            out.append(index < filled ? '█' : '░');
        }
        return out.toString();
    }
}
