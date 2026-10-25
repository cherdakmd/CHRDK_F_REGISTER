package ru.cherdakmd.chrdkclasses;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Каталог классов и деревьев талантов (1.4.164).
 *
 * <p>Все числа здесь, а не в конфиге: дерево — это баланс, и его нельзя
 * менять опечаткой в YAML. Конфиг управляет только темпом (сколько уровней
 * навыков даёт уровень класса, сколько очков за уровень) и кулдаунами.</p>
 *
 * <p>Устройство одинаковое у всех классов: три ветки по пять ярусов. Ветка —
 * это роль внутри стиля: у воина «Стойкость» (броня и здоровье), «Натиск»
 * (урон и скорость) и «Земля» (падения, отбрасывание); у стрелка «Меткость»,
 * «Скорость» и «Выживание»; у мага «Чары», «Щит» и «Поток»; у жреца «Милость»,
 * «Свет» и «Дорога». Верхний узел ветки требует уровня навыка своей темы —
 * поэтому дерево растёт вместе с игрой, а не с датой входа.</p>
 *
 * <p>Пятый ярус — мастерский: он стоит одно очко, но открывается только
 * навыком 75 и предыдущим узлом ветки. Такой узел не «ещё одна ступенька»
 * (очков на все три не хватит: дерево стоит 36, а на потолке уровня доступно
 * 34), а выбор стиля для того, кто дошёл до конца: один мастерский узел
 * помещается рядом с полным деревом, три — уже нет.</p>
 */
public final class ClassCatalog {

    /** Сколько талантов у каждого класса: 3 ветки × 5 ярусов. */
    public static final int TALENTS_PER_CLASS = 15;
    /** Сколько веток у класса. */
    public static final int BRANCHES_PER_CLASS = 3;
    /** Навыки, на которые опираются требования (ид из {@code CHRDK_Skills}). */
    public static final List<String> SKILLS = List.of("mining", "woodcutting", "digging",
            "farming", "fishing", "combat", "archery", "defense", "alchemy", "enchanting");

    /** Пассивная прибавка класса: вид эффекта и величина. */
    public record Effect(EffectKind kind, double value) {

        public Effect {
            kind = kind == null ? EffectKind.MAX_HEALTH : kind;
            value = Math.max(0.0, value);
        }
    }

    private static final Map<String, PlayerClass> CLASSES = new LinkedHashMap<>();
    private static final Map<String, List<Talent>> TALENTS = new LinkedHashMap<>();
    private static final Map<String, Talent> BY_ID = new LinkedHashMap<>();
    private static final Map<String, Map<EffectKind, Double>> PASSIVE = new LinkedHashMap<>();
    private static final Map<String, List<String>> BRANCHES = new LinkedHashMap<>();

    static {
        for (PlayerClass value : PlayerClass.values()) {
            CLASSES.put(value.id(), value);
        }

        passive(PlayerClass.WARRIOR,
                new Effect(EffectKind.MAX_HEALTH, 4),
                new Effect(EffectKind.ARMOR, 2),
                new Effect(EffectKind.MELEE_DAMAGE, 0.06));
        branches(PlayerClass.WARRIOR, "stoyka", "natisk", "zemlya");

        talent("warrior-stoyka-1", PlayerClass.WARRIOR, "stoyka", 1, 1, "",
                "defense", 10, EffectKind.ARMOR, 2);
        talent("warrior-stoyka-2", PlayerClass.WARRIOR, "stoyka", 2, 2, "warrior-stoyka-1",
                "defense", 20, EffectKind.MAX_HEALTH, 2);
        talent("warrior-stoyka-3", PlayerClass.WARRIOR, "stoyka", 3, 3, "warrior-stoyka-2",
                "defense", 35, EffectKind.DAMAGE_REDUCTION, 0.04);
        talent("warrior-stoyka-4", PlayerClass.WARRIOR, "stoyka", 4, 5, "warrior-stoyka-3",
                "defense", 55, EffectKind.MAX_HEALTH, 4);
        talent("warrior-stoyka-5", PlayerClass.WARRIOR, "stoyka", 5, 1, "warrior-stoyka-4",
                "defense", 75, EffectKind.ARMOR, 4);

        talent("warrior-natisk-1", PlayerClass.WARRIOR, "natisk", 1, 1, "",
                "combat", 10, EffectKind.MELEE_DAMAGE, 0.03);
        talent("warrior-natisk-2", PlayerClass.WARRIOR, "natisk", 2, 2, "warrior-natisk-1",
                "combat", 20, EffectKind.MELEE_DAMAGE, 0.04);
        talent("warrior-natisk-3", PlayerClass.WARRIOR, "natisk", 3, 3, "warrior-natisk-2",
                "combat", 35, EffectKind.MOVEMENT_SPEED, 0.02);
        talent("warrior-natisk-4", PlayerClass.WARRIOR, "natisk", 4, 5, "warrior-natisk-3",
                "combat", 55, EffectKind.MELEE_DAMAGE, 0.05);
        talent("warrior-natisk-5", PlayerClass.WARRIOR, "natisk", 5, 1, "warrior-natisk-4",
                "combat", 75, EffectKind.MELEE_DAMAGE, 0.10);

        talent("warrior-zemlya-1", PlayerClass.WARRIOR, "zemlya", 1, 1, "",
                "defense", 10, EffectKind.FALL_REDUCTION, 0.10);
        talent("warrior-zemlya-2", PlayerClass.WARRIOR, "zemlya", 2, 2, "warrior-zemlya-1",
                "defense", 20, EffectKind.KNOCKBACK_RESISTANCE, 0.10);
        talent("warrior-zemlya-3", PlayerClass.WARRIOR, "zemlya", 3, 3, "warrior-zemlya-2",
                "mining", 30, EffectKind.FALL_REDUCTION, 0.15);
        talent("warrior-zemlya-4", PlayerClass.WARRIOR, "zemlya", 4, 5, "warrior-zemlya-3",
                "defense", 45, EffectKind.DAMAGE_REDUCTION, 0.03);
        talent("warrior-zemlya-5", PlayerClass.WARRIOR, "zemlya", 5, 1, "warrior-zemlya-4",
                "mining", 75, EffectKind.KNOCKBACK_RESISTANCE, 0.20);

        passive(PlayerClass.ARCHER,
                new Effect(EffectKind.ARROW_DAMAGE, 0.10),
                new Effect(EffectKind.MOVEMENT_SPEED, 0.03),
                new Effect(EffectKind.FALL_REDUCTION, 0.10));
        branches(PlayerClass.ARCHER, "metkost", "skorost", "vyzhivanie");

        talent("archer-metkost-1", PlayerClass.ARCHER, "metkost", 1, 1, "",
                "archery", 10, EffectKind.ARROW_DAMAGE, 0.04);
        talent("archer-metkost-2", PlayerClass.ARCHER, "metkost", 2, 2, "archer-metkost-1",
                "archery", 20, EffectKind.ARROW_DAMAGE, 0.05);
        talent("archer-metkost-3", PlayerClass.ARCHER, "metkost", 3, 3, "archer-metkost-2",
                "archery", 35, EffectKind.ARROW_DAMAGE, 0.06);
        talent("archer-metkost-4", PlayerClass.ARCHER, "metkost", 4, 5, "archer-metkost-3",
                "archery", 55, EffectKind.ARROW_DAMAGE, 0.08);
        talent("archer-metkost-5", PlayerClass.ARCHER, "metkost", 5, 1, "archer-metkost-4",
                "archery", 75, EffectKind.ARROW_DAMAGE, 0.12);

        talent("archer-skorost-1", PlayerClass.ARCHER, "skorost", 1, 1, "",
                "archery", 10, EffectKind.MOVEMENT_SPEED, 0.02);
        talent("archer-skorost-2", PlayerClass.ARCHER, "skorost", 2, 2, "archer-skorost-1",
                "archery", 20, EffectKind.FALL_REDUCTION, 0.15);
        talent("archer-skorost-3", PlayerClass.ARCHER, "skorost", 3, 3, "archer-skorost-2",
                "archery", 35, EffectKind.MOVEMENT_SPEED, 0.02);
        talent("archer-skorost-4", PlayerClass.ARCHER, "skorost", 4, 5, "archer-skorost-3",
                "defense", 45, EffectKind.KNOCKBACK_RESISTANCE, 0.10);
        talent("archer-skorost-5", PlayerClass.ARCHER, "skorost", 5, 1, "archer-skorost-4",
                "archery", 75, EffectKind.MOVEMENT_SPEED, 0.06);

        talent("archer-vyzhivanie-1", PlayerClass.ARCHER, "vyzhivanie", 1, 1, "",
                "defense", 10, EffectKind.REGEN, 1);
        talent("archer-vyzhivanie-2", PlayerClass.ARCHER, "vyzhivanie", 2, 2,
                "archer-vyzhivanie-1", "defense", 20, EffectKind.MAX_HEALTH, 2);
        talent("archer-vyzhivanie-3", PlayerClass.ARCHER, "vyzhivanie", 3, 3,
                "archer-vyzhivanie-2", "defense", 35, EffectKind.DAMAGE_REDUCTION, 0.03);
        talent("archer-vyzhivanie-4", PlayerClass.ARCHER, "vyzhivanie", 4, 5,
                "archer-vyzhivanie-3", "defense", 55, EffectKind.MAX_HEALTH, 4);
        talent("archer-vyzhivanie-5", PlayerClass.ARCHER, "vyzhivanie", 5, 1,
                "archer-vyzhivanie-4", "defense", 75, EffectKind.REGEN, 2);

        passive(PlayerClass.MAGE,
                new Effect(EffectKind.ARMOR, 2),
                new Effect(EffectKind.DAMAGE_REDUCTION, 0.04),
                new Effect(EffectKind.KNOCKBACK_RESISTANCE, 0.05));
        branches(PlayerClass.MAGE, "chary", "shchit", "potok");

        talent("mage-chary-1", PlayerClass.MAGE, "chary", 1, 1, "",
                "enchanting", 10, EffectKind.ARMOR, 1);
        talent("mage-chary-2", PlayerClass.MAGE, "chary", 2, 2, "mage-chary-1",
                "enchanting", 20, EffectKind.KNOCKBACK_RESISTANCE, 0.05);
        talent("mage-chary-3", PlayerClass.MAGE, "chary", 3, 3, "mage-chary-2",
                "enchanting", 35, EffectKind.DAMAGE_REDUCTION, 0.03);
        talent("mage-chary-4", PlayerClass.MAGE, "chary", 4, 5, "mage-chary-3",
                "enchanting", 55, EffectKind.ARMOR, 2);
        talent("mage-chary-5", PlayerClass.MAGE, "chary", 5, 1, "mage-chary-4",
                "enchanting", 75, EffectKind.DAMAGE_REDUCTION, 0.10);

        talent("mage-shchit-1", PlayerClass.MAGE, "shchit", 1, 1, "",
                "defense", 10, EffectKind.MAX_HEALTH, 2);
        talent("mage-shchit-2", PlayerClass.MAGE, "shchit", 2, 2, "mage-shchit-1",
                "defense", 20, EffectKind.DAMAGE_REDUCTION, 0.03);
        talent("mage-shchit-3", PlayerClass.MAGE, "shchit", 3, 3, "mage-shchit-2",
                "defense", 35, EffectKind.MAX_HEALTH, 2);
        talent("mage-shchit-4", PlayerClass.MAGE, "shchit", 4, 5, "mage-shchit-3",
                "defense", 55, EffectKind.DAMAGE_REDUCTION, 0.04);
        talent("mage-shchit-5", PlayerClass.MAGE, "shchit", 5, 1, "mage-shchit-4",
                "defense", 75, EffectKind.MAX_HEALTH, 4);

        talent("mage-potok-1", PlayerClass.MAGE, "potok", 1, 1, "",
                "alchemy", 10, EffectKind.MOVEMENT_SPEED, 0.02);
        talent("mage-potok-2", PlayerClass.MAGE, "potok", 2, 2, "mage-potok-1",
                "alchemy", 20, EffectKind.REGEN, 1);
        talent("mage-potok-3", PlayerClass.MAGE, "potok", 3, 3, "mage-potok-2",
                "alchemy", 35, EffectKind.SATURATION, 2);
        talent("mage-potok-4", PlayerClass.MAGE, "potok", 4, 5, "mage-potok-3",
                "alchemy", 55, EffectKind.MOVEMENT_SPEED, 0.03);
        talent("mage-potok-5", PlayerClass.MAGE, "potok", 5, 1, "mage-potok-4",
                "alchemy", 75, EffectKind.MOVEMENT_SPEED, 0.05);

        passive(PlayerClass.CLERIC,
                new Effect(EffectKind.MAX_HEALTH, 2),
                new Effect(EffectKind.REGEN, 1),
                new Effect(EffectKind.SATURATION, 3));
        branches(PlayerClass.CLERIC, "milost", "svet", "doroga");

        talent("cleric-milost-1", PlayerClass.CLERIC, "milost", 1, 1, "",
                "alchemy", 10, EffectKind.REGEN, 1);
        talent("cleric-milost-2", PlayerClass.CLERIC, "milost", 2, 2, "cleric-milost-1",
                "alchemy", 20, EffectKind.REGEN, 1);
        talent("cleric-milost-3", PlayerClass.CLERIC, "milost", 3, 3, "cleric-milost-2",
                "alchemy", 35, EffectKind.MAX_HEALTH, 2);
        talent("cleric-milost-4", PlayerClass.CLERIC, "milost", 4, 5, "cleric-milost-3",
                "alchemy", 55, EffectKind.REGEN, 1);
        talent("cleric-milost-5", PlayerClass.CLERIC, "milost", 5, 1, "cleric-milost-4",
                "alchemy", 75, EffectKind.SATURATION, 3);

        talent("cleric-svet-1", PlayerClass.CLERIC, "svet", 1, 1, "",
                "defense", 10, EffectKind.MAX_HEALTH, 2);
        talent("cleric-svet-2", PlayerClass.CLERIC, "svet", 2, 2, "cleric-svet-1",
                "defense", 20, EffectKind.ARMOR, 1);
        talent("cleric-svet-3", PlayerClass.CLERIC, "svet", 3, 3, "cleric-svet-2",
                "defense", 35, EffectKind.DAMAGE_REDUCTION, 0.03);
        talent("cleric-svet-4", PlayerClass.CLERIC, "svet", 4, 5, "cleric-svet-3",
                "defense", 55, EffectKind.MAX_HEALTH, 4);
        talent("cleric-svet-5", PlayerClass.CLERIC, "svet", 5, 1, "cleric-svet-4",
                "defense", 75, EffectKind.ARMOR, 5);

        talent("cleric-doroga-1", PlayerClass.CLERIC, "doroga", 1, 1, "",
                "farming", 10, EffectKind.SATURATION, 2);
        talent("cleric-doroga-2", PlayerClass.CLERIC, "doroga", 2, 2, "cleric-doroga-1",
                "farming", 20, EffectKind.MOVEMENT_SPEED, 0.02);
        talent("cleric-doroga-3", PlayerClass.CLERIC, "doroga", 3, 3, "cleric-doroga-2",
                "farming", 35, EffectKind.FALL_REDUCTION, 0.15);
        talent("cleric-doroga-4", PlayerClass.CLERIC, "doroga", 4, 5, "cleric-doroga-3",
                "farming", 55, EffectKind.MOVEMENT_SPEED, 0.03);
        talent("cleric-doroga-5", PlayerClass.CLERIC, "doroga", 5, 1, "cleric-doroga-4",
                "farming", 75, EffectKind.MOVEMENT_SPEED, 0.05);
    }

    private static void passive(PlayerClass playerClass, Effect... effects) {
        Map<EffectKind, Double> map = new EnumMap<>(EffectKind.class);
        for (Effect effect : effects) {
            map.merge(effect.kind(), effect.value(), Double::sum);
        }
        PASSIVE.put(playerClass.id(), Map.copyOf(map));
    }

    private static void branches(PlayerClass playerClass, String... ids) {
        BRANCHES.put(playerClass.id(), List.of(ids));
    }

    private static void talent(String id, PlayerClass playerClass, String branch, int tier,
                               int cost, String requires, String skill, int skillLevel,
                               EffectKind kind, double value) {
        Talent talent = new Talent(id, playerClass.id(), branch, tier, cost, requires,
                skill, skillLevel, kind, value);
        TALENTS.computeIfAbsent(playerClass.id(), key -> new ArrayList<>()).add(talent);
        BY_ID.put(id, talent);
    }

    /* --------------------------------- доступ --------------------------------- */

    /** Класс по иду или {@code null}. */
    public static PlayerClass playerClass(String id) {
        return id == null ? null : CLASSES.get(id.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** Все классы в порядке витрины. */
    public static List<PlayerClass> classes() {
        return List.copyOf(CLASSES.values());
    }

    /** Таланты класса в порядке объявления (ветка за веткой, ярус за ярусом). */
    public static List<Talent> talents(String classId) {
        List<Talent> list = classId == null ? null : TALENTS.get(classId.trim().toLowerCase(java.util.Locale.ROOT));
        return list == null ? List.of() : List.copyOf(list);
    }

    /** Талант по иду или {@code null}. */
    public static Talent talent(String id) {
        return id == null ? null : BY_ID.get(id.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** Пассивные эффекты класса. */
    public static Map<EffectKind, Double> passive(String classId) {
        Map<EffectKind, Double> map = classId == null ? null
                : PASSIVE.get(classId.trim().toLowerCase(java.util.Locale.ROOT));
        return map == null ? Map.of() : map;
    }

    /** Ветки класса в порядке меню. */
    public static List<String> branches(String classId) {
        List<String> list = classId == null ? null
                : BRANCHES.get(classId.trim().toLowerCase(java.util.Locale.ROOT));
        return list == null ? List.of() : list;
    }

    /** Таланты одной ветки по ярусам. */
    public static List<Talent> branch(String classId, String branch) {
        List<Talent> out = new ArrayList<>();
        for (Talent talent : talents(classId)) {
            if (talent.branch().equals(branch)) {
                out.add(talent);
            }
        }
        out.sort((left, right) -> Integer.compare(left.tier(), right.tier()));
        return out;
    }

    /** Талант того же яруса, но предыдущий — для проверки родителя. */
    public static Talent parentOf(Talent talent) {
        return talent == null || talent.requires().isEmpty() ? null : talent(talent.requires());
    }

    /** Есть ли класс с таким идом. */
    public static boolean exists(String classId) {
        return playerClass(classId) != null;
    }

    private ClassCatalog() {
    }
}
