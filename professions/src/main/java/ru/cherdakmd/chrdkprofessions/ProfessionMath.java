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

    /**
     * Сколько опыта нужно, чтобы уйти с уровня {@code level} на следующий.
     *
     * <p>Потолок приходит снаружи (1.4.167): ранг может поднять его выше
     * {@link #MAX_LEVEL}, и кривая продолжается тем же шагом. Поэтому уровень
     * выше 25 — это не «вторая кривая», а та же, просто длиннее.</p>
     */
    static int needed(int level, int maxLevel) {
        int cap = Math.max(1, maxLevel);
        int safe = Math.clamp(level, 1, cap);
        if (safe >= cap) {
            return 0;
        }
        return BASE_XP + STEP_XP * (safe - 1);
    }

    /** Опыт до следующего уровня при обычном потолке. */
    static int needed(int level) {
        return needed(level, MAX_LEVEL);
    }

    /** Сколько всего опыта нужно, чтобы достичь уровня {@code level}. */
    static int totalTo(int level, int maxLevel) {
        int cap = Math.max(1, maxLevel);
        int safe = Math.clamp(level, 1, cap);
        int total = 0;
        for (int current = 1; current < safe; current++) {
            total += needed(current);
        }
        return total;
    }

    /** Сколько всего опыта нужно, чтобы достичь уровня при обычном потолке. */
    static int totalTo(int level) {
        return totalTo(level, MAX_LEVEL);
    }

    /** Уровень по накопленному опыту: 1..{@code maxLevel}. */
    static int levelFor(int xp, int maxLevel) {
        int cap = Math.max(1, maxLevel);
        int rest = Math.max(0, xp);
        int level = 1;
        while (level < cap && rest >= needed(level, cap)) {
            rest -= needed(level, cap);
            level++;
        }
        return level;
    }

    /** Уровень по опыту при обычном потолке. */
    static int levelFor(int xp) {
        return levelFor(xp, MAX_LEVEL);
    }

    /** Опыт внутри текущего уровня при своём потолке. */
    static int progress(int xp, int maxLevel) {
        int cap = Math.max(1, maxLevel);
        int rest = Math.max(0, xp);
        int level = 1;
        while (level < cap && rest >= needed(level, cap)) {
            rest -= needed(level, cap);
            level++;
        }
        return rest;
    }

    /** Опыт внутри текущего уровня при обычном потолке. */
    static int progress(int xp) {
        return progress(xp, MAX_LEVEL);
    }

    /** Сколько опыта осталось до следующего уровня (0 на потолке). */
    static int xpToNext(int xp) {
        int level = levelFor(xp);
        int need = needed(level);
        return need <= 0 ? 0 : Math.max(0, need - progress(xp));
    }

    /** Заполнение полосы уровня в процентах при своём потолке: 0..100. */
    static int progressPercent(int xp, int maxLevel) {
        int cap = Math.max(1, maxLevel);
        int level = levelFor(xp, cap);
        int need = needed(level, cap);
        if (need <= 0) {
            return 100;
        }
        int percent = (int) Math.round(progress(xp, cap) * 100.0 / need);
        return Math.clamp(percent, 0, 100);
    }

    /** Заполнение полосы уровня в процентах при обычном потолке: 0..100. */
    static int progressPercent(int xp) {
        return progressPercent(xp, MAX_LEVEL);
    }

    /**
     * Потолок прибавки с учётом дополнительных уровней навыка (1.4.167).
     *
     * <p>Ранг поднимает не только уровень профессии, но и потолок её прибавки:
     * иначе опыт сверх 25-го уровня ничего бы не давал, и «прокачать навык
     * сверх обычного» было бы обещанием без товара.</p>
     *
     * @param base     обычный потолок прибавки
     * @param perLevel сколько прибавки даёт один уровень
     * @param extra    дополнительные уровни навыка от ранга
     */
    static int raisedCap(int base, int perLevel, int extra) {
        return base + Math.max(0, extra) * Math.max(0, perLevel);
    }

    /** Скидка кузнеца на работу в наковальне, %: 2 за уровень, потолок 40 (+ за уровни ранга). */
    static int smithDiscount(int level, int maxLevel) {
        int cap = raisedCap(MAX_DISCOUNT, 2, Math.max(0, maxLevel - MAX_LEVEL));
        return Math.clamp(Math.clamp(level, 1, Math.max(1, maxLevel)) * 2, 0, cap);
    }

    static int smithDiscount(int level) {
        return smithDiscount(level, MAX_LEVEL);
    }

    /** Шанс алхимика получить лишнюю склянку, %: 1 за уровень, потолок 20 (+ за уровни ранга). */
    static int alchemistChance(int level, int maxLevel) {
        int cap = raisedCap(MAX_EXTRA_BOTTLE, 1, Math.max(0, maxLevel - MAX_LEVEL));
        return Math.clamp(Math.clamp(level, 1, Math.max(1, maxLevel)), 0, cap);
    }

    static int alchemistChance(int level) {
        return alchemistChance(level, MAX_LEVEL);
    }

    /** Пороги прибавки повара: +1 с 5-го, +2 с 15-го, +3 с 30-го (только с рангом). */
    private static final int[] COOK_STEPS = {5, 15, 30};

    /**
     * Прибавка повара к насыщению; с 1.4.178 пороги ниже.
     *
     * <p>Раньше первый шаг был на 10-м уровне: до него прибавка равнялась нулю, и
     * повар честно не понимал, зачем выбрал ремесло. Теперь +1 уже с 5-го, +2 с
     * 15-го, а третий шаг остаётся наградой за 30-й уровень, который открывает
     * только ранг V. Потолок прежний: +2 без ранга.</p>
     */
    static int cookSaturation(int level, int maxLevel) {
        int safe = Math.clamp(level, 1, Math.max(1, maxLevel));
        int bonus = 0;
        for (int step : COOK_STEPS) {
            if (safe >= step) {
                bonus++;
            }
        }
        int cap = MAX_SATURATION + Math.max(0, maxLevel - MAX_LEVEL) / 10;
        return Math.min(bonus, Math.max(MAX_SATURATION, cap));
    }

    static int cookSaturation(int level) {
        return cookSaturation(level, MAX_LEVEL);
    }

    /**
     * Следующий порог насыщения повара (1.4.179).
     *
     * @return уровень следующего шага или {@code 0}, если шагов больше нет или
     *         потолок игрока ниже него
     */
    static int nextCookStep(int level, int maxLevel) {
        int cap = Math.max(1, maxLevel);
        int safe = Math.clamp(level, 1, cap);
        for (int step : COOK_STEPS) {
            if (step > safe && step <= cap) {
                return step;
            }
        }
        return 0;
    }

    static int nextCookStep(int level) {
        return nextCookStep(level, MAX_LEVEL);
    }

    /**
     * Шанс камнереза получить лишний блок при плавке и распиле, %: 1 за уровень,
     * потолок 15 (+ за уровни ранга). С 1.4.177 шанс работает и на камнерезе.
     */
    static int masonChance(int level, int maxLevel) {
        int cap = raisedCap(MAX_EXTRA_BLOCK, 1, Math.max(0, maxLevel - MAX_LEVEL));
        return Math.clamp(Math.clamp(level, 1, Math.max(1, maxLevel)), 0, cap);
    }

    static int masonChance(int level) {
        return masonChance(level, MAX_LEVEL);
    }

    /** Величина прибавки профессии на этом уровне (в её собственных единицах). */
    static int perkValue(Profession profession, int level, int maxLevel) {
        if (profession == null) {
            return 0;
        }
        return switch (profession) {
            case SMITH -> smithDiscount(level, maxLevel);
            case ALCHEMIST -> alchemistChance(level, maxLevel);
            case COOK -> cookSaturation(level, maxLevel);
            case MASON -> masonChance(level, maxLevel);
        };
    }

    static int perkValue(Profession profession, int level) {
        return perkValue(profession, level, MAX_LEVEL);
    }

    /** Потолок уровня профессии с бонусом ранга (1.4.167): 25..35. */
    static int maxLevel(int extraSkillLevels) {
        return MAX_LEVEL + Math.clamp(extraSkillLevels, 0, 10);
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
