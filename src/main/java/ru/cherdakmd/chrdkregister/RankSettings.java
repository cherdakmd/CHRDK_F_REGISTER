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

    private final boolean enabled;
    private final boolean showNameInChat;
    private final boolean welcomeEnabled;
    private final int welcomeLevel;
    private final int welcomeDays;
    private final boolean announceJoin;
    private final List<RankTier> tiers;
    private final Map<Integer, RankTier> byLevel;

    private RankSettings(boolean enabled, boolean showNameInChat, boolean welcomeEnabled,
                         int welcomeLevel, int welcomeDays, boolean announceJoin,
                         List<RankTier> tiers) {
        this.enabled = enabled;
        this.showNameInChat = showNameInChat;
        this.welcomeEnabled = welcomeEnabled;
        this.welcomeLevel = RankLadder.clampLevel(welcomeLevel);
        this.welcomeDays = Math.max(0, Math.min(365, welcomeDays));
        this.announceJoin = announceJoin;
        this.tiers = List.copyOf(tiers);
        Map<Integer, RankTier> index = new LinkedHashMap<>();
        for (RankTier tier : this.tiers) {
            index.put(tier.level(), tier);
        }
        this.byLevel = Map.copyOf(index);
    }

    /** Значения по умолчанию: лестница включена, приветственный ранг — 1 день. */
    public static RankSettings defaults() {
        return new RankSettings(true, false, true, 1, 1, true, DEFAULT_TIERS);
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
        return new RankSettings(enabled, showName, welcomeEnabled, welcomeLevel, welcomeDays,
                announceJoin, tiers);
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

    /** Наибольший настроенный уровень. */
    public int maxLevel() {
        int max = 0;
        for (RankTier tier : tiers) {
            max = Math.max(max, tier.level());
        }
        return max;
    }
}
