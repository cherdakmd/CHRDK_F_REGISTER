package ru.cherdakmd.chrdkskills;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Расчёт активных перков: какие эффекты работают на текущих уровнях.
 *
 * <p>Класс чистый — на вход идут уровни навыков, на выходе карта «вид эффекта →
 * суммарная величина». Доли складываются и ограничиваются 100 %: два перка
 * «шанс двойной добычи» не могут дать 110 % шанса. Абсолютные величины
 * (броня) складываются без потолка — их ограничивает сама игра.</p>
 *
 * <p>Отдельно отдаётся список активных перков — его показывает витрина, чтобы
 * игрок видел, что именно сейчас работает, а не только цифры.</p>
 */
public final class SkillPerks {

    /** Активные перки игрока: карта величин и список для отображения. */
    public record Active(Map<SkillPerk.Kind, Double> values, List<SkillPerk> perks) {

        public double value(SkillPerk.Kind kind) {
            return values.getOrDefault(kind, 0.0);
        }

        public boolean has(SkillPerk.Kind kind) {
            return value(kind) > 0.0;
        }

        public boolean flag(SkillPerk.Kind kind) {
            return has(kind);
        }
    }

    private SkillPerks() {
    }

    /**
     * Считает активные перки.
     *
     * @param levels уровни навыков (навык → уровень, 1..max)
     */
    public static Active active(Map<SkillType, Integer> levels) {
        if (levels == null || levels.isEmpty()) {
            return new Active(Map.of(), List.of());
        }
        Map<SkillPerk.Kind, Double> sums = new EnumMap<>(SkillPerk.Kind.class);
        Map<String, SkillPerk> seen = new LinkedHashMap<>();
        for (Map.Entry<SkillType, Integer> entry : levels.entrySet()) {
            int level = entry.getValue() == null ? 1 : entry.getValue();
            for (SkillPerk perk : SkillRegistry.perksOf(entry.getKey())) {
                if (level < perk.level()) {
                    continue;
                }
                sums.merge(perk.kind(), perk.value(), Double::sum);
                // Один и тот же эффект с одинаковым идом не дублируется даже при
                // повторной записи навыка в карте.
                seen.putIfAbsent(perk.id(), perk);
            }
        }
        if (sums.isEmpty()) {
            return new Active(Map.of(), List.of());
        }
        Map<SkillPerk.Kind, Double> clamped = new EnumMap<>(SkillPerk.Kind.class);
        sums.forEach((kind, value) -> clamped.put(kind,
                kind.percent() ? Math.min(1.0, value) : value));
        return new Active(Map.copyOf(clamped), List.copyOf(seen.values()));
    }

    /** Перки, которые откроются на указанном уровне (для витрины). */
    public static List<SkillPerk> perksAt(SkillType skill, int level) {
        if (skill == null) {
            return List.of();
        }
        return SkillRegistry.perksOf(skill).stream()
                .filter(perk -> perk.level() <= level)
                .toList();
    }

    /** Ближайший неоткрытый перк навыка или {@code null}, если всё открыто. */
    public static SkillPerk nextPerk(SkillType skill, int level) {
        if (skill == null) {
            return null;
        }
        for (SkillPerk perk : SkillRegistry.perksOf(skill)) {
            if (perk.level() > level) {
                return perk;
            }
        }
        return null;
    }

    /** Уровни, на которых у навыка появляются перки (по возрастанию, без дублей). */
    public static List<Integer> thresholds(SkillType skill) {
        Set<Integer> levels = new java.util.TreeSet<>();
        for (SkillPerk perk : SkillRegistry.perksOf(skill)) {
            levels.add(perk.level());
        }
        return List.copyOf(levels);
    }

    /** Суммарный уровень всех навыков — «сила» игрока для топа. */
    public static int totalLevel(Map<SkillType, Integer> levels) {
        int total = 0;
        for (SkillType skill : SkillType.values()) {
            total += Math.max(1, levels == null ? 1 : levels.getOrDefault(skill, 1));
        }
        return total;
    }
}
