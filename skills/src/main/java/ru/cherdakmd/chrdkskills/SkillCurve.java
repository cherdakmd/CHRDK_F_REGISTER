package ru.cherdakmd.chrdkskills;

import java.util.Arrays;

/**
 * Кривая опыта и уровней.
 *
 * <p>Стоимость следующего уровня растёт линейно:
 * {@code base + growth × (level − 1)}. При базе 100 и росте 25 первый уровень
 * стоит 100 опыта, сотое — 2 575, а весь путь до 100-го — 132 625 опыта.
 * Линейная кривая (а не экспонента) выбрана осознанно: в игре десять навыков,
 * и экспонента превратила бы прокачку в бесконечность уже к 30-му уровню.</p>
 *
 * <p>Класс чистый (никаких Bukkit-типов) и не хранит состояния — все величины
 * считаются из параметров, поэтому конфиг можно менять без миграции данных:
 * опыт хранится числом, а уровень выводится из него.</p>
 */
public final class SkillCurve {

    public record Params(int maxLevel, long baseXp, double growth) {

        public Params {
            maxLevel = Math.max(2, Math.min(500, maxLevel));
            baseXp = Math.max(1L, baseXp);
            growth = Math.max(0.0, growth);
        }

        public static final Params DEFAULT = new Params(100, 100, 25.0);
    }

    private SkillCurve() {
    }

    /** Сколько опыта нужно, чтобы перейти с {@code level} на следующий. */
    public static long xpForNext(int level, Params params) {
        if (level < 1) {
            return params.baseXp();
        }
        return Math.round(params.baseXp() + params.growth() * (level - 1));
    }

    /** Полный опыт, накопленный к началу уровня {@code level} (для 1-го — 0). */
    public static long totalForLevel(int level, Params params) {
        int target = Math.max(1, Math.min(params.maxLevel() + 1, level));
        long total = 0L;
        for (int current = 1; current < target; current++) {
            total += xpForNext(current, params);
        }
        return total;
    }

    /** Уровень по накопленному опыту (1..maxLevel). */
    public static int levelFor(long xp, Params params) {
        if (xp <= 0L) {
            return 1;
        }
        long total = 0L;
        for (int level = 1; level < params.maxLevel(); level++) {
            total += xpForNext(level, params);
            if (xp < total) {
                return level;
            }
        }
        return params.maxLevel();
    }

    /** Опыт внутри текущего уровня: сколько уже набрано и сколько нужно. */
    public static LevelProgress progress(long xp, Params params) {
        int level = levelFor(xp, params);
        long floor = totalForLevel(level, params);
        long into = Math.max(0L, xp - floor);
        long need = level >= params.maxLevel() ? 0L : xpForNext(level, params);
        int percent = level >= params.maxLevel()
                ? 100
                : (need <= 0 ? 0 : (int) Math.min(100L, into * 100L / need));
        return new LevelProgress(level, into, need, percent);
    }

    public record LevelProgress(int level, long into, long need, int percent) {
    }

    /** Опыт, которого не хватает до указанного уровня (0, если уже есть). */
    public static long missingForLevel(long xp, int level, Params params) {
        return Math.max(0L, totalForLevel(level, params) - Math.max(0L, xp));
    }

    /** Полоса прогресса без разметки: 10 сегментов по умолчанию. */
    public static String bar(int percent, int width) {
        int segments = Math.max(1, Math.min(40, width));
        int filled = Math.round(Math.max(0, Math.min(100, percent)) * segments / 100.0f);
        StringBuilder out = new StringBuilder(segments);
        for (int index = 0; index < segments; index++) {
            out.append(index < filled ? '█' : '░');
        }
        return out.toString();
    }

    /** Предрасчёт полного пути: нужен только тестам и диагностике. */
    static long[] table(Params params) {
        long[] totals = new long[params.maxLevel()];
        long sum = 0L;
        for (int level = 1; level <= params.maxLevel(); level++) {
            totals[level - 1] = sum;
            sum += xpForNext(level, params);
        }
        return Arrays.copyOf(totals, totals.length);
    }
}
