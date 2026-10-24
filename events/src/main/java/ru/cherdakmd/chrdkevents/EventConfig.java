package ru.cherdakmd.chrdkevents;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Разбор {@code config.yml} мировых событий (1.4.160).
 *
 * <p>Расписание задаётся диапазоном паузы между событиями, а сами события —
 * списком с весами. Всё, что нельзя разобрать, пропускается с предупреждением
 * в лог: опечатка в материале не должна ронять плагин и не должна тихо
 * выключать событие.</p>
 */
public final class EventConfig {

    /** Потолок числа событий: больше просто не помещается в список. */
    public static final int MAX_EVENTS = 32;
    /** Потолок паузы между событиями: сутки. */
    public static final int MAX_DELAY_SECONDS = 86_400;
    /** Минимальная пауза: чаще события перестают быть событием. */
    public static final int MIN_DELAY_SECONDS = 60;
    /** Потолок порога онлайна. */
    public static final int MAX_MIN_PLAYERS = 1_000;

    private final boolean enabled;
    private final String locale;
    private final boolean bossbar;
    private final int minPlayers;
    private final int minDelaySeconds;
    private final int maxDelaySeconds;
    private final List<WorldEvent> events;
    private final Map<String, WorldEvent> byId;

    private EventConfig(boolean enabled, String locale, boolean bossbar, int minPlayers,
                        int minDelaySeconds, int maxDelaySeconds, List<WorldEvent> events) {
        this.enabled = enabled;
        this.locale = locale;
        this.bossbar = bossbar;
        this.minPlayers = minPlayers;
        this.minDelaySeconds = minDelaySeconds;
        this.maxDelaySeconds = maxDelaySeconds;
        this.events = List.copyOf(events);
        Map<String, WorldEvent> ids = new LinkedHashMap<>();
        for (WorldEvent event : this.events) {
            ids.putIfAbsent(event.id(), event);
        }
        this.byId = Map.copyOf(ids);
    }

    /** Пустой конфиг: события выключены, но плагин работает. */
    public static EventConfig defaults() {
        return new EventConfig(true, "ru", true, 1, 600, 1_800, List.of());
    }

    /** Разбирает конфиг; {@code null} допустим — тогда действуют умолчания. */
    public static EventConfig parse(FileConfiguration config, Logger logger) {
        FileConfiguration source = config == null ? new YamlConfiguration() : config;

        boolean enabled = source.getBoolean("general.enabled", true);
        String locale = source.getString("general.locale", "ru");
        locale = locale == null || locale.isBlank() ? "ru" : locale.trim().toLowerCase(Locale.ROOT);
        boolean bossbar = source.getBoolean("general.bossbar", true);
        int minPlayers = clamp(source, "general.min-players", 1, 0, MAX_MIN_PLAYERS, logger);

        int minDelay = clamp(source, "schedule.min-delay-seconds", 600,
                MIN_DELAY_SECONDS, MAX_DELAY_SECONDS, logger);
        int maxDelay = clamp(source, "schedule.max-delay-seconds", 1_800,
                MIN_DELAY_SECONDS, MAX_DELAY_SECONDS, logger);
        if (maxDelay < minDelay) {
            if (logger != null) {
                logger.warning("[CHRDK_Events] schedule.max-delay-seconds меньше min-delay-seconds ("
                        + maxDelay + " < " + minDelay + ") — значения поменяны местами.");
            }
            int swap = minDelay;
            minDelay = maxDelay;
            maxDelay = swap;
        }

        List<WorldEvent> events = parseEvents(source, logger);
        if (enabled && events.isEmpty()) {
            if (logger != null) {
                logger.warning("[CHRDK_Events] В config.yml нет ни одного рабочего события — "
                        + "события не будут запускаться. Проверьте секцию events.");
            }
        }
        return new EventConfig(enabled, locale, bossbar, minPlayers, minDelay, maxDelay, events);
    }

    private static List<WorldEvent> parseEvents(FileConfiguration config, Logger logger) {
        List<WorldEvent> out = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("events");
        if (section == null) {
            return out;
        }
        for (String key : section.getKeys(false)) {
            if (out.size() >= MAX_EVENTS) {
                if (logger != null) {
                    logger.warning("[CHRDK_Events] Событий больше " + MAX_EVENTS
                            + " — лишние пропущены.");
                }
                break;
            }
            EventKind kind = EventKind.parse(section.getString(key + ".type"));
            if (kind == null) {
                if (logger != null) {
                    logger.warning("[CHRDK_Events] events." + key + ": неизвестный вид «"
                            + section.getString(key + ".type") + "» — пропускаю "
                            + "(нужны XP, MINING, LOOT или EFFECT).");
                }
                continue;
            }
            String name = section.getString(key + ".name", key);
            String description = section.getString(key + ".description", "");
            int duration = (int) Math.clamp(section.getLong(key + ".duration-seconds", 300L),
                    WorldEvent.MIN_DURATION_SECONDS, WorldEvent.MAX_DURATION_SECONDS);
            int weight = (int) Math.clamp(section.getLong(key + ".weight", 10L),
                    0L, WorldEvent.MAX_WEIGHT);
            int cooldown = (int) Math.clamp(section.getLong(key + ".cooldown-seconds", 3_600L),
                    0L, WorldEvent.MAX_COOLDOWN_SECONDS);
            int percent = (int) Math.clamp(section.getLong(key + ".percent", 25L), 0L, 100L);
            Material icon = EventMaterials.resolve(section.getString(key + ".icon", "CLOCK"));
            if (!EventMaterials.isItem(icon)) {
                icon = Material.CLOCK;
            }
            List<Material> blocks = materials(section.getStringList(key + ".blocks"),
                    logger, "events." + key + ".blocks");
            List<Material> items = materials(section.getStringList(key + ".items"),
                    logger, "events." + key + ".items");
            List<WorldEvent.Effect> effects = effects(section.getMapList(key + ".effects"),
                    logger, "events." + key + ".effects");
            WorldEvent event = new WorldEvent(key, name, description, kind, duration, weight,
                    cooldown, percent, blocks, items, effects, icon);
            if (!event.usable()) {
                if (logger != null) {
                    logger.warning("[CHRDK_Events] events." + key + ": нет веса или данных для вида "
                            + kind + " — событие пропущено (нечего выдавать).");
                }
                continue;
            }
            out.add(event);
        }
        return out;
    }

    private static List<Material> materials(List<String> names, Logger logger, String path) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<Material> out = new ArrayList<>();
        for (String name : names) {
            Material material = EventMaterials.resolve(name);
            if (!EventMaterials.isItem(material)) {
                if (logger != null) {
                    logger.warning("[CHRDK_Events] " + path + ": неизвестный материал «"
                            + name + "» — пропущен.");
                }
                continue;
            }
            out.add(material);
        }
        return out;
    }

    private static List<WorldEvent.Effect> effects(List<Map<?, ?>> rows, Logger logger,
                                                   String path) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<WorldEvent.Effect> out = new ArrayList<>();
        for (Map<?, ?> row : rows) {
            if (row == null) {
                continue;
            }
            String type = String.valueOf(row.get("type") == null ? "" : row.get("type"));
            int amplifier = number(row.get("amplifier"), 0);
            int seconds = number(row.get("seconds"), 120);
            WorldEvent.Effect effect = new WorldEvent.Effect(type, amplifier, seconds);
            if (!effect.usable()) {
                if (logger != null) {
                    logger.warning("[CHRDK_Events] " + path + ": эффект без имени — пропущен.");
                }
                continue;
            }
            out.add(effect);
        }
        return out;
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static int clamp(FileConfiguration source, String path, int fallback,
                             int min, int max, Logger logger) {
        long raw = source.getLong(path, fallback);
        int value = (int) Math.clamp(raw, min, max);
        if (value != raw && logger != null) {
            logger.warning("[CHRDK_Events] " + path + ": " + raw
                    + " вне границ " + min + ".." + max + " — взято " + value + ".");
        }
        return value;
    }

    /* --------------------------------- доступ --------------------------------- */

    public boolean enabled() {
        return enabled;
    }

    public String locale() {
        return locale;
    }

    public boolean bossbar() {
        return bossbar;
    }

    public int minPlayers() {
        return minPlayers;
    }

    public int minDelaySeconds() {
        return minDelaySeconds;
    }

    public int maxDelaySeconds() {
        return maxDelaySeconds;
    }

    public List<WorldEvent> events() {
        return events;
    }

    /** Событие по иду или {@code null}. */
    public WorldEvent event(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return byId.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /** Иды событий — для автодополнения. */
    public List<String> eventIds() {
        List<String> ids = new ArrayList<>();
        for (WorldEvent event : events) {
            ids.add(event.id());
        }
        return ids;
    }
}
