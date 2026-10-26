package ru.cherdakmd.chrdkclasses;

/**
 * Плюшки донатных рангов в системе классов (1.4.175).
 *
 * <p>Ранг не продаёт силу: он меняет <b>темп</b>. Числа ниже двигают три вещи —
 * насколько быстро растёт уровень класса, насколько низкий навык нужен для
 * узла дерева и как часто можно применять умения. Потолки эффектов
 * ({@link EffectKind#cap()}) и цена дерева (36 очков) остаются общими для всех,
 * поэтому ранг V не даёт прибавок сверх того, что доступно без ранга, — только
 * доходит до них раньше и пересобирает дерево чаще.</p>
 *
 * <p>Таблица живёт в коде, как и деревья талантов: это баланс, а не настройка.
 * Выключатель один — {@code ranks.enabled} в {@code config.yml}.</p>
 *
 * <p>Ступени совпадают с ядром: 1 — Странник, 2 — Следопыт, 3 — Хранитель,
 * 4 — Меценат, 5 — Покровитель. Неизвестная ступень (старое ядро, ранг снят)
 * даёт {@link #NONE}.</p>
 *
 * @param level             ступень ранга, 0 — без ранга
 * @param thresholdDiscount насколько ниже требуемый уровень навыка для узлов
 * @param stepDiscount      насколько меньше уровней навыков нужно на уровень класса
 * @param cooldownFactor    множитель отката умений: 0.80 — на 20 % быстрее
 * @param changeDaysOff     на сколько дней короче кулдаун смены класса
 * @param respecHoursOff    на сколько часов короче кулдаун пересборки дерева
 */
enum RankPerks {

    /** Без ранга и при выключенных плюшках: всё как в базовых правилах. */
    NONE(0, 0, 0, 1.00, 0, 0),
    WANDERER(1, 1, 1, 0.97, 1, 2),
    PATHFINDER(2, 2, 2, 0.94, 2, 4),
    KEEPER(3, 3, 3, 0.90, 3, 6),
    PATRON(4, 4, 4, 0.86, 4, 8),
    FOUNDER(5, 5, 5, 0.80, 5, 12);

    /** Верхняя ступень лестницы: больше рангов в ядре нет. */
    static final int MAX_RANK = 5;
    /** Нижняя граница шага уровней — та же, что в конфиге. */
    static final int MIN_LEVEL_STEP = ClassConfig.MIN_LEVELS_PER_LEVEL;
    /** Самый сильный мыслимый множитель отката: ниже умение не ускоряется. */
    static final double MIN_COOLDOWN_FACTOR = 0.5;

    private final int level;
    private final int thresholdDiscount;
    private final int stepDiscount;
    private final double cooldownFactor;
    private final int changeDaysOff;
    private final int respecHoursOff;

    RankPerks(int level, int thresholdDiscount, int stepDiscount, double cooldownFactor,
              int changeDaysOff, int respecHoursOff) {
        this.level = level;
        this.thresholdDiscount = thresholdDiscount;
        this.stepDiscount = stepDiscount;
        this.cooldownFactor = cooldownFactor;
        this.changeDaysOff = changeDaysOff;
        this.respecHoursOff = respecHoursOff;
    }

    /** Плюшки ступени; мусор и {@code null}-уровень дают {@link #NONE}. */
    static RankPerks of(int level) {
        RankPerks[] values = values();
        int index = Math.clamp(level, 0, MAX_RANK);
        return values[Math.min(index, values.length - 1)];
    }

    int level() {
        return level;
    }

    /** Требуемый уровень навыка для узла: порог ниже, но не меньше единицы. */
    int skillRequirement(int base) {
        if (base <= 0) {
            return 0;
        }
        return Math.max(1, base - thresholdDiscount);
    }

    /** Шаг уровня класса: сколько уровней навыков нужно на один уровень. */
    int levelStep(int configured) {
        return Math.max(MIN_LEVEL_STEP, configured - stepDiscount);
    }

    /** Кулдаун смены класса в секундах: скидка в днях, но не ниже нуля. */
    long changeCooldownSeconds(long baseSeconds) {
        return Math.max(0L, baseSeconds - changeDaysOff * 86_400L);
    }

    /** Кулдаун пересборки дерева в секундах: скидка в часах, но не ниже нуля. */
    long respecCooldownSeconds(long baseSeconds) {
        return Math.max(0L, baseSeconds - respecHoursOff * 3_600L);
    }

    /** Насколько ниже порог навыка — для текста «порог −N». */
    int thresholdDiscount() {
        return thresholdDiscount;
    }

    /** Насколько мельче шаг уровня — для текста «шаг −N». */
    int levelStepDiscount() {
        return stepDiscount;
    }

    /** Скидка смены класса в днях — для текста. */
    int changeDaysOff() {
        return changeDaysOff;
    }

    /** Скидка пересборки дерева в часах — для текста. */
    int respecHoursOff() {
        return respecHoursOff;
    }

    /** Множитель отката — для текстов и диагностики. */
    double cooldownFactor() {
        return cooldownFactor;
    }
}
