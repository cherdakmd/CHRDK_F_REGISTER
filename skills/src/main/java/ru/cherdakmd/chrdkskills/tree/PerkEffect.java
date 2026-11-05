package ru.cherdakmd.chrdkskills.tree;

/**
 * Виды эффектов узла дерева.
 *
 * <p>Политика каркаса — <b>QoL-only</b>: дерево даёт удобство и выбор, но не
 * множители дропа, опыта и скорости добычи. Иначе оно сложилось бы с
 * {@code ranks.bonuses}, перками {@code SkillPerks}, {@code tier-forge} и
 * обойти бы {@code daily-xp-cap} / {@code xp-per-diamond} профессий.</p>
 *
 * <p>Балансовые виды оставлены в перечислении намеренно: они нужны, чтобы
 * валидатор мог <i>явно отвергнуть</i> такую дефиницию, а не просто не знать
 * о ней. {@link PerkNode} бросает исключение при попытке создать узел с
 * {@link #balanceSensitive()} == true.</p>
 */
public enum PerkEffect {

    /** Подсветка жилы / превью соседних блоков. */
    VEIN_PREVIEW(false),
    /** Снижение износа инструмента, доля. */
    TOOL_WEAR_REDUCTION(false),
    /** Сокращение кулдауна активного приёма, доля. */
    COOLDOWN_REDUCTION(false),
    /** Радиус эффекта по площади, блоки. */
    AREA_RADIUS(false),
    /** Дополнительная дальность взаимодействия, блоки. */
    REACH_BONUS(false),
    /** Детализация HUD: что сейчас активно и сколько осталось. */
    HUD_DETAIL(false),
    /** Длительность активного приёма, секунды. */
    ABILITY_DURATION(false),
    /** Отмена платы за сброс дерева (ядро). */
    RESET_FEE_WAIVER(false),

    /** Запрещено: множитель дропа. */
    DROP_MULTIPLIER(true),
    /** Запрещено: множитель опыта. */
    XP_MULTIPLIER(true),
    /** Запрещено: множитель скорости разрушения. */
    BREAK_SPEED_MULTIPLIER(true);

    private final boolean balanceSensitive;

    PerkEffect(boolean balanceSensitive) {
        this.balanceSensitive = balanceSensitive;
    }

    public boolean balanceSensitive() {
        return balanceSensitive;
    }
}
