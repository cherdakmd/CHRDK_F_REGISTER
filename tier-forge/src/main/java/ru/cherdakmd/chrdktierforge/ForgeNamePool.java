package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Random;

/**
 * Пул случайных кличек кованных предметов (1.4.55): 15 имён для оружия,
 * 10 для брони, 10 для инструментов — ровно 35. Имя выдаётся предмету один раз
 * при первом накате тира (опознание, крафт с роллом, дрop, выдача), дальше живёт
 * в PDC и просто перекрашивается под новый тир при перекатах/апгрейдах.
 * Пулы переопределяются конфигом (names.weapons/armor/tools).
 */
public final class ForgeNamePool {

    /** 15 имён оружия. */
    public static final List<String> DEFAULT_WEAPONS = List.of(
            "Гроза степей", "Клык зари", "Тишина", "Жнец", "Звон крови",
            "Северный ветер", "Молот теней", "Плач бури", "Игла судьбы", "Стальной цветок",
            "Огненный кнут", "Клинок рассвета", "Воронья песнь", "Гнев гор", "Последний аргумент");

    /** 10 имён брони. */
    public static final List<String> DEFAULT_ARMOR = List.of(
            "Стена", "Хранитель", "Медвежья шкура", "Панцирь рассвета", "Тень крепости",
            "Оплот", "Крепость", "Латный колокол", "Тихая сталь", "Дедовский заслон");

    /** 10 имён инструментов. */
    public static final List<String> DEFAULT_TOOLS = List.of(
            "Крот", "Землерой", "Горный ключ", "Дедова рука", "Звон руды",
            "Камнетёс", "Голос штольни", "Рудный пёс", "Продавец гор", "Верный друг");

    /** Группа пула по виду предмета (топор → оружие: кличат прежде всего боевое). */
    public enum Group {
        WEAPONS, ARMOR, TOOLS;

        /** Пул для вида предмета; null — вид не кличется. */
        public static Group of(ItemKind kind) {
            if (kind == null) {
                return null;
            }
            if (kind.isWeapon()) {
                return WEAPONS;
            }
            if (kind.isArmor()) {
                return ARMOR;
            }
            return kind.isTool() ? TOOLS : null;
        }
    }

    private final List<String> weapons;
    private final List<String> armor;
    private final List<String> tools;

    public ForgeNamePool(List<String> weapons, List<String> armor, List<String> tools) {
        this.weapons = List.copyOf(weapons);
        this.armor = List.copyOf(armor);
        this.tools = List.copyOf(tools);
    }

    /** Пулы из конфига: пустая/отсутствующая секция = дефолт. */
    public static ForgeNamePool parse(FileConfiguration config) {
        return new ForgeNamePool(
                readList(config, "names.weapons", DEFAULT_WEAPONS),
                readList(config, "names.armor", DEFAULT_ARMOR),
                readList(config, "names.tools", DEFAULT_TOOLS));
    }

    private static List<String> readList(FileConfiguration config, String key, List<String> fallback) {
        List<String> raw = config.getStringList(key);
        List<String> cleaned = raw.stream()
                .filter(line -> line != null && !line.isBlank())
                .map(String::trim)
                .toList();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    /** Случайное имя из подходящего предмету пула (null — пул/вид не подошёл). */
    public String pick(ItemKind kind, Random random) {
        Group group = Group.of(kind);
        if (group == null) {
            return null;
        }
        List<String> pool = switch (group) {
            case WEAPONS -> weapons;
            case ARMOR -> armor;
            case TOOLS -> tools;
        };
        if (pool.isEmpty()) {
            return null;
        }
        return pool.get(random.nextInt(pool.size()));
    }

    /** Всего имён во всех пулах (лог/тесты). */
    public int totalCount() {
        return weapons.size() + armor.size() + tools.size();
    }
}
