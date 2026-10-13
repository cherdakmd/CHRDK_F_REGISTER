package ru.cherdakmd.chrdktierforge;

import java.util.List;
import java.util.Map;

/**
 * Кузнечные улучшения (1.4.55): 15 кодовых свойств, докручиваемых тир-предмету
 * на наковальне по шагам (уровень I..V). Чистая логика — Bukkit не нужен,
 * материал оплаты хранится строкой (конфиг может переопределить любой).
 *
 * <p>Материалы по умолчанию намеренно НЕ из рецептов починки (не слитки и не
 * алмазы): улучшение приоритетнее ванильной починки в нашей ветке наковальни,
 * и коллизия убила бы починку тир-предметов материалом.</p>
 */
public final class ForgeUpgrade {

    private ForgeUpgrade() {
    }

    /** На какие предметы ставится улучшение. */
    public enum Target {
        WEAPON, ARMOR, TOOL, ANY;

        /** Подходит ли вид предмета (топор — и оружие, и инструмент: ему можно и то, и то). */
        public boolean matches(ItemKind kind) {
            if (kind == null) {
                return false;
            }
            return switch (this) {
                case WEAPON -> kind.isWeapon();
                case ARMOR -> kind.isArmor();
                case TOOL -> kind.isTool();
                case ANY -> true;
            };
        }
    }

    /** Кодовый эффект уровня. */
    public enum Effect {
        /** +% к урону атаки (атрибут). */
        ATTACK_PERCENT,
        /** +% к скорости атаки (атрибут). */
        ATTACK_SPEED_PERCENT,
        /** % нанесённого урона возвращается в здоровье (событие). */
        LIFESTEAL,
        /** шанс критического удара ×1.5 (событие). */
        CRIT_CHANCE,
        /** +% урона по целям с менее чем 30% здоровья (событие). */
        EXECUTE,
        /** +% опыта с убийств мобов (событие). */
        HUNTER_XP,
        /** +% урона по нежити (событие). */
        SMITE,
        /** +% к броне (атрибут). */
        ARMOR_PERCENT,
        /** +% к скорости передвижения (атрибут). */
        MOVE_SPEED_PERCENT,
        /** +N❤ максимального здоровья (атрибут). */
        MAX_HEALTH,
        /** шанс вернуть нападавшему часть полученного урона (событие). */
        THORNS,
        /** −% входящего урона (событие). */
        DAMAGE_TAKEN_PERCENT,
        /** шанс дублированного дропа руды (событие). */
        ORE_DOUBLE,
        /** +% к скорости добычи (атрибут). */
        DIG_SPEED_PERCENT,
        /** шанс не потратить прочность предмета (событие). */
        DURABILITY_SAVE
    }

    /** Описание одного улучшения. */
    public record Def(String id, String displayName, Target target, Effect effect,
                      double perLevel, int maxLevel, String material) {
    }

    /** 15 улучшений в фиксированном порядке (id — ключи PDC/конфига, не переименовывать). */
    public static final List<Def> ALL = List.of(
            new Def("edge", "Кромка", Target.WEAPON, Effect.ATTACK_PERCENT, 2.0, 5, "QUARTZ"),
            new Def("swing", "Размах", Target.WEAPON, Effect.ATTACK_SPEED_PERCENT, 2.0, 5, "FEATHER"),
            new Def("lifesteal", "Кровопийца", Target.WEAPON, Effect.LIFESTEAL, 2.0, 3, "GHAST_TEAR"),
            new Def("critical", "Крит", Target.WEAPON, Effect.CRIT_CHANCE, 3.0, 5, "RABBIT_FOOT"),
            new Def("executioner", "Добивание", Target.WEAPON, Effect.EXECUTE, 5.0, 3, "BLAZE_POWDER"),
            new Def("hunter", "Следопыт", Target.WEAPON, Effect.HUNTER_XP, 10.0, 5, "LAPIS_LAZULI"),
            new Def("smite", "Кара", Target.WEAPON, Effect.SMITE, 4.0, 5, "BONE"),
            new Def("bulwark", "Булат", Target.ARMOR, Effect.ARMOR_PERCENT, 2.0, 5, "IRON_BLOCK"),
            new Def("lightfoot", "Лёгкость", Target.ARMOR, Effect.MOVE_SPEED_PERCENT, 1.5, 5, "SUGAR"),
            new Def("vitality", "Живучесть", Target.ARMOR, Effect.MAX_HEALTH, 0.5, 4, "GLISTERING_MELON_SLICE"),
            new Def("thornmail", "Терновник", Target.ARMOR, Effect.THORNS, 3.0, 4, "CACTUS"),
            new Def("resilience", "Стойкость", Target.ARMOR, Effect.DAMAGE_TAKEN_PERCENT, 2.0, 4, "OBSIDIAN"),
            new Def("prospector", "Рудачир", Target.TOOL, Effect.ORE_DOUBLE, 6.0, 4, "RAW_GOLD"),
            new Def("quickpick", "Сноровка", Target.TOOL, Effect.DIG_SPEED_PERCENT, 4.0, 5, "REDSTONE"),
            new Def("tempered", "Закалка", Target.ANY, Effect.DURABILITY_SAVE, 5.0, 4, "PRISMARINE_CRYSTALS"));

    /** Ищет улучшение по id (null — нет такого). */
    public static Def byId(String id) {
        if (id == null) {
            return null;
        }
        for (Def def : ALL) {
            if (def.id().equals(id)) {
                return def;
            }
        }
        return null;
    }

    /**
     * Какое улучшение оплачивает материал {@code materialName} для предмета вида
     * {@code kind} (с учётом переопределений конфига). null — материал не кузнечный
     * или предмет не подходит улучшению.
     */
    public static Def forMaterial(String materialName, ItemKind kind, Map<String, String> overrides) {
        if (materialName == null || materialName.isBlank()) {
            return null;
        }
        String wanted = materialName.trim().toUpperCase(java.util.Locale.ROOT);
        for (Def def : ALL) {
            String effective = overrides != null
                    ? overrides.getOrDefault(def.id(), def.material()) : def.material();
            if (wanted.equalsIgnoreCase(effective) && def.target().matches(kind)) {
                return def;
            }
        }
        return null;
    }

    /** Эффективный материал оплаты (дефолт + переопределение конфигом). */
    public static String materialOf(Def def, Map<String, String> overrides) {
        return overrides != null ? overrides.getOrDefault(def.id(), def.material()) : def.material();
    }

    /** Римская цифра уровня (1..10 → I..X; вне диапазона — арабская). */
    public static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> Integer.toString(level);
        };
    }

    /** Строка свойства для лора при заданном суммарном уровне («+6% урона»). */
    public static String describe(Def def, int level) {
        double total = def.perLevel() * level;
        String value = fmt(total);
        return switch (def.effect()) {
            case ATTACK_PERCENT -> "+" + value + "% урона";
            case ATTACK_SPEED_PERCENT -> "+" + value + "% скорости атаки";
            case LIFESTEAL -> "вампиризм " + value + "% урона";
            case CRIT_CHANCE -> "крит ×1.5 — шанс " + value + "%";
            case EXECUTE -> "+" + value + "% урона по целям ниже 30% HP";
            case HUNTER_XP -> "+" + value + "% опыта с мобов";
            case SMITE -> "+" + value + "% урона нежити";
            case ARMOR_PERCENT -> "+" + value + "% брони";
            case MOVE_SPEED_PERCENT -> "+" + value + "% скорости";
            case MAX_HEALTH -> "+" + value + "❤ к здоровью";
            case THORNS -> value + "% шанс вернуть 25% урона";
            case DAMAGE_TAKEN_PERCENT -> "-" + value + "% входящего урона";
            case ORE_DOUBLE -> value + "% шанс дубля руды";
            case DIG_SPEED_PERCENT -> "+" + value + "% скорости добычи";
            case DURABILITY_SAVE -> value + "% шанс сберечь прочность";
        };
    }

    /** Целое без хвоста, иначе один знак после запятой (1.5 → «1.5», 3.0 → «3»). */
    static String fmt(double value) {
        return value == Math.rint(value)
                ? Long.toString((long) value)
                : String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
