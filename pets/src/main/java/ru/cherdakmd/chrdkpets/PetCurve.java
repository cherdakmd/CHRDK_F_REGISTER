package ru.cherdakmd.chrdkpets;

/**
 * Кривая опыта и уровней питомца.
 *
 * <p>Стоимость следующего уровня растёт линейно:
 * {@code base + growth × (level − 1)}. При базе 40 и росте 25 первый уровень
 * стоит 40 опыта, девятый — 240, а весь путь 1..10 — 1 260 опыта. Линейная
 * кривая выбрана осознанно: питомец — спутник, а не второй навык, и десятый
 * уровень должен браться за несколько вечеров, а не за месяц.</p>
 *
 * <p>Класс чистый (никаких Bukkit-типов) и не хранит состояния: опыт лежит в
 * {@code pets.json} числом, уровень всегда выводится из него. Поэтому смена
 * кривой в конфиге не требует миграции данных — уровни просто пересчитаются.</p>
 */
public final class PetCurve {

    public record Params(int maxLevel, long baseXp, double growth) {

        public Params {
            maxLevel = Math.max(2, Math.min(100, maxLevel));
            baseXp = Math.max(1L, baseXp);
            growth = Math.max(0.0, growth);
        }

        public static final Params DEFAULT = new Params(10, 40L, 25.0);
    }

    private PetCurve() {
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
}
