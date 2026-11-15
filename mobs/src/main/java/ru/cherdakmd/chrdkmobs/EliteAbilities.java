package ru.cherdakmd.chrdkmobs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Способности элитных мобов: чистый перечень без Bukkit.
 *
 * <p>Каждой элите при появлении достаётся случайный набор способностей без
 * повторов: пассивные баффы, опасные касания при ударе и тактические приёмы
 * боя. Идентификаторы ({@link #id()}) хранятся в PDC моба через запятую.</p>
 */
public enum EliteAbilities {

    /** Скорость II. */
    SPEED(Kind.PASSIVE),
    /** Сила I. */
    STRENGTH(Kind.PASSIVE),
    /** Сопротивление I. */
    RESISTANCE(Kind.PASSIVE),
    /** Огнестойкость. */
    FIRE_RESISTANCE(Kind.PASSIVE),
    /** Регенерация I. */
    REGENERATION(Kind.PASSIVE),
    /** Поджог жертвы при ударе. */
    FIRE_TOUCH(Kind.TOUCH),
    /** Яд при ударе. */
    VENOM(Kind.TOUCH),
    /** Замедление при ударе. */
    SLOW_TOUCH(Kind.TOUCH),
    /** Слабость при ударе. */
    WEAKNESS_TOUCH(Kind.TOUCH),
    /** Мощное отбрасывание при ударе. */
    KNOCKBACK(Kind.TOUCH),
    /** Вампиризм: лечение за нанесённый урон. */
    LIFESTEAL(Kind.TOUCH),
    /** Шипы: отражение части урона атакующему. */
    THORNS(Kind.TACTIC),
    /** Призыв подмоги при получении урона. */
    MINIONS(Kind.TACTIC),
    /** Телепорт к жертве при получении урона. */
    BLINK(Kind.TACTIC);

    /** Группа способности: когда она срабатывает. */
    public enum Kind { PASSIVE, TOUCH, TACTIC }

    private final Kind kind;

    EliteAbilities(Kind kind) {
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** Идентификатор для PDC и конфига. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Обратный поиск по идентификатору; неизвестный — {@code null}. */
    public static EliteAbilities byId(String id) {
        if (id == null) {
            return null;
        }
        for (EliteAbilities ability : values()) {
            if (ability.id().equals(id)) {
                return ability;
            }
        }
        return null;
    }

    /**
     * Случайный набор без повторов: от {@code min} до {@code max} (включительно),
     * размер равновероятен. Выход за размеры пула зажимается.
     */
    public static List<EliteAbilities> pick(Random random, int min, int max) {
        return pickFrom(List.of(values()), random, min, max);
    }

    /** Случайный набор из тематического пула (например, варианта моба). */
    public static List<EliteAbilities> pickFrom(List<EliteAbilities> source, Random random, int min, int max) {
        List<EliteAbilities> pool = new ArrayList<>(source);
        Collections.shuffle(pool, random);
        int low = Math.max(0, Math.min(min, pool.size()));
        int high = Math.max(low, Math.min(max, pool.size()));
        int count = low + (high > low ? random.nextInt(high - low + 1) : 0);
        return List.copyOf(pool.subList(0, count));
    }

    /** Набор в строку для PDC (иды через запятую). */
    public static String join(List<EliteAbilities> abilities) {
        StringBuilder out = new StringBuilder();
        for (EliteAbilities ability : abilities) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(ability.id());
        }
        return out.toString();
    }

    /** Набор из строки PDC; пустое и неизвестное игнорируется. */
    public static EnumSet<EliteAbilities> parse(String joined) {
        EnumSet<EliteAbilities> result = EnumSet.noneOf(EliteAbilities.class);
        if (joined == null || joined.isBlank()) {
            return result;
        }
        for (String id : joined.split(",")) {
            EliteAbilities ability = byId(id.trim());
            if (ability != null) {
                result.add(ability);
            }
        }
        return result;
    }
}
