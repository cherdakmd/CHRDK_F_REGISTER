package ru.cherdakmd.chrdkachievements;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * Чистая арифметика достижений: прогресс, выполнение, выбор новых открытий.
 *
 * <p>Класс без Bukkit-типов, чтобы проверяться юнит-тестами: сервис отвечает за
 * события и награды, а «сколько процентов» и «что открылось» решается здесь.</p>
 */
public final class AchievementRules {

    private AchievementRules() {
    }

    /** Прогресс 0..100 (округление вниз), безопасный к отрицательным и большим значениям. */
    public static int percent(long value, long goal) {
        if (goal <= 0) {
            return 0;
        }
        long clamped = Math.max(0L, Math.min(goal, value));
        return (int) (clamped * 100L / goal);
    }

    public static boolean completed(long value, long goal) {
        return goal > 0 && value >= goal;
    }

    /** Счётчик для отображения: не больше порога (иначе «1 200 / 100»). */
    public static long displayValue(long value, long goal) {
        return Math.max(0L, Math.min(Math.max(0L, goal), value));
    }

    /**
     * Достижения, которые выполнились, но ещё не открыты. Порядок — как в каталоге,
     * чтобы уведомления приходили предсказуемо.
     *
     * @param progress значение счётчика по метрике
     */
    public static List<AchievementDef> newlyCompleted(List<AchievementDef> catalogue,
                                                      Set<String> unlocked,
                                                      ToLongFunction<AchievementMetric> progress) {
        if (catalogue == null || progress == null) {
            return List.of();
        }
        List<AchievementDef> out = new ArrayList<>();
        for (AchievementDef def : catalogue) {
            if (unlocked != null && unlocked.contains(def.id())) {
                continue;
            }
            if (completed(progress.applyAsLong(def.metric()), def.goal())) {
                out.add(def);
            }
        }
        return List.copyOf(out);
    }

    /** Полоса прогресса заданной ширины (1..40 сегментов). */
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
