package ru.cherdakmd.chrdktierforge;

import java.util.List;
import java.util.Map;

/**
 * Кузнечные улучшения (1.4.55: 15 базовых; 1.4.56: +35 = 50 кодовых свойств),
 * докручиваемых тир-предмету на наковальне по шагам (уровень I..V). Чистая логика —
 * Bukkit не нужен, материал оплаты хранится строкой (конфиг может переопределить любой).
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
        /** +N❤ максимального здоровья (атрибут ADD_NUMBER). */
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
        DURABILITY_SAVE,
        // --- 1.4.56: ещё 35 фишек ---
        /** +N к урону плашкой (атрибут ADD_NUMBER). */
        RAW_DAMAGE,
        /** +N к силе отбрасывания (атрибут ADD_NUMBER). */
        ATTACK_KNOCKBACK,
        /** +N блоков дальности удара (атрибут ADD_NUMBER). */
        REACH,
        /** +доля урона веерной атаки (атрибут ADD_NUMBER 0..1). */
        SWEEP_RATIO,
        /** шанс поджечь цель (событие). */
        IGNITE,
        /** шанс замедлить цель (событие). */
        SLOW_HIT,
        /** шанс отравить цель (событие). */
        POISON_HIT,
        /** шанс ослепить цель (событие). */
        BLIND_HIT,
        /** +% урона по цели с полным здоровьем (событие). */
        FIRST_STRIKE,
        /** +% урона по бронированной цели (событие). */
        ARMOR_PIERCE,
        /** +% урона за каждое потерянное сердце атакующего (событие, с потолком). */
        RAGE,
        /** +доля сопротивления отбросу (атрибут ADD_NUMBER). */
        KB_RESIST,
        /** +N блоков безопасного падения (атрибут ADD_NUMBER). */
        SAFE_FALL,
        /** +N блоков авто-шага (атрибут ADD_NUMBER). */
        STEP_HEIGHT,
        /** +доля шанса не тратить воздух (атрибут ADD_NUMBER). */
        OXYGEN,
        /** +доля скорости плавания (атрибут ADD_NUMBER). */
        SWIM_SPEED,
        /** +% скорости красться (атрибут, скаляр). */
        SNEAK_SPEED,
        /** −% времени горения (атрибут, скаляр с минусом). */
        BURN_TIME,
        /** +доля сопротивления взрывному отбросу (атрибут ADD_NUMBER). */
        EXPLOSION_RESIST,
        /** +N удачи (атрибут ADD_NUMBER: рыбалка/дроп с лут-таблиц). */
        LUCK,
        /** шанс полностью парировать удар (событие). */
        PARRY,
        /** пассивная регенерация пульсом (событие-пульс). */
        REGEN_PULSE,
        /** шанс замедлить нападавшего (событие). */
        CATCHER,
        /** Скорость I при HP ниже 30% с кулдауном (событие). */
        ADRENALINE,
        /** смертельный урон раз в минуту оставляет 1 HP + Сопротивление (событие). */
        LAST_STAND,
        /** +N к скорости добычи в единицах «эффективности» (атрибут ADD_NUMBER). */
        MINING_EFFICIENCY,
        /** +N блоков дальности добычи/кладки (атрибут ADD_NUMBER). */
        BLOCK_REACH,
        /** шанс переплавить дроп руды в слиток (событие). */
        SMELT_ORE,
        /** +% опыта с добычи блоков (событие). */
        BLOCK_XP,
        /** выбивает колонну того же блока над/под целью (событие). */
        ZONE_BREAK,
        /** предмет не выпадает при смерти, возвращается при респауне (событие). */
        SOULBOUND,
        /** % полученного опыта чинит этот предмет (событие). */
        XP_REPAIR,
        /** пассивное ночное зрение (событие-пульс). */
        NIGHT_VISION,
        /** притягивает ближайшие предметы-дропы к владельцу (событие-пульс). */
        MAGNET,
        /** +% к размеру носителя (атрибут, скаляр). */
        SCALE_UP,
        // --- 1.4.57: третья ковка, ещё 35 эффектов ---
        /** шанс громового дозаряда при ударе (событие, +урон и раскат грома). */
        THUNDER,
        /** шанс иссушить цель (событие, Wither). */
        WITHER_HEX,
        /** шанс дублировать один дроп убитого моба (событие). */
        LOOT_DOUBLE,
        /** шанс притянуть цель к нападающему (событие, velocity). */
        HOOK,
        /** +% урона по целям под негативными эффектами (событие). */
        DEBUFF_DAMAGE,
        /** +% урона по игрокам (событие). */
        PVP_DAMAGE,
        /** +% урона по животным (событие). */
        ANIMAL_DAMAGE,
        /** +N к скорости атаки плашкой (атрибут ADD_NUMBER). */
        ATTACK_SPEED_FLAT,
        /** шанс снять с цели случайный положительный эффект (событие). */
        CLEANSE_HIT,
        /** шанс заморозить цель (событие, freezeTicks). */
        FREEZE_HIT,
        /** +N к прочности брони (атрибут ARMOR_TOUGHNESS, ADD_NUMBER). */
        TOUGHNESS_FLAT,
        /** +% к силе прыжка (атрибут, скаляр). */
        JUMP,
        /** −% к гравитации (атрибут, скаляр). */
        GRAVITY_DOWN,
        /** −% к урону падения (атрибут FALL_DAMAGE_MULTIPLIER, скаляр). */
        FALL_DAMAGE_DOWN,
        /** +доля эффективности ходьбы по грунтам (атрибут ADD_NUMBER 0..1). */
        WALK_EFFICIENCY,
        /** шанс получить Поглощение при получении урона (событие). */
        ABSORB_PROC,
        /** шанс получить Сопротивление при получении урона (событие). */
        RESIST_PROC,
        /** −% урона от взрывов (событие, по причине урона). */
        EXPLOSION_DAMAGE_DOWN,
        /** −% урона от магии и дыхания дракона (событие). */
        MAGIC_DAMAGE_DOWN,
        /** полная защита от замерзания (пульс). */
        NO_FREEZE,
        /** восполняет сытность голодающему владельцу (пульс). */
        FEED_PULSE,
        /** шанс отразить снаряд обратно в стрелка (событие). */
        PROJECTILE_REFLECT,
        /** +% длительности положительных эффектов на владельце (событие). */
        GOOD_EFFECTS_LONGER,
        /** −% длительности негативных эффектов на владельце (событие). */
        BAD_EFFECTS_SHORTER,
        /** шанс дубля дропа урожая (событие, BlockDropItemEvent). */
        CROP_DOUBLE,
        /** шанс дубля брёвен (событие, BlockDropItemEvent). */
        LOG_DOUBLE,
        /** шанс поймать второй трофей на рыбалке (событие). */
        FISH_DOUBLE,
        /** Спешка, пока инструмент в руке (пульс). */
        PULSE_HASTE,
        /** шанс найти золотой самородок в камне (событие). */
        GOLD_FIND,
        /** шанс дубля дропа глубже Y=0 (событие). */
        DEEP_DOUBLE,
        /** +% к получаемому опыту из любых источников (событие). */
        XP_GAIN,
        /** предмет чинит сам себя со временем (пульс). */
        PULSE_MEND,
        /** Скорость на тропинках и траве (пульс). */
        PATH_SPEED,
        /** Скорость вне боя — 5 секунд тишины (пульс). */
        ROAM_SPEED,
        /** +% урона стрелам и снарядам владельца (событие). */
        SNIPE
    }

    /** Описание одного улучшения. */
    public record Def(String id, String displayName, Target target, Effect effect,
                      double perLevel, int maxLevel, String material) {
    }

    /** 85 улучшений в фиксированном порядке (id — ключи PDC/конфига, не переименовывать). */
    public static final List<Def> ALL = List.of(
            // --- первая ковка (1.4.55): 15 базовых ---
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
            new Def("tempered", "Закалка", Target.ANY, Effect.DURABILITY_SAVE, 5.0, 4, "PRISMARINE_CRYSTALS"),
            // --- вторая ковка (1.4.56): ещё 35 ---
            // оружие (11)
            new Def("sharpen", "Заточка", Target.WEAPON, Effect.RAW_DAMAGE, 0.25, 5, "IRON_NUGGET"),
            new Def("knockback", "Отброс", Target.WEAPON, Effect.ATTACK_KNOCKBACK, 0.3, 4, "PISTON"),
            new Def("reach", "Досягаемость", Target.WEAPON, Effect.REACH, 0.15, 3, "LEAD"),
            new Def("sweeping", "Веер", Target.WEAPON, Effect.SWEEP_RATIO, 0.1, 4, "PHANTOM_MEMBRANE"),
            new Def("igniter", "Поджигатель", Target.WEAPON, Effect.IGNITE, 15.0, 4, "FLINT_AND_STEEL"),
            new Def("frostbite", "Оледенение", Target.WEAPON, Effect.SLOW_HIT, 10.0, 3, "ICE"),
            new Def("venom", "Жало", Target.WEAPON, Effect.POISON_HIT, 10.0, 3, "POISONOUS_POTATO"),
            new Def("blinder", "Ослепление", Target.WEAPON, Effect.BLIND_HIT, 8.0, 3, "INK_SAC"),
            new Def("ambush", "Внезапный", Target.WEAPON, Effect.FIRST_STRIKE, 6.0, 3, "GLOWSTONE_DUST"),
            new Def("crusher", "Ломатель", Target.WEAPON, Effect.ARMOR_PIERCE, 4.0, 4, "FLINT"),
            new Def("rage", "Ярость", Target.WEAPON, Effect.RAGE, 0.75, 3, "MAGMA_CREAM"),
            // броня (15)
            new Def("unbending", "Несокрушимость", Target.ARMOR, Effect.KB_RESIST, 0.1, 4, "ANCIENT_DEBRIS"),
            new Def("feather", "Пух", Target.ARMOR, Effect.SAFE_FALL, 1.0, 3, "WHITE_WOOL"),
            new Def("giant", "Великан", Target.ARMOR, Effect.STEP_HEIGHT, 0.2, 3, "LADDER"),
            new Def("gills", "Жабры", Target.ARMOR, Effect.OXYGEN, 0.15, 4, "PUFFERFISH"),
            new Def("frogman", "Плавник", Target.ARMOR, Effect.SWIM_SPEED, 0.1, 4, "KELP"),
            new Def("silent", "Тихий ход", Target.ARMOR, Effect.SNEAK_SPEED, 15.0, 4, "RABBIT_HIDE"),
            new Def("asbestos", "Асбест", Target.ARMOR, Effect.BURN_TIME, 15.0, 4, "PRISMARINE_SHARD"),
            new Def("shelter", "Укрытие", Target.ARMOR, Effect.EXPLOSION_RESIST, 0.15, 4, "TNT"),
            new Def("charm", "Талисман", Target.ARMOR, Effect.LUCK, 0.5, 3, "EMERALD"),
            new Def("parry", "Парирование", Target.ARMOR, Effect.PARRY, 4.0, 5, "SHIELD"),
            new Def("warm", "Теплокровность", Target.ARMOR, Effect.REGEN_PULSE, 0.2, 5, "GOLDEN_APPLE"),
            new Def("catcher", "Ловец", Target.ARMOR, Effect.CATCHER, 15.0, 4, "SLIME_BALL"),
            new Def("adrenaline", "Адреналин", Target.ARMOR, Effect.ADRENALINE, 1.0, 4, "COOKIE"),
            new Def("laststand", "Последний рубеж", Target.ARMOR, Effect.LAST_STAND, 1.0, 1, "TOTEM_OF_UNDYING"),
            new Def("colossus", "Исполин", Target.ARMOR, Effect.SCALE_UP, 5.0, 4, "PUMPKIN"),
            // инструменты (5)
            new Def("jeweler", "Ювелир", Target.TOOL, Effect.MINING_EFFICIENCY, 1.0, 5, "GRINDSTONE"),
            new Def("craftsman", "Мастер", Target.TOOL, Effect.BLOCK_REACH, 0.2, 3, "SPYGLASS"),
            new Def("smelter", "Плавильня", Target.TOOL, Effect.SMELT_ORE, 10.0, 4, "FURNACE"),
            new Def("geologist", "Геолог", Target.TOOL, Effect.BLOCK_XP, 15.0, 4, "BOOK"),
            new Def("hewer", "Забойщик", Target.TOOL, Effect.ZONE_BREAK, 1.0, 2, "RAIL"),
            // универсальные (4)
            new Def("soulbind", "Привязка души", Target.ANY, Effect.SOULBOUND, 1.0, 1, "NETHER_STAR"),
            new Def("selfmend", "Самопочинка", Target.ANY, Effect.XP_REPAIR, 5.0, 5, "EXPERIENCE_BOTTLE"),
            new Def("lantern", "Светильник", Target.ANY, Effect.NIGHT_VISION, 1.0, 1, "GLOW_INK_SAC"),
            new Def("magnet", "Магнит", Target.ANY, Effect.MAGNET, 0.5, 5, "COMPASS"),
            // --- третья ковка (1.4.57): ещё 35 ---
            // оружие (10)
            new Def("storm", "Громовержец", Target.WEAPON, Effect.THUNDER, 8.0, 3, "LIGHTNING_ROD"),
            new Def("hex", "Проклятие", Target.WEAPON, Effect.WITHER_HEX, 10.0, 3, "WITHER_ROSE"),
            new Def("reaper", "Жатва", Target.WEAPON, Effect.LOOT_DOUBLE, 10.0, 4, "NAUTILUS_SHELL"),
            new Def("hook", "Крюк", Target.WEAPON, Effect.HOOK, 10.0, 3, "TRIPWIRE_HOOK"),
            new Def("crippler", "Калечащий", Target.WEAPON, Effect.DEBUFF_DAMAGE, 6.0, 4, "FERMENTED_SPIDER_EYE"),
            new Def("duelist", "Дуэлянт", Target.WEAPON, Effect.PVP_DAMAGE, 8.0, 4, "GOLD_NUGGET"),
            new Def("tamer", "Зверолов", Target.WEAPON, Effect.ANIMAL_DAMAGE, 6.0, 4, "PORKCHOP"),
            new Def("lash", "Хлыст", Target.WEAPON, Effect.ATTACK_SPEED_FLAT, 0.06, 4, "VINE"),
            new Def("drainer", "Гаситель", Target.WEAPON, Effect.CLEANSE_HIT, 8.0, 3, "GLASS_BOTTLE"),
            new Def("freezer", "Морозильник", Target.WEAPON, Effect.FREEZE_HIT, 10.0, 3, "SNOWBALL"),
            // броня (14)
            new Def("plated", "Литная", Target.ARMOR, Effect.TOUGHNESS_FLAT, 0.5, 5, "COPPER_BLOCK"),
            new Def("spring", "Пружина", Target.ARMOR, Effect.JUMP, 8.0, 3, "SLIME_BLOCK"),
            new Def("gliding", "Парение", Target.ARMOR, Effect.GRAVITY_DOWN, 6.0, 5, "SHULKER_SHELL"),
            new Def("cushion", "Амортизатор", Target.ARMOR, Effect.FALL_DAMAGE_DOWN, 10.0, 4, "HAY_BLOCK"),
            new Def("aegis", "Эгида", Target.ARMOR, Effect.ABSORB_PROC, 15.0, 4, "GOLD_BLOCK"),
            new Def("warden", "Щитоносец", Target.ARMOR, Effect.RESIST_PROC, 12.0, 3, "BONE_BLOCK"),
            new Def("sapper", "Сапёр", Target.ARMOR, Effect.EXPLOSION_DAMAGE_DOWN, 12.0, 4, "GUNPOWDER"),
            new Def("antimage", "Антимаг", Target.ARMOR, Effect.MAGIC_DAMAGE_DOWN, 10.0, 4, "ECHO_SHARD"),
            new Def("parka", "Шуба", Target.ARMOR, Effect.NO_FREEZE, 1.0, 1, "BLUE_ICE"),
            new Def("feeder", "Кормилец", Target.ARMOR, Effect.FEED_PULSE, 0.4, 3, "BREAD"),
            new Def("mirror", "Зеркало", Target.ARMOR, Effect.PROJECTILE_REFLECT, 10.0, 3, "GLASS_PANE"),
            new Def("hikers", "Ступни", Target.ARMOR, Effect.WALK_EFFICIENCY, 0.1, 4, "SOUL_SAND"),
            new Def("mead", "Медовник", Target.ARMOR, Effect.GOOD_EFFECTS_LONGER, 15.0, 4, "HONEY_BOTTLE"),
            new Def("stoic", "Стойкий", Target.ARMOR, Effect.BAD_EFFECTS_SHORTER, 12.0, 4, "RED_MUSHROOM"),
            // инструменты (6)
            new Def("gardener", "Земледел", Target.TOOL, Effect.CROP_DOUBLE, 10.0, 4, "WHEAT_SEEDS"),
            new Def("logger", "Лесник", Target.TOOL, Effect.LOG_DOUBLE, 8.0, 4, "OAK_SAPLING"),
            new Def("sailor", "Моряк", Target.TOOL, Effect.FISH_DOUBLE, 12.0, 4, "HEART_OF_THE_SEA"),
            new Def("workaholic", "Трудоголик", Target.TOOL, Effect.PULSE_HASTE, 1.0, 2, "COAL"),
            new Def("goldbug", "Златолюб", Target.TOOL, Effect.GOLD_FIND, 5.0, 4, "GOLD_ORE"),
            new Def("delver", "Глубинник", Target.TOOL, Effect.DEEP_DOUBLE, 10.0, 4, "DEEPSLATE"),
            // универсальные (5)
            new Def("scholar", "Эрудит", Target.ANY, Effect.XP_GAIN, 5.0, 4, "PAPER"),
            new Def("mender", "Мастеровой", Target.ANY, Effect.PULSE_MEND, 1.0, 3, "BLAZE_ROD"),
            new Def("pathwalker", "Тропинка", Target.ANY, Effect.PATH_SPEED, 1.0, 1, "GRASS_BLOCK"),
            new Def("breeze", "Ветер", Target.ANY, Effect.ROAM_SPEED, 1.0, 1, "WIND_CHARGE"),
            new Def("sniper", "Снайпер", Target.WEAPON, Effect.SNIPE, 8.0, 4, "ARROW"));

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
            case RAW_DAMAGE -> "+" + value + " к урону";
            case ATTACK_KNOCKBACK -> "+" + value + " к отброску";
            case REACH -> "+" + value + " м дальности удара";
            case SWEEP_RATIO -> "+" + Math.round(total * 100) + "% урона веером";
            case IGNITE -> value + "% поджечь врага";
            case SLOW_HIT -> value + "% замедлить цель";
            case POISON_HIT -> value + "% отравить цель";
            case BLIND_HIT -> value + "% ослепить цель";
            case FIRST_STRIKE -> "+" + value + "% по нетронутым";
            case ARMOR_PIERCE -> "+" + value + "% по бронированным";
            case RAGE -> "ярость за потерянные ❤ (до 30%)";
            case KB_RESIST -> "+" + Math.round(total * 100) + "% отпора отбросу";
            case SAFE_FALL -> "+" + value + " м мягкости падения";
            case STEP_HEIGHT -> "+" + value + " м авто-шаг";
            case OXYGEN -> "+" + Math.round(total * 100) + "% воздуха под водой";
            case SWIM_SPEED -> "+" + Math.round(total * 100) + "% скорости плавания";
            case SNEAK_SPEED -> "+" + value + "% скорости красться";
            case BURN_TIME -> "-" + value + "% времени горения";
            case EXPLOSION_RESIST -> "+" + Math.round(total * 100) + "% отпора взрыву";
            case LUCK -> "+" + value + " удачи";
            case PARRY -> value + "% парировать удар";
            case REGEN_PULSE -> "+" + value + "❤/2с регенерации";
            case CATCHER -> value + "% поймать нападавшего";
            case ADRENALINE -> "скорость при HP ниже 30% (раз в 10 с)";
            case LAST_STAND -> "смертельный удар не убьёт (раз в 60 с)";
            case MINING_EFFICIENCY -> "+" + value + " к темпу добычи";
            case BLOCK_REACH -> "+" + value + " м дальности руки";
            case SMELT_ORE -> value + "% переплавить дроп руды";
            case BLOCK_XP -> "+" + value + "% опыта с блоков";
            case ZONE_BREAK -> "копает колонну боков";
            case SOULBOUND -> "не выпадает при смерти";
            case XP_REPAIR -> value + "% опыта чинит предмет";
            case NIGHT_VISION -> "ночное зрение (пассивно)";
            case MAGNET -> "магнит дропа (до " + fmt(1.5 + total) + " м)";
            case SCALE_UP -> "+" + value + "% роста";
            // --- 1.4.57 ---
            case THUNDER -> value + "% ударить громом (+" + fmt(level / 2.0) + "❤)";
            case WITHER_HEX -> value + "% иссушить цель";
            case LOOT_DOUBLE -> value + "% дубль добычи моба";
            case HOOK -> value + "% подтянуть цель крюком";
            case DEBUFF_DAMAGE -> "+" + value + "% по ослабленным";
            case PVP_DAMAGE -> "+" + value + "% урона игрокам";
            case ANIMAL_DAMAGE -> "+" + value + "% урона животным";
            case ATTACK_SPEED_FLAT -> "+" + value + " к скорости ударов";
            case CLEANSE_HIT -> value + "% снять магию с цели";
            case FREEZE_HIT -> value + "% заморозить цель";
            case TOUGHNESS_FLAT -> "+" + value + " к прочности брони";
            case JUMP -> "+" + value + "% к прыжку";
            case GRAVITY_DOWN -> "-" + value + "% гравитации";
            case FALL_DAMAGE_DOWN -> "-" + value + "% урона падения";
            case WALK_EFFICIENCY -> "+" + Math.round(total * 100) + "% лёгкости ходьбы";
            case ABSORB_PROC -> value + "% шанс поглощения от удара";
            case RESIST_PROC -> value + "% шанс стойкости от удара";
            case EXPLOSION_DAMAGE_DOWN -> "-" + value + "% урона взрывов";
            case MAGIC_DAMAGE_DOWN -> "-" + value + "% урона магии";
            case NO_FREEZE -> "не замерзает никогда";
            case FEED_PULSE -> "+" + value + " сытости/2с голодающему";
            case PROJECTILE_REFLECT -> value + "% отразить снаряд";
            case GOOD_EFFECTS_LONGER -> "+" + value + "% длительности добрых эффектов";
            case BAD_EFFECTS_SHORTER -> "-" + value + "% длительности злых эффектов";
            case CROP_DOUBLE -> value + "% дубль урожая";
            case LOG_DOUBLE -> value + "% дубль брёвен";
            case FISH_DOUBLE -> value + "% дубль улова";
            case PULSE_HASTE -> "спешка с инструментом в руке (II на ур. 2)";
            case GOLD_FIND -> value + "% найти золотую крупицу в камне";
            case DEEP_DOUBLE -> value + "% дубль добычи глубже нуля";
            case XP_GAIN -> "+" + value + "% опыта из любых источников";
            case PULSE_MEND -> "чинит себя: " + fmt(def.perLevel()) + " прочности/2с";
            case PATH_SPEED -> "скорость на тропинках и траве";
            case ROAM_SPEED -> "скорость вне боя (после 5 с тишины)";
            case SNIPE -> "+" + value + "% урона стрелам";
        };
    }

    /** Целое без хвоста, иначе один знак после запятой (1.5 → «1.5», 3.0 → «3»). */
    static String fmt(double value) {
        return value == Math.rint(value)
                ? Long.toString((long) value)
                : String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
