package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Настройки серверного меню {@code /menu} (секция {@code server-menu:}).
 *
 * <p>Меню двухуровневое: главная страница показывает категории (и быстрые
 * кнопки без категории), страница категории — её пункты, кнопку «Назад» и
 * «Закрыть». Так меню не упирается в 54 слота, когда аддонов становится больше,
 * и новые разделы не приходится втискивать в единственный ряд.</p>
 *
 * <p>Обратная совместимость: если {@code categories} не заданы (или у пунктов
 * нет {@code category}), меню остаётся одностраничным, как в 1.4.49–1.4.137.
 * Пункт скрывается, если выключен его аддон ({@code plugin}) или у игрока нет
 * права ({@code permission}).</p>
 *
 * <p>Пункт без {@code command} — подсказка: он рисуется, но клик ничего не
 * делает. Так на главную страницу попадает строка «как пользоваться меню»,
 * не занимая отдельного раздела.</p>
 *
 * @param enabled       мастер-флаг меню
 * @param title         заголовок главной страницы
 * @param categoryTitle заголовок страницы категории ({@code %category%})
 * @param backSlot      слот кнопки «Назад» на страницах категорий
 * @param backIcon      иконка кнопки «Назад»
 * @param backName      название кнопки «Назад» (MiniMessage)
 * @param closeSlot     слот кнопки «Закрыть» на страницах категорий ({@code -1} — нет кнопки)
 * @param closeIcon     иконка кнопки «Закрыть»
 * @param closeName     название кнопки «Закрыть» (MiniMessage)
 * @param categories    категории (могут быть пустыми — тогда меню плоское)
 * @param entries       пункты меню
 */
public record ServerMenuSettings(boolean enabled, String title, String categoryTitle, int backSlot,
                                 Material backIcon, String backName, int closeSlot, Material closeIcon,
                                 String closeName, List<Category> categories, List<Entry> entries) {

    /** Слот «Закрыть» по умолчанию: правый угол нижнего ряда страницы раздела. */
    public static final int DEFAULT_CLOSE_SLOT = 44;

    /** Иконка и подпись «Закрыть» по умолчанию. */
    public static final Material DEFAULT_CLOSE_ICON = Material.BARRIER;

    public static final String DEFAULT_CLOSE_NAME = "<red>✖ Закрыть</red>";

    public static final ServerMenuSettings DISABLED = new ServerMenuSettings(false,
            "<gold>Меню сервера</gold>", "<gold>%category%</gold>", 40, Material.ARROW,
            "<gray>◀ Назад в меню</gray>", DEFAULT_CLOSE_SLOT, DEFAULT_CLOSE_ICON, DEFAULT_CLOSE_NAME,
            List.of(), List.of());

    /** Совместимость с конфигами и тестами до 1.4.170: без явной кнопки «Закрыть». */
    public ServerMenuSettings(boolean enabled, String title, String categoryTitle, int backSlot,
                              Material backIcon, String backName, List<Category> categories,
                              List<Entry> entries) {
        this(enabled, title, categoryTitle, backSlot, backIcon, backName, DEFAULT_CLOSE_SLOT,
                DEFAULT_CLOSE_ICON, DEFAULT_CLOSE_NAME, categories, entries);
    }

    /** Категория главной страницы. */
    public record Category(String id, Material icon, String name, String lore, int slot,
                           String plugin, String permission) {
        public Category {
            id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
            name = name == null ? "" : name.trim();
            lore = lore == null ? "" : lore.trim();
            plugin = plugin == null ? "" : plugin.trim();
            permission = permission == null ? "" : permission.trim();
        }

        boolean available(java.util.function.Predicate<String> plugins,
                          java.util.function.Predicate<String> permissions) {
            return (plugin.isBlank() || plugins.test(plugin))
                    && (permission.isBlank() || permissions.test(permission));
        }
    }

    /** Пункт меню: иконка, имя/лор (MiniMessage), команда от игрока, слот (-1 = авто). */
    public record Entry(int slot, Material icon, String name, String lore, String command,
                        String plugin, String permission, String category) {
        public Entry {
            category = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
            plugin = plugin == null ? "" : plugin.trim();
            permission = permission == null ? "" : permission.trim();
            command = command == null ? "" : command.trim();
        }

        public Entry(int slot, Material icon, String name, String lore, String command) {
            this(slot, icon, name, lore, command, "", "", "");
        }

        public Entry(int slot, Material icon, String name, String lore, String command,
                     String plugin, String permission) {
            this(slot, icon, name, lore, command, plugin, permission, "");
        }

        /** Пункт-подсказка: клик ничего не делает. */
        public boolean info() {
            return command.isBlank();
        }

        boolean available(java.util.function.Predicate<String> plugins,
                          java.util.function.Predicate<String> permissions) {
            return !RetiredAddons.plugin(plugin) && !RetiredAddons.command(command)
                    && (plugin.isBlank() || plugins.test(plugin))
                    && (permission.isBlank() || permissions.test(permission));
        }
    }

    /** Есть ли у меню категории (двухуровневый режим). */
    public boolean grouped() {
        return !categories.isEmpty() && entries.stream().anyMatch(entry -> !entry.category().isBlank());
    }

    /** Пункты категории в порядке конфига. */
    public List<Entry> entriesOf(String categoryId) {
        String id = categoryId == null ? "" : categoryId.trim().toLowerCase(Locale.ROOT);
        return entries.stream().filter(entry -> entry.category().equals(id)).toList();
    }

    /** Пункты без категории: на главной странице они идут рядом с категориями. */
    public List<Entry> looseEntries() {
        return entries.stream().filter(entry -> entry.category().isBlank()).toList();
    }

    public static ServerMenuSettings from(FileConfiguration config) {
        if (config == null) {
            return DISABLED;
        }
        boolean enabled = config.getBoolean("server-menu.enabled", true);
        String title = text(config.getString("server-menu.title"), "<gold>Меню сервера</gold>");
        String categoryTitle = text(config.getString("server-menu.category-title"), "<gold>%category%</gold>");
        int backSlot = clampSlot(config.getInt("server-menu.back-slot", 40), 0, 53);
        Material backIcon = material(config.getString("server-menu.back-icon"), Material.ARROW);
        String backName = text(config.getString("server-menu.back-name"), "<gray>◀ Назад в меню</gray>");
        // -1 в конфиге задуман как «без кнопки», поэтому диапазон шире обычных слотов.
        int closeSlot = clampSlot(config.getInt("server-menu.close-slot", DEFAULT_CLOSE_SLOT), -1, 53);
        Material closeIcon = material(config.getString("server-menu.close-icon"), DEFAULT_CLOSE_ICON);
        String closeName = text(config.getString("server-menu.close-name"), DEFAULT_CLOSE_NAME);

        List<Category> categories = new ArrayList<>();
        for (Map<?, ?> map : config.getMapList("server-menu.categories")) {
            if (map == null) {
                continue;
            }
            String id = string(map.get("id"));
            String name = string(map.get("name"));
            Object slotRaw = map.get("slot");
            int slot = toInt(slotRaw, -1);
            if (id.isEmpty() || name.isEmpty() || slot < -1 || slot > 53) {
                continue;
            }
            categories.add(new Category(id, material(string(map.get("icon")), Material.CHEST), name,
                    string(map.get("lore")), slot, string(map.get("plugin")),
                    string(map.get("permission"))));
        }

        List<Entry> entries = new ArrayList<>();
        for (Map<?, ?> map : config.getMapList("server-menu.items")) {
            if (map == null) {
                continue;
            }
            int slot = toInt(map.get("slot"), -1);
            String name = string(map.get("name"));
            // Пустая команда допустима: это пункт-подсказка, клик по нему ничего не делает.
            if (name.isEmpty() || slot < -1 || slot > 53) {
                continue;
            }
            entries.add(new Entry(slot, material(string(map.get("icon")), Material.PAPER), name,
                    string(map.get("lore")), string(map.get("command")), string(map.get("plugin")),
                    string(map.get("permission")), string(map.get("category"))));
        }
        return new ServerMenuSettings(enabled, title, categoryTitle, backSlot, backIcon, backName,
                closeSlot, closeIcon, closeName, List.copyOf(categories), List.copyOf(entries));
    }

    private static String text(String raw, String fallback) {
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    private static int clampSlot(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String string(Object raw) {
        return raw == null ? "" : raw.toString().trim();
    }

    private static Material material(String raw, Material fallback) {
        String name = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (name.isEmpty()) {
            return fallback;
        }
        Material material = Material.matchMaterial(name);
        // Неизвестная иконка не должна ронять сервер: берём безопасный заменитель.
        return material == null || !material.isItem() ? fallback : material;
    }

    private static int toInt(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw != null) {
            try {
                return Integer.parseInt(raw.toString().trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
