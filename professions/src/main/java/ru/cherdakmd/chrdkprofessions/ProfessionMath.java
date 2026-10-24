package ru.cherdakmd.chrdkprofessions;

/**
 * Кривая уровней и прибавки профессий (1.4.165).
 *
 * <p>Уровень хранится не отдельно, а выводится из накопленного опыта: сумма
 * опыта не может «разъехаться» с уровнем, а смена кривой не требует миграции
 * файла. Потолок — 25-й уровень, дальше опыт копится только для статистики.</p>
 *
 * <p>Все прибавки процентные или штучные и зажаты константами ниже. Это тот же
 * принцип, что у классов и гильдий: профессия экономит материалы и время, но
 * не продаёт силу, поэтому потолок важнее «интересной» формулы.</p>
 */
final class ProfessionMath {

    /** Потолок уровня профессии. */
    static final int MAX_LEVEL = 25;
    /** Опыт на переход с 1-го на 2-й уровень. */
    static final int BASE_XP = 120;
    /** Насколько дороже каждый следующий уровень. */
    static final int STEP_XP = 60;
    /** Потолок скидки на наковальню, %. */
    static final int MAX_DISCOUNT = 40;
    /** Потолок шанса лишней склянки, %. */
    static final int MAX_EXTRA_BOTTLE = 20;
    /** Потолок шанса второго блока, %. */
    static final int MAX_EXTRA_BLOCK = 15;
    /** Потолок прибавки насыщения, очки. */
    static final int MAX_SATURATION = 2;

    /** Сколько опыта нужно, чтобы уйти с уровня {@code level} на следующий. */
    static int needed(int level) {
        int safe = Math.clamp(level, 1, MAX_LEVEL);
        if (safe >= MAX_LEVEL) {
            return 0;
        }
        return BASE_XP + STEP_XP * (safe - 1);
    }

    /** Сколько всего опыта нужно, чтобы достичь уровня {@code level}. */
    static int totalTo(int level) {
        int safe = Math.clamp(level, 1, MAX_LEVEL);
        int total = 0;
        for (int current = 1; current < safe; current++) {
            total += needed(current);
        }
        return total;
    }

    /** Уровень по накопленному опыту: 1..25. */
    static int levelFor(int xp) {
        int rest = Math.max(0, xp);
        int level = 1;
        while (level < MAX_LEVEL && rest >= needed(level)) {
            rest -= needed(level);
            level++;
        }
        return level;
    }

    /** Опыт внутри текущего уровня. */
    static int progress(int xp) {
        int rest = Math.max(0, xp);
        int level = 1;
        while (level < MAX_LEVEL && rest >= needed(level)) {
            rest -= needed(level);
            level++;
        }
        return rest;
    }

    /** Сколько опыта осталось до следующего уровня (0 на потолке). */
    static int xpToNext(int xp) {
        int level = levelFor(xp);
        int need = needed(level);
        return need <= 0 ? 0 : Math.max(0, need - progress(xp));
    }

    /** Заполнение полосы уровня в процентах: 0..100. */
    static int progressPercent(int xp) {
        int level = levelFor(xp);
        int need = needed(level);
        if (need <= 0) {
            return 100;
        }
        int percent = (int) Math.round(progress(xp) * 100.0 / need);
        return Math.clamp(percent, 0, 100);
    }

    /** Скидка кузнеца на работу в наковальне, %: 2 за уровень, потолок 40. */
    static int smithDiscount(int level) {
        return Math.clamp(Math.clamp(level, 1, MAX_LEVEL) * 2, 0, MAX_DISCOUNT);
    }

    /** Шанс алхимика получить лишнюю склянку, %: 1 за уровень, потолок 20. */
    static int alchemistChance(int level) {
        return Math.clamp(Math.clamp(level, 1, MAX_LEVEL), 0, MAX_EXTRA_BOTTLE);
    }

    /** Прибавка повара к насыщению: +1 с 10-го уровня, +2 с 20-го. */
    static int cookSaturation(int level) {
        int safe = Math.clamp(level, 1, MAX_LEVEL);
        return Math.clamp(safe / 10, 0, MAX_SATURATION);
    }

    /** Шанс камнереза получить второй блок при плавке, %: 1 за уровень, потолок 15. */
    static int masonChance(int level) {
        return Math.clamp(Math.clamp(level, 1, MAX_LEVEL), 0, MAX_EXTRA_BLOCK);
    }

    /** Величина прибавки профессии на этом уровне (в её собственных единицах). */
    static int perkValue(Profession profession, int level) {
        if (profession == null) {
            return 0;
        }
        return switch (profession) {
            case SMITH -> smithDiscount(level);
            case ALCHEMIST -> alchemistChance(level);
            case COOK -> cookSaturation(level);
            case MASON -> masonChance(level);
        };
    }

    /** Ключ ранга для текстов: новичок, подмастерье, мастер, магистр. */
    static String rankKey(int level) {
        int safe = Math.clamp(level, 1, MAX_LEVEL);
        if (safe >= 20) {
            return "rank.magister";
        }
        if (safe >= 10) {
            return "rank.master";
        }
        if (safe >= 5) {
            return "rank.journeyman";
        }
        return "rank.novice";
    }

    /** Порог ранга в уровнях — для подсказки «сколько до следующего». */
    static int nextRankAt(int level) {
        int safe = Math.clamp(level, 1, MAX_LEVEL);
        if (safe < 5) {
            return 5;
        }
        if (safe < 10) {
            return 10;
        }
        if (safe < 20) {
            return 20;
        }
        return MAX_LEVEL;
    }

    /** Время до кулдауна: «3 дн. 4 ч», «2 ч 5 мин», «12 мин», «30 с». */
    static String clock(long seconds) {
        long safe = Math.max(0L, seconds);
        long days = safe / 86_400;
        long hours = (safe % 86_400) / 3_600;
        long minutes = (safe % 3_600) / 60;
        if (days > 0) {
            return days + " дн." + (hours > 0 ? " " + hours + " ч" : "");
        }
        if (hours > 0) {
            return hours + " ч" + (minutes > 0 ? " " + minutes + " мин" : "");
        }
        return minutes > 0 ? minutes + " мин" : safe + " с";
    }

    private ProfessionMath() {
    }
}
