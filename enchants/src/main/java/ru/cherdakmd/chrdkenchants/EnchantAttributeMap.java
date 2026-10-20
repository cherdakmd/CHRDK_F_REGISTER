package ru.cherdakmd.chrdkenchants;

import java.util.ArrayList;
import java.util.List;

/**
 * Атрибутная часть чар (1.4.128): какие чары и проклятия меняют характеристики
 * предмета. Чистая таблица без Bukkit — {@link EnchantItems} переводит её в
 * настоящие {@code AttributeModifier}, а тесты проверяют значения и знак.
 *
 * <p>Разделение важное: событийные чары (вампиризм, молнии, спасители) живут в
 * слушателях, а «+8% урона» или «−15% скорости» — это атрибуты. Они складываются
 * с тирами, артефактами и ковкой, потому что у каждой чары свой ключ модификатора
 * с суффиксом уровня.</p>
 */
public final class EnchantAttributeMap {

    /** Куда кладётся модификатор — контекст предмета решает вызывающий. */
    public enum Target {
        /** Урон атаки (оружие в руке). */
        ATTACK_DAMAGE,
        /** Скорость атаки (оружие в руке). */
        ATTACK_SPEED,
        /** Броня (деталь на себе). */
        ARMOR,
        /** Скорость передвижения (ботинки на себе). */
        MOVEMENT_SPEED
    }

    /** Как читать величину: проценты (multiply_scalar_1) или целые единицы. */
    public enum Kind {
        PERCENT,
        FLAT
    }

    /**
     * Один модификатор.
     *
     * @param target  какой атрибут
     * @param kind    проценты или единицы
     * @param amount  величина (у проклятий отрицательная)
     * @param suffix  часть ключа, чтобы уровни не конфликтовали
     */
    public record Modifier(Target target, Kind kind, double amount, String suffix) {
    }

    private EnchantAttributeMap() {
    }

    /**
     * Модификаторы чары. Пустой список — чара событийная: атрибутов у неё нет.
     */
    public static List<Modifier> modifiers(String id, int level) {
        if (id == null || level <= 0) {
            return List.of();
        }
        return switch (id) {
            case "keen_edge" -> percent(Target.ATTACK_DAMAGE, id, level, new int[]{4, 6, 8});
            case "bulwark" -> percent(Target.ARMOR, id, level, new int[]{5, 7, 9});
            case "featherstep" -> percent(Target.MOVEMENT_SPEED, id, level, new int[]{4, 6, 8});
            case "dull_blade" -> percent(Target.ATTACK_DAMAGE, id, level, new int[]{-5, -8, -11});
            case "clumsy_swing" -> percent(Target.ATTACK_SPEED, id, level, new int[]{-8, -12, -16});
            case "thin_padding" -> percent(Target.ARMOR, id, level, new int[]{-4, -6, -8});
            case "heavy_hull" -> percent(Target.MOVEMENT_SPEED, id, level, new int[]{-10, -15, -20});
            default -> List.of();
        };
    }

    /** Есть ли у чары атрибутная часть (для чистки при снятии). */
    public static boolean hasModifiers(String id) {
        return !modifiers(id, 1).isEmpty();
    }

    /** Все атрибуты, которые модуль вообще трогает. */
    public static List<Target> allTargets() {
        List<Target> targets = new ArrayList<>();
        for (Target target : Target.values()) {
            targets.add(target);
        }
        return List.copyOf(targets);
    }

    private static List<Modifier> percent(Target target, String id, int level, int[] table) {
        int value = table[Math.min(Math.max(0, level - 1), table.length - 1)];
        if (value == 0) {
            return List.of();
        }
        return List.of(new Modifier(target, Kind.PERCENT, value / 100.0, id));
    }
}
