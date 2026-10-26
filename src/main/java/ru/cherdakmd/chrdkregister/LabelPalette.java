package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Палитра цветов ника (1.4.187): база всем, больше — со ступенью ранга,
 * отдельные — покупкой в каталоге.
 *
 * <p>Владелец ранга уже получает цвет ступени, но выбрать свой не мог: ранг
 * задаёт оформление целиком. Здесь — список цветов из конфига ядра
 * ({@code labels.colors.list}). У каждого цвета два условия доступа:</p>
 *
 * <ul>
 *   <li>{@code rank} — минимальная ступень доната (0 — доступен всем);</li>
 *   <li>{@code product} — id товара каталога: пока он активен, цвет открыт
 *       даже без нужной ступени.</li>
 * </ul>
 *
 * <p>Палитра ничего не знает про чат и Bukkit-события: чистая логика, которую
 * можно проверить юнит-тестами и перезагрузить по {@code /pass reload}.</p>
 */
public final class LabelPalette {

    /** Один цвет: ид, подпись для меню, MiniMessage-шаблон и условия доступа. */
    public record Color(String id, String name, String format, int rank, String product) {

        public Color {
            id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
            name = name == null || name.isBlank() ? id : name;
            format = format == null || format.isBlank() ? "<white>%name%</white>" : format;
            rank = Math.max(0, rank);
            product = product == null ? "" : product.trim().toLowerCase(Locale.ROOT);
        }

        /** Цвет продаётся в каталоге, а не открывается рангом. */
        public boolean inCatalog() {
            return !product.isBlank();
        }

        /** Доступен ли при этой ступени без покупок. */
        public boolean openForRank(int rankLevel) {
            return rankLevel >= rank;
        }
    }

    private static final LabelPalette EMPTY = new LabelPalette(false, List.of());

    private final boolean enabled;
    private final List<Color> colors;
    private final Map<String, Color> byId;

    private LabelPalette(boolean enabled, List<Color> colors) {
        this.enabled = enabled;
        this.colors = List.copyOf(colors);
        Set<String> seen = new LinkedHashSet<>();
        java.util.Map<String, Color> map = new java.util.LinkedHashMap<>();
        for (Color color : colors) {
            if (!color.id().isEmpty() && seen.add(color.id())) {
                map.put(color.id(), color);
            }
        }
        this.byId = Map.copyOf(map);
    }

    /** Палитра выключена: ник красится рангом или тегом каталога, как раньше. */
    public static LabelPalette disabled() {
        return EMPTY;
    }

    public static LabelPalette fromConfig(FileConfiguration config) {
        if (config == null) {
            return EMPTY;
        }
        boolean enabled = config.getBoolean("labels.colors.enabled", true);
        List<Map<?, ?>> raw = config.getMapList("labels.colors.list");
        List<Color> parsed = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<?, ?> entry : raw) {
            String id = text(entry.get("id")).toLowerCase(Locale.ROOT);
            if (id.isEmpty() || !id.matches("[a-z0-9_-]{1,32}") || !seen.add(id)) {
                continue;
            }
            parsed.add(new Color(id, text(entry.get("name")),
                    text(entry.get("format")),
                    number(entry.get("rank")),
                    text(entry.get("product"))));
        }
        return new LabelPalette(enabled, parsed);
    }

    /** Палитра из готового списка — для тестов. */
    public static LabelPalette of(boolean enabled, List<Color> colors) {
        return new LabelPalette(enabled, colors == null ? List.of() : colors);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public List<Color> colors() {
        return colors;
    }

    /** Цвет по иду; {@code null} — ида нет в конфиге. */
    public Color byId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return byId.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Доступен ли цвет игроку: хватает ступени ранга либо куплен товар,
     * который его открывает.
     *
     * @param color      проверяемый цвет; {@code null} — недоступен
     * @param rankLevel  ступень донатного ранга 0..5
     * @param purchased  иды активных товаров игрока (то, что он купил)
     */
    public boolean allowed(Color color, int rankLevel, Set<String> purchased) {
        if (color == null) {
            return false;
        }
        if (color.openForRank(rankLevel)) {
            return true;
        }
        return color.inCatalog() && purchased != null && purchased.contains(color.product());
    }

    /** Все доступные игроку цвета в порядке конфига. */
    public List<Color> available(int rankLevel, Set<String> purchased) {
        List<Color> out = new ArrayList<>();
        for (Color color : colors) {
            if (allowed(color, rankLevel, purchased)) {
                out.add(color);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Отрисовка ника цветом: {@code <aqua>%name%</aqua>} → {@code <aqua>Ник</aqua>}.
     *
     * <p>Имя уже экранировано вызывающим кодом: тут только подстановка.</p>
     */
    public static String apply(Color color, String escapedName) {
        String name = escapedName == null ? "" : escapedName;
        if (color == null) {
            return name;
        }
        return color.format().replace("%name%", name);
    }

    /** Почему цвет недоступен игроку. */
    public enum Lock {
        /** Цвет есть и открыт — подсказка не нужна. */
        NONE,
        /** Такого цвета в палитре нет. */
        HIDDEN,
        /** Цвет продаётся в каталоге, товар не активен. */
        CATALOG,
        /** Цвету нужна ступень ранга выше текущей. */
        RANK
    }

    /**
     * Ближайшее требование для подсказки в меню: «нужен ранг III» или
     * «продаётся в каталоге». Текст выбирает вызывающий код — язык и разметка
     * живут в messages.yml, а не в логике доступа.
     */
    public static Lock lockReason(Color color, Set<String> purchased) {
        if (color == null) {
            return Lock.HIDDEN;
        }
        if (color.inCatalog() && (purchased == null || !purchased.contains(color.product()))) {
            return Lock.CATALOG;
        }
        return Lock.RANK;
    }

    /** Разбор секции конфига — для {@code ConfigReport} и диагностики. */
    public static int count(ConfigurationSection section) {
        return section == null ? 0 : section.getMapList("list").size();
    }
}
