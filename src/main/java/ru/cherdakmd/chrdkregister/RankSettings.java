package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Настройки донатных рангов из секции {@code ranks} (1.4.167).
 *
 * <p>Разбор терпимый: если секции нет (старый config.yml), работают встроенные
 * значения по умолчанию — лестница из пяти ступеней с русскими именами. Одна
 * кривая запись не роняет плагин и не утаскивает остальные ступени. Каталог
 * товаров при этом остаётся источником цен и сроков, а здесь только оформление
 * и приветственный ранг.</p>
 *
 * <p>Класс чистый: без Bukkit-сервера, покрыт юнит-тестами.</p>
 */
public final class RankSettings {

    /** Пять ступеней по умолчанию — те, что согласованы с владельцем. */
    private static final List<RankTier> DEFAULT_TIERS = List.of(
            new RankTier(1, "wanderer", "Странник",
                    "<gray>%name%</gray>", "✦", ""),
            new RankTier(2, "pathfinder", "Следопыт",
                    "<green>%name%</green>", "❖", ""),
            new RankTier(3, "keeper", "Хранитель",
                    "<aqua>%name%</aqua>", "✧",
                    "<aqua>✧ Хранитель</aqua> <white>%player%</white> <gray>вернулся в гильдию</gray>"),
            new RankTier(4, "patron", "Меценат",
                    "<gradient:#ffd700:#ff8800>%name%</gradient>", "★",
                    "<gradient:#ffd700:#ff8800>★ Меценат</gradient> <white>%player%</white> "
                            + "<gray>снаряжает новую экспедицию</gray>"),
            new RankTier(5, "founder", "Покровитель",
                    "<gradient:#b388ff:#ffd700>%name%</gradient>", "⚜",
                    "<gradient:#b388ff:#ffd700>⚜ Покровитель</gradient> <white>%player%</white> "
                            + "<gray>входит в летопись гильдии</gray>")
    );

    private final RankJoinMessages joinSelector = new RankJoinMessages();
    private final Map<Integer, List<String>> joinPools = new LinkedHashMap<>();
    private final boolean enabled;
    private final boolean showNameInChat;
    private final boolean welcomeEnabled;
    private final int welcomeLevel;
    private final int welcomeDays;
    private final boolean announceJoin;
    private final List<RankTier> tiers;
    private final Map<Integer, RankTier> byLevel;
    private final int hatMinLevel;
    private final List<Integer> bonusXp;
    private final List<Integer> bonusDrop;
    private final List<Integer> bonusSkill;

    private RankSettings(boolean enabled, boolean showNameInChat, boolean welcomeEnabled,
                         int welcomeLevel, int welcomeDays, boolean announceJoin,
                         List<RankTier> tiers, int hatMinLevel,
                         List<Integer> bonusXp, List<Integer> bonusDrop,
                         List<Integer> bonusSkill) {
        this.enabled = enabled;
        this.showNameInChat = showNameInChat;
        this.welcomeEnabled = false; // старый конфиг не может включить бесплатный платный ранг
        this.welcomeLevel = RankLadder.clampLevel(welcomeLevel);
        this.welcomeDays = Math.max(0, Math.min(365, welcomeDays));
        this.announceJoin = announceJoin;
        this.tiers = List.copyOf(tiers);
        this.hatMinLevel = Math.max(1, Math.min(RankLadder.MAX_LEVEL, hatMinLevel));
        this.bonusXp = List.copyOf(bonusXp);
        this.bonusDrop = List.copyOf(bonusDrop);
        this.bonusSkill = List.copyOf(bonusSkill);
        Map<Integer, RankTier> index = new LinkedHashMap<>();
        for (RankTier tier : this.tiers) {
            index.put(tier.level(), tier);
        }
        this.byLevel = Map.copyOf(index);
        for (RankTier tier : this.tiers) {
            RankTier stock = DEFAULT_TIERS.stream().filter(t -> t.level() == tier.level()).findFirst().orElse(null);
            // Только точный старый сток получает новый пул; собственная строка/отключение сохраняются.
            boolean oldStock = stock != null && stock.equals(tier);
            joinPools.put(tier.level(), oldStock ? RankJoinMessages.defaults(tier)
                    : tier.hasJoinLine() ? List.of(tier.joinLine()) : List.of());
        }
    }

    /** Значения по умолчанию: лестница включена, приветственный ранг — 1 день. */
    public static RankSettings defaults() {
        return new RankSettings(true, false, false, 1, 3, true, DEFAULT_TIERS, 5,
                BonusDefaults.XP, BonusDefaults.DROP, BonusDefaults.SKILL);
    }

    public static RankSettings from(FileConfiguration config) {
        if (config == null) {
            return defaults();
        }
        boolean enabled = config.getBoolean("ranks.enabled", true);
        boolean showName = config.getBoolean("ranks.show-name-in-chat", false);
        boolean welcomeEnabled = config.getBoolean("ranks.welcome.enabled", true);
        int welcomeLevel = config.getInt("ranks.welcome.level", 1);
        int welcomeDays = config.getInt("ranks.welcome.days", 3);
        boolean announceJoin = config.getBoolean("ranks.announce-join", true);

        List<RankTier> tiers = parseTiers(config.getMapList("ranks.levels"));
        if (tiers.isEmpty()) {
            tiers = DEFAULT_TIERS;
        }
        RankSettings settings = new RankSettings(enabled, showName, welcomeEnabled, welcomeLevel, welcomeDays,
                announceJoin, tiers,
                config.getInt("ranks.hat.min-level", 5),
                intList(config, "ranks.bonuses.xp-percent", BonusDefaults.XP),
                intList(config, "ranks.bonuses.drop-percent", BonusDefaults.DROP),
                intList(config, "ranks.bonuses.skill-levels", BonusDefaults.SKILL));
        for (Map<?, ?> entry : config.getMapList("ranks.levels")) {
            int level = intOf(entry.get("level"), -1);
            if (settings.byLevel.containsKey(level) && entry.get("join-lines") instanceof List<?> lines) {
                settings.joinPools.put(level, lines.stream().filter(String.class::isInstance).map(String.class::cast)
                        .map(String::trim).filter(line -> !line.isBlank()).distinct().limit(100).toList());
            }
        }
        return settings;
    }

    /** Таблицы бонусов по умолчанию — те, что согласованы с владельцем (1.4.167). */
    static final class BonusDefaults {
        static final List<Integer> XP = List.of(0, 10, 20, 30, 40, 50);
        static final List<Integer> DROP = List.of(0, 5, 10, 15, 20, 25);
        static final List<Integer> SKILL = List.of(0, 2, 4, 6, 8, 10);

        private BonusDefaults() {
        }
    }

    /**
     * Таблица чисел из конфига: пустая или битая — встроенные значения.
     *
     * <p>Длина таблицы не важна: ступень выше последней получает последнее
     * значение, поэтому админ может задать и три числа, и десять.</p>
     */
    static List<Integer> intList(FileConfiguration config, String path, List<Integer> fallback) {
        List<?> raw = config.getList(path, List.of());
        if (raw.isEmpty()) {
            return fallback;
        }
        List<Integer> result = new ArrayList<>();
        for (Object value : raw) {
            if (!(value instanceof Number number)) {
                // Одна строка-опечатка в таблице не должна молча обнулять бонусы:
                // берём умолчания целиком и кажем это в /pass doctor.
                return fallback;
            }
            result.add(Math.max(0, number.intValue()));
        }
        return result.isEmpty() ? fallback : List.copyOf(result);
    }

    /**
     * Бонусы ступени ранга (1.4.167).
     *
     * <p>Возвращается всегда: без ранга — {@link ru.cherdakmd.chrdkregister.api.RankBonus#NONE},
     * выше последней ступени — значения последней. Потолки контракта применяются
     * в самом {@code RankBonus}, поэтому конфиг не может продать больше обещанного.</p>
     */
    public ru.cherdakmd.chrdkregister.api.RankBonus bonus(int level) {
        if (level <= 0) {
            return ru.cherdakmd.chrdkregister.api.RankBonus.NONE;
        }
        return new ru.cherdakmd.chrdkregister.api.RankBonus(level,
                pick(bonusXp, level), pick(bonusDrop, level), pick(bonusSkill, level));
    }

    private static int pick(List<Integer> table, int level) {
        if (table.isEmpty()) {
            return 0;
        }
        return table.get(Math.min(level, table.size() - 1));
    }

    /** Разбор списка ступеней; кривые записи пропускаются, дубли уровней — тоже. */
    static List<RankTier> parseTiers(List<Map<?, ?>> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<RankTier> result = new ArrayList<>();
        Map<Integer, Boolean> seen = new LinkedHashMap<>();
        for (Map<?, ?> entry : raw) {
            if (entry == null) {
                continue;
            }
            int level = intOf(entry.get("level"), -1);
            if (level < 1 || level > RankLadder.MAX_LEVEL || seen.containsKey(level)) {
                continue;
            }
            String id = text(entry.get("id")).toLowerCase(Locale.ROOT);
            String name = text(entry.get("name"));
            String format = text(entry.get("format"));
            String badge = text(entry.get("badge"));
            String joinLine = text(entry.get("join-line"));
            seen.put(level, true);
            result.add(new RankTier(level, id, name, format, badge, joinLine));
        }
        return result;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /* ------------------------------ доступ ------------------------------ */

    public boolean enabled() {
        return enabled;
    }

    public boolean showNameInChat() {
        return showNameInChat;
    }

    public boolean welcomeEnabled() {
        return welcomeEnabled;
    }

    public int welcomeLevel() {
        return welcomeLevel;
    }

    public int welcomeDays() {
        return welcomeDays;
    }

    /** Со ступени ранга доступна команда {@code /hat}. */
    public int hatMinLevel() {
        return hatMinLevel;
    }

    public boolean announceJoin() {
        return announceJoin;
    }

    public List<RankTier> tiers() {
        return tiers;
    }

    /** Ступень по уровню; {@code null}, если такого уровня нет. */
    public RankTier tier(int level) {
        return byLevel.get(level);
    }

    /** Имя ступени без разметки; пустая строка для 0 и неизвестных уровней. */
    public String name(int level) {
        RankTier tier = byLevel.get(level);
        return tier == null ? "" : tier.name();
    }

    /** Знак перед ником; пустая строка, если ранга нет. */
    public String badge(int level) {
        RankTier tier = byLevel.get(level);
        return tier == null ? "" : tier.badge();
    }

    /** Ник в оформлении ранга; без ступени — ник как есть. */
    public String decorate(int level, String playerName) {
        RankTier tier = byLevel.get(level);
        return tier == null ? (playerName == null ? "" : playerName) : tier.decorate(playerName);
    }

    /** Строка входа в чате; пустая — не анонсировать. */
    public String joinLine(int level, String playerName) {
        RankTier tier = byLevel.get(level);
        if (tier == null || !tier.hasJoinLine()) {
            return "";
        }
        return tier.joinLine().replace("%player%", playerName == null ? "" : playerName);
    }

    public String nextJoinLine(int level, String name) {
        return joinSelector.next(level, name, joinPools.getOrDefault(level, List.of()));
    }
    List<String> joinPool(int level) { return joinPools.getOrDefault(level, List.of()); }

    /** Наибольший настроенный уровень. */
    public int maxLevel() {
        int max = 0;
        for (RankTier tier : tiers) {
            max = Math.max(max, tier.level());
        }
        return max;
    }
}
