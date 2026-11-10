package ru.cherdakmd.chrdkskills;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static ru.cherdakmd.chrdkskills.SkillPerk.Kind.ARMOR;
import static ru.cherdakmd.chrdkskills.SkillPerk.Kind.ARROW_DAMAGE;
import static ru.cherdakmd.chrdkskills.SkillPerk.Kind.ARROW_KEEP;
import static ru.cherdakmd.chrdkskills.SkillPerk.Kind.AUTO_REPLANT;
import static ru.cherdakmd.chrdkskills.SkillPerk.Kind.BREAK_SPEED;
import static ru.cherdakmd.chrdkskills.SkillPerk.Kind.DAMAGE_REDUCTION;
import static ru.cherdakmd.chrdkskills.SkillPerk.Kind.DOUBLE_DROP;
import static ru.cherdakmd.chrdkskills.SkillPerk.Kind.ENCHANT_BONUS;
import static ru.cherdakmd.chrdkskills.SkillPerk.Kind.EXTRA_CATCH;
import static ru.cherdakmd.chrdkskills.SkillPerk.Kind.EXTRA_POTION;
import static ru.cherdakmd.chrdkskills.SkillPerk.Kind.LIFESTEAL;
import static ru.cherdakmd.chrdkskills.SkillPerk.Kind.MELEE_DAMAGE;
import static ru.cherdakmd.chrdkskills.SkillPerk.Kind.POTION_DURATION;
import static ru.cherdakmd.chrdkskills.SkillPerk.Kind.XP_REFUND;

/**
 * Каталог навыков и их перков.
 *
 * <p>Всего 10 навыков по <b>12 ступеней</b> (120 перков). Пороги 10, 15, 25,
 * 35, 50, 60, 75, 85, 90, 95 и 100: каждый прежний перк разбит на две
 * половинки, поэтому дерево вдвое длиннее, а суммарные эффекты на 100-м
 * уровне <b>не изменились</b> — максимум +20 % скорости копания, +15 %
 * ближнего урона и 12 % снижения входящего урона. Кривая XP, уровни 1..100,
 * вехи наград и активные приёмы не тронуты.</p>
 *
 * <p>Флаг автопосадки по-прежнему впервые открывается на 25-м уровне: первая
 * его ступень осталась на прежнем пороге. Мелкие шаги дают игроку видимый
 * прогресс каждые 10–15 уровней вместо долгого ожидания следующего бонуса.</p>
 */
public final class SkillRegistry {

    public static final int EXPECTED_SKILLS = 10;
    public static final int PERKS_PER_SKILL = 12;
    /** Пороги ступеней без дублей: их видит игрок в карточке навыка. */
    public static final List<Integer> THRESHOLDS = List.of(10, 15, 25, 35, 50, 60, 75, 85, 90, 95, 100);

    private static final Map<SkillType, List<SkillPerk>> PERKS = new EnumMap<>(SkillType.class);

    static {
        perks(SkillType.MINING,
                // 12 ступеней; суммы на 100 ур. те же, что были у 6 перков.
                new SkillPerk("mining-strong-hand", 10, BREAK_SPEED, 0.03),
                new SkillPerk("mining-strong-hand-2", 15, BREAK_SPEED, 0.03),
                new SkillPerk("mining-rich-vein", 25, DOUBLE_DROP, 0.05),
                new SkillPerk("mining-rich-vein-2", 35, DOUBLE_DROP, 0.05),
                new SkillPerk("mining-pick-master", 50, BREAK_SPEED, 0.035),
                new SkillPerk("mining-pick-master-2", 60, BREAK_SPEED, 0.035),
                new SkillPerk("mining-deep-vein", 75, DOUBLE_DROP, 0.05),
                new SkillPerk("mining-deep-vein-2", 85, DOUBLE_DROP, 0.05),
                new SkillPerk("mining-legend", 90, BREAK_SPEED, 0.035),
                new SkillPerk("mining-legend-vein", 95, DOUBLE_DROP, 0.05),
                new SkillPerk("mining-legend-2", 100, BREAK_SPEED, 0.035),
                new SkillPerk("mining-legend-vein-2", 100, DOUBLE_DROP, 0.05));

        perks(SkillType.WOODCUTTING,
                // 12 ступеней; суммы на 100 ур. те же, что были у 6 перков.
                new SkillPerk("wood-steady-axe", 10, BREAK_SPEED, 0.025),
                new SkillPerk("wood-steady-axe-2", 15, BREAK_SPEED, 0.025),
                new SkillPerk("wood-split", 25, DOUBLE_DROP, 0.05),
                new SkillPerk("wood-split-2", 35, DOUBLE_DROP, 0.05),
                new SkillPerk("wood-lumberjack", 50, BREAK_SPEED, 0.025),
                new SkillPerk("wood-lumberjack-2", 60, BREAK_SPEED, 0.025),
                new SkillPerk("wood-grove", 75, DOUBLE_DROP, 0.05),
                new SkillPerk("wood-grove-2", 85, DOUBLE_DROP, 0.05),
                new SkillPerk("wood-legend", 90, DOUBLE_DROP, 0.05),
                new SkillPerk("wood-legend-axe", 95, BREAK_SPEED, 0.025),
                new SkillPerk("wood-legend-2", 100, DOUBLE_DROP, 0.05),
                new SkillPerk("wood-legend-axe-2", 100, BREAK_SPEED, 0.025));

        perks(SkillType.DIGGING,
                // 12 ступеней; суммы на 100 ур. те же, что были у 6 перков.
                new SkillPerk("dig-shovel", 10, BREAK_SPEED, 0.025),
                new SkillPerk("dig-shovel-2", 15, BREAK_SPEED, 0.025),
                new SkillPerk("dig-extra", 25, DOUBLE_DROP, 0.05),
                new SkillPerk("dig-extra-2", 35, DOUBLE_DROP, 0.05),
                new SkillPerk("dig-master", 50, BREAK_SPEED, 0.025),
                new SkillPerk("dig-master-2", 60, BREAK_SPEED, 0.025),
                new SkillPerk("dig-rich-soil", 75, DOUBLE_DROP, 0.05),
                new SkillPerk("dig-rich-soil-2", 85, DOUBLE_DROP, 0.05),
                new SkillPerk("dig-legend", 90, BREAK_SPEED, 0.025),
                new SkillPerk("dig-legend-extra", 95, DOUBLE_DROP, 0.05),
                new SkillPerk("dig-legend-2", 100, BREAK_SPEED, 0.025),
                new SkillPerk("dig-legend-extra-2", 100, DOUBLE_DROP, 0.05));

        perks(SkillType.FARMING,
                // 12 ступеней; суммы на 100 ур. те же, что были у 6 перков.
                new SkillPerk("farm-thrifty", 10, DOUBLE_DROP, 0.05),
                new SkillPerk("farm-thrifty-2", 15, DOUBLE_DROP, 0.05),
                new SkillPerk("farm-replant", 25, AUTO_REPLANT, 0.5),
                new SkillPerk("farm-replant-2", 35, AUTO_REPLANT, 0.5),
                new SkillPerk("farm-harvest", 50, DOUBLE_DROP, 0.05),
                new SkillPerk("farm-harvest-2", 60, DOUBLE_DROP, 0.05),
                new SkillPerk("farm-master", 75, DOUBLE_DROP, 0.05),
                new SkillPerk("farm-master-2", 85, DOUBLE_DROP, 0.05),
                new SkillPerk("farm-legend", 90, DOUBLE_DROP, 0.05),
                new SkillPerk("farm-legend-replant", 95, AUTO_REPLANT, 0.5),
                new SkillPerk("farm-legend-2", 100, DOUBLE_DROP, 0.05),
                new SkillPerk("farm-legend-replant-2", 100, AUTO_REPLANT, 0.5));

        perks(SkillType.FISHING,
                // 12 ступеней; суммы на 100 ур. те же, что были у 6 перков.
                new SkillPerk("fish-extra", 10, EXTRA_CATCH, 0.035),
                new SkillPerk("fish-extra-2", 15, EXTRA_CATCH, 0.035),
                new SkillPerk("fish-treasure", 25, EXTRA_CATCH, 0.04),
                new SkillPerk("fish-treasure-2", 35, EXTRA_CATCH, 0.04),
                new SkillPerk("fish-big-catch", 50, EXTRA_CATCH, 0.04),
                new SkillPerk("fish-big-catch-2", 60, EXTRA_CATCH, 0.04),
                new SkillPerk("fish-master", 75, EXTRA_CATCH, 0.04),
                new SkillPerk("fish-master-2", 85, EXTRA_CATCH, 0.04),
                new SkillPerk("fish-legend", 90, EXTRA_CATCH, 0.045),
                new SkillPerk("fish-legend-treasure", 95, DOUBLE_DROP, 0.05),
                new SkillPerk("fish-legend-2", 100, EXTRA_CATCH, 0.045),
                new SkillPerk("fish-legend-treasure-2", 100, DOUBLE_DROP, 0.05));

        perks(SkillType.COMBAT,
                // 12 ступеней; суммы на 100 ур. те же, что были у 6 перков.
                new SkillPerk("combat-sharp", 10, MELEE_DAMAGE, 0.02),
                new SkillPerk("combat-sharp-2", 15, MELEE_DAMAGE, 0.02),
                new SkillPerk("combat-leech", 25, LIFESTEAL, 0.015),
                new SkillPerk("combat-leech-2", 35, LIFESTEAL, 0.015),
                new SkillPerk("combat-master", 50, MELEE_DAMAGE, 0.025),
                new SkillPerk("combat-master-2", 60, MELEE_DAMAGE, 0.025),
                new SkillPerk("combat-leech-master", 75, LIFESTEAL, 0.015),
                new SkillPerk("combat-leech-master-2", 85, LIFESTEAL, 0.015),
                new SkillPerk("combat-legend", 90, MELEE_DAMAGE, 0.03),
                new SkillPerk("combat-legend-leech", 95, LIFESTEAL, 0.01),
                new SkillPerk("combat-legend-2", 100, MELEE_DAMAGE, 0.03),
                new SkillPerk("combat-legend-leech-2", 100, LIFESTEAL, 0.01));

        perks(SkillType.ARCHERY,
                // 12 ступеней; суммы на 100 ур. те же, что были у 6 перков.
                new SkillPerk("archery-aim", 10, ARROW_DAMAGE, 0.025),
                new SkillPerk("archery-aim-2", 15, ARROW_DAMAGE, 0.025),
                new SkillPerk("archery-quiver", 25, ARROW_KEEP, 0.05),
                new SkillPerk("archery-quiver-2", 35, ARROW_KEEP, 0.05),
                new SkillPerk("archery-master", 50, ARROW_DAMAGE, 0.025),
                new SkillPerk("archery-master-2", 60, ARROW_DAMAGE, 0.025),
                new SkillPerk("archery-quiver-master", 75, ARROW_KEEP, 0.05),
                new SkillPerk("archery-quiver-master-2", 85, ARROW_KEEP, 0.05),
                new SkillPerk("archery-legend", 90, ARROW_DAMAGE, 0.025),
                new SkillPerk("archery-legend-quiver", 95, ARROW_KEEP, 0.05),
                new SkillPerk("archery-legend-2", 100, ARROW_DAMAGE, 0.025),
                new SkillPerk("archery-legend-quiver-2", 100, ARROW_KEEP, 0.05));

        perks(SkillType.DEFENSE,
                // 12 ступеней; суммы на 100 ур. те же, что были у 6 перков.
                new SkillPerk("defense-plates", 10, ARMOR, 1),
                new SkillPerk("defense-plates-2", 15, ARMOR, 1),
                new SkillPerk("defense-dodge", 25, DAMAGE_REDUCTION, 0.02),
                new SkillPerk("defense-dodge-2", 35, DAMAGE_REDUCTION, 0.02),
                new SkillPerk("defense-shield", 50, ARMOR, 1),
                new SkillPerk("defense-shield-2", 60, ARMOR, 1),
                new SkillPerk("defense-master", 75, DAMAGE_REDUCTION, 0.02),
                new SkillPerk("defense-master-2", 85, DAMAGE_REDUCTION, 0.02),
                new SkillPerk("defense-legend", 90, DAMAGE_REDUCTION, 0.02),
                new SkillPerk("defense-legend-plates", 95, ARMOR, 1),
                new SkillPerk("defense-legend-2", 100, DAMAGE_REDUCTION, 0.02),
                new SkillPerk("defense-legend-plates-2", 100, ARMOR, 1));

        perks(SkillType.ALCHEMY,
                // 12 ступеней; суммы на 100 ур. те же, что были у 6 перков.
                new SkillPerk("alchemy-long", 10, POTION_DURATION, 0.05),
                new SkillPerk("alchemy-long-2", 15, POTION_DURATION, 0.05),
                new SkillPerk("alchemy-extra", 25, EXTRA_POTION, 0.05),
                new SkillPerk("alchemy-extra-2", 35, EXTRA_POTION, 0.05),
                new SkillPerk("alchemy-master", 50, POTION_DURATION, 0.05),
                new SkillPerk("alchemy-master-2", 60, POTION_DURATION, 0.05),
                new SkillPerk("alchemy-extra-master", 75, EXTRA_POTION, 0.025),
                new SkillPerk("alchemy-extra-master-2", 85, EXTRA_POTION, 0.025),
                new SkillPerk("alchemy-legend", 90, POTION_DURATION, 0.05),
                new SkillPerk("alchemy-legend-extra", 95, EXTRA_POTION, 0.025),
                new SkillPerk("alchemy-legend-2", 100, POTION_DURATION, 0.05),
                new SkillPerk("alchemy-legend-extra-2", 100, EXTRA_POTION, 0.025));

        perks(SkillType.ENCHANTING,
                // 12 ступеней; суммы на 100 ур. те же, что были у 6 перков.
                new SkillPerk("enchant-focus", 10, ENCHANT_BONUS, 0.025),
                new SkillPerk("enchant-focus-2", 15, ENCHANT_BONUS, 0.025),
                new SkillPerk("enchant-refund", 25, XP_REFUND, 0.05),
                new SkillPerk("enchant-refund-2", 35, XP_REFUND, 0.05),
                new SkillPerk("enchant-master", 50, ENCHANT_BONUS, 0.025),
                new SkillPerk("enchant-master-2", 60, ENCHANT_BONUS, 0.025),
                new SkillPerk("enchant-refund-master", 75, XP_REFUND, 0.05),
                new SkillPerk("enchant-refund-master-2", 85, XP_REFUND, 0.05),
                new SkillPerk("enchant-legend", 90, ENCHANT_BONUS, 0.025),
                new SkillPerk("enchant-legend-refund", 95, XP_REFUND, 0.05),
                new SkillPerk("enchant-legend-2", 100, ENCHANT_BONUS, 0.025),
                new SkillPerk("enchant-legend-refund-2", 100, XP_REFUND, 0.05));
    }

    private SkillRegistry() {
    }

    private static void perks(SkillType skill, SkillPerk... list) {
        PERKS.put(skill, List.of(list));
    }

    /** Пороги одной строкой для подсказок в меню: «10, 15, ..., 100». */
    public static String thresholdLine() {
        StringBuilder out = new StringBuilder();
        for (int threshold : THRESHOLDS) {
            if (out.length() > 0) out.append(", ");
            out.append(threshold);
        }
        return out.toString();
    }

    public static List<SkillPerk> perksOf(SkillType skill) {
        return PERKS.getOrDefault(skill, List.of());
    }

    /** Все навыки в порядке витрины. */
    public static List<SkillType> all() {
        return List.of(SkillType.values());
    }

    public static List<SkillType> byCategory(SkillType.Category category) {
        List<SkillType> out = new ArrayList<>();
        for (SkillType skill : SkillType.values()) {
            if (skill.category() == category) {
                out.add(skill);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public static int size() {
        return SkillType.values().length;
    }
}
