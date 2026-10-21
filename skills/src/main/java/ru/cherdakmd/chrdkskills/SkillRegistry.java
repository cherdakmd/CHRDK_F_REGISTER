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
 * <p>Всего 10 навыков по 6 перков. Уровни перков — 10, 25, 50, 75 и 100
 * (у части навыков два эффекта на последнем рубеже), поэтому прокачка даёт
 * ощутимые ступени, а не сплошную линию процентов.</p>
 *
 * <p>Числа подобраны так, чтобы 100-й уровень был заметно сильнее старта, но
 * не ломал баланс других аддонов: максимум +20 % скорости копания, +15 %
 * ближнего урона и 12 % снижения входящего урона.</p>
 */
public final class SkillRegistry {

    public static final int EXPECTED_SKILLS = 10;
    public static final int PERKS_PER_SKILL = 6;

    private static final Map<SkillType, List<SkillPerk>> PERKS = new EnumMap<>(SkillType.class);

    static {
        // Пороги 10/25/50/75/100; на 100-м уровне у навыка два перка. Числа —
        // прибавки, которые складываются: итог на 100 указан в комментарии
        // каждого навыка, и именно эти суммы считаются потолком.
        perks(SkillType.MINING,
                // копание +20 %, шанс второй руды 30 %
                new SkillPerk("mining-strong-hand", 10, BREAK_SPEED, 0.06),
                new SkillPerk("mining-rich-vein", 25, DOUBLE_DROP, 0.10),
                new SkillPerk("mining-pick-master", 50, BREAK_SPEED, 0.07),
                new SkillPerk("mining-deep-vein", 75, DOUBLE_DROP, 0.10),
                new SkillPerk("mining-legend", 100, BREAK_SPEED, 0.07),
                new SkillPerk("mining-legend-vein", 100, DOUBLE_DROP, 0.10));

        perks(SkillType.WOODCUTTING,
                // рубка +15 %, шанс второго бревна 30 %
                new SkillPerk("wood-steady-axe", 10, BREAK_SPEED, 0.05),
                new SkillPerk("wood-split", 25, DOUBLE_DROP, 0.10),
                new SkillPerk("wood-lumberjack", 50, BREAK_SPEED, 0.05),
                new SkillPerk("wood-grove", 75, DOUBLE_DROP, 0.10),
                new SkillPerk("wood-legend", 100, DOUBLE_DROP, 0.10),
                new SkillPerk("wood-legend-axe", 100, BREAK_SPEED, 0.05));

        perks(SkillType.DIGGING,
                // копание +15 %, шанс второй горсти 30 %
                new SkillPerk("dig-shovel", 10, BREAK_SPEED, 0.05),
                new SkillPerk("dig-extra", 25, DOUBLE_DROP, 0.10),
                new SkillPerk("dig-master", 50, BREAK_SPEED, 0.05),
                new SkillPerk("dig-rich-soil", 75, DOUBLE_DROP, 0.10),
                new SkillPerk("dig-legend", 100, BREAK_SPEED, 0.05),
                new SkillPerk("dig-legend-extra", 100, DOUBLE_DROP, 0.10));

        perks(SkillType.FARMING,
                // урожай ×2 с шансом 40 %, автопосадка с 25-го уровня
                new SkillPerk("farm-thrifty", 10, DOUBLE_DROP, 0.10),
                new SkillPerk("farm-replant", 25, AUTO_REPLANT, 1.0),
                new SkillPerk("farm-harvest", 50, DOUBLE_DROP, 0.10),
                new SkillPerk("farm-master", 75, DOUBLE_DROP, 0.10),
                new SkillPerk("farm-legend", 100, DOUBLE_DROP, 0.10),
                new SkillPerk("farm-legend-replant", 100, AUTO_REPLANT, 1.0));

        perks(SkillType.FISHING,
                // вторая рыба: 40 % суммарного шанса плюс отдельные 10 % на 100-м
                new SkillPerk("fish-extra", 10, EXTRA_CATCH, 0.07),
                new SkillPerk("fish-treasure", 25, EXTRA_CATCH, 0.08),
                new SkillPerk("fish-big-catch", 50, EXTRA_CATCH, 0.08),
                new SkillPerk("fish-master", 75, EXTRA_CATCH, 0.08),
                new SkillPerk("fish-legend", 100, EXTRA_CATCH, 0.09),
                new SkillPerk("fish-legend-treasure", 100, DOUBLE_DROP, 0.10));

        perks(SkillType.COMBAT,
                // урон +15 %, вампиризм 8 % от нанесённого
                new SkillPerk("combat-sharp", 10, MELEE_DAMAGE, 0.04),
                new SkillPerk("combat-leech", 25, LIFESTEAL, 0.03),
                new SkillPerk("combat-master", 50, MELEE_DAMAGE, 0.05),
                new SkillPerk("combat-leech-master", 75, LIFESTEAL, 0.03),
                new SkillPerk("combat-legend", 100, MELEE_DAMAGE, 0.06),
                new SkillPerk("combat-legend-leech", 100, LIFESTEAL, 0.02));

        perks(SkillType.ARCHERY,
                // урон из лука +15 %, возврат стрелы 30 %
                new SkillPerk("archery-aim", 10, ARROW_DAMAGE, 0.05),
                new SkillPerk("archery-quiver", 25, ARROW_KEEP, 0.10),
                new SkillPerk("archery-master", 50, ARROW_DAMAGE, 0.05),
                new SkillPerk("archery-quiver-master", 75, ARROW_KEEP, 0.10),
                new SkillPerk("archery-legend", 100, ARROW_DAMAGE, 0.05),
                new SkillPerk("archery-legend-quiver", 100, ARROW_KEEP, 0.10));

        perks(SkillType.DEFENSE,
                // броня +6, снижение входящего урона 12 %
                new SkillPerk("defense-plates", 10, ARMOR, 2.0),
                new SkillPerk("defense-dodge", 25, DAMAGE_REDUCTION, 0.04),
                new SkillPerk("defense-shield", 50, ARMOR, 2.0),
                new SkillPerk("defense-master", 75, DAMAGE_REDUCTION, 0.04),
                new SkillPerk("defense-legend", 100, DAMAGE_REDUCTION, 0.04),
                new SkillPerk("defense-legend-plates", 100, ARMOR, 2.0));

        perks(SkillType.ALCHEMY,
                // зелья на 30 % дольше, шанс второй порции 20 %
                new SkillPerk("alchemy-long", 10, POTION_DURATION, 0.10),
                new SkillPerk("alchemy-extra", 25, EXTRA_POTION, 0.10),
                new SkillPerk("alchemy-master", 50, POTION_DURATION, 0.10),
                new SkillPerk("alchemy-extra-master", 75, EXTRA_POTION, 0.05),
                new SkillPerk("alchemy-legend", 100, POTION_DURATION, 0.10),
                new SkillPerk("alchemy-legend-extra", 100, EXTRA_POTION, 0.05));

        perks(SkillType.ENCHANTING,
                // шанс поднять чару на 1 — 15 %, возврат уровней — 30 %
                new SkillPerk("enchant-focus", 10, ENCHANT_BONUS, 0.05),
                new SkillPerk("enchant-refund", 25, XP_REFUND, 0.10),
                new SkillPerk("enchant-master", 50, ENCHANT_BONUS, 0.05),
                new SkillPerk("enchant-refund-master", 75, XP_REFUND, 0.10),
                new SkillPerk("enchant-legend", 100, ENCHANT_BONUS, 0.05),
                new SkillPerk("enchant-legend-refund", 100, XP_REFUND, 0.10));
    }

    private SkillRegistry() {
    }

    private static void perks(SkillType skill, SkillPerk... list) {
        PERKS.put(skill, List.of(list));
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
