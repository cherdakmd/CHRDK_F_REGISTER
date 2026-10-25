package ru.cherdakmd.chrdktierforge;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Визуальный образ тира (1.4.119): блеск предмета и аура частиц вокруг владельца.
 *
 * <p>У каждого из семи тиров свой стиль: проклятый дымится тёмно-красным,
 * легендарный — золотом с искрами пламени. Аура рисуется только за предмет
 * в руке (или надетую броню), поэтому редкость видно в бою, а не по всему
 * инвентарю. Стили целиком переопределяются в {@code visuals.tiers.*};
 * отсутствующая секция берёт умолчание из кода.</p>
 *
 * <p>Частицы без данных ({@code Void}) и цветная пыль {@code DUST} — только они
 * поддерживаются: у остальных частиц в 1.21+ обязательный {@code data}, и
 * случайный {@code ITEM}/{@code BLOCK} из конфига уронил бы отрисовку.
 * Неизвестные и «датовые» частицы отбрасываются с предупреждением в лог.</p>
 *
 * <p>Чистая логика парсинга — покрыта {@link TierVisualsTest}.</p>
 */
public record TierVisuals(boolean enabled, int intervalTicks, boolean selfOnly, boolean armor,
                          Map<String, Style> tiers) {

    /** Ниже 5 тиков аура превращается в сплошное облако и грузит сеть. */
    public static final int MIN_INTERVAL_TICKS = 5;
    /** Раз в 2 секунды — уже почти незаметно, зато дёшево. */
    public static final int MAX_INTERVAL_TICKS = 40;
    /** Больше трёх слоёв на тир — визуальный шум и лишние пакеты. */
    public static final int MAX_EFFECTS = 3;
    /** Потолок частиц на один слой: защита от «count: 500» в конфиге. */
    public static final int MAX_COUNT = 8;

    /** Блеск предмета: как у ванили, принудительно включён или выключен. */
    public enum Glint {
        INHERIT("inherit"), ON("on"), OFF("off");

        private final String id;

        Glint(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** Неизвестное значение — «как в ванили» с предупреждением. */
        static Glint parse(String raw, String tier, Logger logger) {
            String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            // YAML 1.1 читает без кавычек on/off как boolean — не теряем смысл.
            if ("true".equals(normalized)) {
                return ON;
            }
            if ("false".equals(normalized)) {
                return OFF;
            }
            for (Glint value : values()) {
                if (value.id.equals(normalized)) {
                    return value;
                }
            }
            if (!normalized.isEmpty()) {
                logger.warning("config: visuals.tiers." + tier + ".glint '" + raw
                        + "' — неизвестно, используется inherit");
            }
            return INHERIT;
        }
    }

    /**
     * Слой ауры. {@code color}/{@code size} имеют смысл только для {@link Particle#DUST};
     * для остальных частиц {@code color == null}.
     */
    public record Effect(Particle particle, int count, double spread, double speed,
                         Color color, float size) {
    }

    /** Стиль тира: блеск и до трёх слоёв частиц. */
    public record Style(Glint glint, List<Effect> effects) {

        public Style {
            effects = List.copyOf(effects);
        }

        public boolean hasParticles() {
            return !effects.isEmpty();
        }
    }

    public TierVisuals {
        tiers = Collections.unmodifiableMap(new LinkedHashMap<>(tiers));
    }

    public Style styleFor(String tierId) {
        return tierId == null ? null : tiers.get(tierId);
    }

    /** Стиль как строка PDC: по ней {@link TierItems} понимает, что блеск устарел. */
    public String stampFor(String tierId) {
        if (!enabled) {
            return "off";
        }
        Style style = styleFor(tierId);
        return style == null ? "inherit" : "glint:" + style.glint().id();
    }

    /** Сколько тиров реально рисуют частицы (для отчёта в консоль). */
    public int tiersWithParticles() {
        int count = 0;
        for (Style style : tiers.values()) {
            if (style.hasParticles()) {
                count++;
            }
        }
        return count;
    }

    /* ------------------------------- умолчания ------------------------------- */

    private static final Map<String, Style> DEFAULT_STYLES = buildDefaults();

    /** Умолчания из кода: конфиг может переопределить любое поле. */
    public static TierVisuals defaults() {
        return new TierVisuals(true, 10, false, true, DEFAULT_STYLES);
    }

    private static Map<String, Style> buildDefaults() {
        Map<String, Style> styles = new LinkedHashMap<>();
        styles.put("cursed", new Style(Glint.OFF, List.of(
                dust(0x6A0F14, 1.1f, 2, 0.32, 0.0),
                plain(Particle.SMOKE, 1, 0.35, 0.01))));
        styles.put("weak", new Style(Glint.INHERIT, List.of(
                dust(0x9A9A9A, 1.0f, 1, 0.28, 0.0))));
        styles.put("common", new Style(Glint.INHERIT, List.of(
                dust(0xE8E8E8, 0.9f, 1, 0.22, 0.0))));
        styles.put("fine", new Style(Glint.INHERIT, List.of(
                dust(0x49B04F, 1.0f, 2, 0.30, 0.0))));
        styles.put("rare", new Style(Glint.INHERIT, List.of(
                dust(0x2FD3E0, 1.05f, 2, 0.30, 0.0),
                plain(Particle.BUBBLE, 1, 0.30, 0.02))));
        styles.put("epic", new Style(Glint.ON, List.of(
                dust(0xB165F0, 1.15f, 3, 0.34, 0.0),
                plain(Particle.PORTAL, 1, 0.40, 0.05))));
        styles.put("legendary", new Style(Glint.ON, List.of(
                dust(0xFFC53D, 1.20f, 3, 0.38, 0.0),
                plain(Particle.FLAME, 1, 0.24, 0.005),
                plain(Particle.END_ROD, 1, 0.34, 0.01))));
        return Collections.unmodifiableMap(styles);
    }

    private static Effect dust(int rgb, float size, int count, double spread, double speed) {
        return new Effect(Particle.DUST, count, spread, speed, Color.fromRGB(rgb), size);
    }

    private static Effect plain(Particle particle, int count, double spread, double speed) {
        return new Effect(particle, count, spread, speed, null, 0f);
    }

    /* -------------------------------- парсинг -------------------------------- */

    public static TierVisuals parse(FileConfiguration config, Logger logger) {
        boolean enabled = config.getBoolean("visuals.enabled", true);
        int interval = clampInt(config.getInt("visuals.interval-ticks", 10),
                MIN_INTERVAL_TICKS, MAX_INTERVAL_TICKS, logger, "visuals.interval-ticks");
        boolean selfOnly = config.getBoolean("visuals.self-only", false);
        boolean armor = config.getBoolean("visuals.armor", true);

        Map<String, Style> styles = new LinkedHashMap<>();
        for (String tier : TierDef.LADDER) {
            Style fallback = DEFAULT_STYLES.get(tier);
            ConfigurationSection section = config.getConfigurationSection("visuals.tiers." + tier);
            if (section == null) {
                styles.put(tier, fallback);
                continue;
            }
            Glint glint = Glint.parse(section.getString("glint", fallback.glint().id()), tier, logger);
            List<Effect> effects = section.isList("particles")
                    ? parseEffects(section.getMapList("particles"), tier, logger)
                    : fallback.effects();
            styles.put(tier, new Style(glint, effects));
        }
        return new TierVisuals(enabled, interval, selfOnly, armor, styles);
    }

    /** Пустой список в конфиге — сознательное «без частиц», отсутствие ключа — умолчание. */
    private static List<Effect> parseEffects(List<Map<?, ?>> raw, String tier, Logger logger) {
        List<Effect> effects = new ArrayList<>();
        for (Map<?, ?> map : raw) {
            if (effects.size() >= MAX_EFFECTS) {
                logger.warning("config: visuals.tiers." + tier
                        + ".particles — больше " + MAX_EFFECTS + " слоёв не поддерживается");
                break;
            }
            String name = string(map.get("type"));
            Particle particle = particle(name);
            if (particle == null) {
                logger.warning("config: visuals.tiers." + tier
                        + ".particles — неизвестная частица '" + name + "', слой пропущен");
                continue;
            }
            int count = clampInt((int) Math.round(number(map.get("count"), 2)),
                    1, MAX_COUNT, logger, "visuals.tiers." + tier + ".particles.count");
            double spread = clampDouble(number(map.get("spread"), 0.3), 0.0, 2.0, logger,
                    "visuals.tiers." + tier + ".particles.spread");
            double speed = clampDouble(number(map.get("speed"), 0.0), 0.0, 1.0, logger,
                    "visuals.tiers." + tier + ".particles.speed");
            if (particle == Particle.DUST) {
                Color color = color(map.get("color"), tier, logger);
                float size = (float) clampDouble(number(map.get("size"), 1.0), 0.1, 4.0,
                        logger, "visuals.tiers." + tier + ".particles.size");
                effects.add(new Effect(particle, count, spread, speed, color, size));
            } else if (particle.getDataType() == Void.class) {
                effects.add(new Effect(particle, count, spread, speed, null, 0f));
            } else {
                logger.warning("config: visuals.tiers." + tier + ".particles — частица "
                        + particle.name() + " требует данных и не поддерживается, слой пропущен");
            }
        }
        return effects;
    }

    private static Particle particle(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT);
        if (name.startsWith("MINECRAFT:")) {
            name = name.substring("MINECRAFT:".length());
        }
        try {
            return Particle.valueOf(name);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** {@code #RRGGBB} или {@code RRGGBB}; битое значение — белый с предупреждением. */
    private static Color color(Object raw, String tier, Logger logger) {
        String text = string(raw).replace("#", "").trim();
        if (text.matches("[0-9A-Fa-f]{6}")) {
            return Color.fromRGB(Integer.parseInt(text, 16));
        }
        if (!text.isEmpty()) {
            logger.warning("config: visuals.tiers." + tier + ".particles.color '" + string(raw)
                    + "' — нужен #RRGGBB, используется белый");
        }
        return Color.WHITE;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int clampInt(int value, int min, int max, Logger logger, String key) {
        if (value < min || value > max) {
            logger.warning("config: " + key + " вне диапазона " + min + ".." + max
                    + ", применена граница");
            return Math.max(min, Math.min(max, value));
        }
        return value;
    }

    private static double clampDouble(double value, double min, double max, Logger logger, String key) {
        if (!Double.isFinite(value) || value < min || value > max) {
            logger.warning("config: " + key + " вне диапазона " + min + ".." + max
                    + ", применена граница");
            return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
        }
        return value;
    }
}
