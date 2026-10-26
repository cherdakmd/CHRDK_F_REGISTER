package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Хранилище всех текстов плагина.
 *
 * <p>Значения читаются из {@code plugins/CHRDK_Core/messages.yml}. Ключи, которых нет в файле
 * пользователя, автоматически берутся из ресурса плагина, поэтому обновление версии никогда не ломает
 * уже настроенный файл. Тексты поддерживают разметку <b>MiniMessage</b>
 * ({@code <red>}, {@code <gradient>}, {@code <hover>}, {@code <click>}) и старые {@code &}-коды
 * (они автоматически конвертируются в MiniMessage), а также подстановки вида {@code %player%}.</p>
 *
 * <p>Все значения, подставляемые в шаблон, экранируются, поэтому ник игрока или текст сообщения
 * не может «сломать» разметку или подделать цвет.</p>
 */
public final class Messages {
    /** Имя файла в папке плагина. */
    public static final String FILE_NAME = "messages.yml";
    /** Ресурс с полным набором значений по умолчанию. */
    public static final String RESOURCE_NAME = "messages.yml";

    private static final Pattern PLACEHOLDER = Pattern.compile("%([A-Za-z0-9_.-]{1,48})%");
    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)&([0-9a-fk-or])");
    private static final Map<Character, String> LEGACY_TAGS = Map.ofEntries(
            Map.entry('0', "black"),
            Map.entry('1', "dark_blue"),
            Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"),
            Map.entry('4', "dark_red"),
            Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"),
            Map.entry('7', "gray"),
            Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"),
            Map.entry('a', "green"),
            Map.entry('b', "aqua"),
            Map.entry('c', "red"),
            Map.entry('d', "light_purple"),
            Map.entry('e', "yellow"),
            Map.entry('f', "white"),
            Map.entry('k', "obfuscated"),
            Map.entry('l', "bold"),
            Map.entry('m', "strikethrough"),
            Map.entry('n', "underlined"),
            Map.entry('o', "italic"),
            Map.entry('r', "reset"));

    private final Map<String, String> defaults;
    private final Map<String, String> overrides;
    private final Logger logger;
    private final MiniMessage mini = MiniMessage.miniMessage();
    /** Теги, которые MiniMessage точно понимает: по ним проверяем парность в /pass reload. */
    private static final Pattern MINI_TAG = Pattern.compile(
            "</?(black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|dark_gray|blue|green|"
                    + "aqua|red|light_purple|yellow|white|obfuscated|bold|strikethrough|underlined|italic|"
                    + "reset|newline|rainbow|gradient|transition|hover|click|key|translate|lang|score|"
                    + "selector|nbt|font|shadow)[:>]");
    private final Set<String> requested = ConcurrentHashMap.newKeySet();
    private final Set<String> warnedMissing = ConcurrentHashMap.newKeySet();

    private Messages(Map<String, String> defaults, Map<String, String> overrides, Logger logger) {
        this.defaults = defaults;
        this.overrides = overrides;
        this.logger = logger;
    }

    /** Загружает тексты для работающего плагина: значения по умолчанию из JAR + настройки с диска. */
    public static Messages load(JavaPlugin plugin, String locale, Logger logger) {
        Map<String, String> defaults = readResource(plugin, RESOURCE_NAME, logger);
        // Тег локали чистим от всего, кроме букв, цифр и дефиса: имя файла не
        // должно уводить за пределы папки плагина.
        String tag = locale == null ? "" : locale.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-').replaceAll("[^a-z0-9-]", "");
        String fileName = tag.isEmpty() || "ru".equals(tag)
                ? FILE_NAME
                : "messages_" + tag + ".yml";
        if (!FILE_NAME.equals(fileName) && plugin.getResource(fileName) == null) {
            // Локали нет в JAR: не роняем плагин на saveResource, а работаем по-русски.
            if (logger != null) {
                logger.info("Локали «" + locale + "» нет в JAR — взяты тексты " + FILE_NAME + ".");
            }
            fileName = FILE_NAME;
        }
        if (!FILE_NAME.equals(fileName)) {
            Map<String, String> localeDefaults = readResource(plugin, fileName, logger);
            if (!localeDefaults.isEmpty()) {
                Map<String, String> merged = new LinkedHashMap<>(defaults);
                merged.putAll(localeDefaults);
                defaults = merged;
            }
        }

        Path userFile = plugin.getDataFolder().toPath().resolve(fileName);
        if (!Files.exists(userFile)) {
            plugin.saveResource(fileName, false);
        }
        Map<String, String> overrides = new LinkedHashMap<>(readFile(userFile));
        String moduleName = plugin.getName();
        if ("CHRDK_Core".equalsIgnoreCase(moduleName)) {
            Map<String, String> legacy = readResource(plugin, "shop-delivery-messages-legacy-1.4.193.yml", logger);
            migrateStockOverrides(defaults, overrides, legacy);
        }
        overrides.replaceAll((key, text) -> GameplayRewardTexts.message(moduleName, key, text));
        Messages messages = new Messages(defaults, overrides, logger);
        messages.logMissingKeys(fileName);
        return messages;
    }

    static void migrateStockOverrides(Map<String, String> defaults, Map<String, String> overrides,
                                      Map<String, String> legacy) {
        for (var entry : legacy.entrySet()) {
            if (java.util.Objects.equals(overrides.get(entry.getKey()), entry.getValue()) && defaults.containsKey(entry.getKey()))
                overrides.put(entry.getKey(), defaults.get(entry.getKey()));
        }
    }

    /** Конструктор для тестов и внешних загрузчиков: значения по умолчанию — поток, правки — файл. */
    public static Messages from(InputStream defaultsStream, Path userFile, Logger logger) {
        Map<String, String> defaults = read(defaultsStream);
        Map<String, String> overrides = readFile(userFile);
        return new Messages(defaults, overrides, logger);
    }

    // ------------------------------------------------------------------ доступ

    /** Готовый компонент по ключу. Аргументы — пары «имя, значение» для подстановок. */
    public Component get(String key, Object... placeholders) {
        return mini.deserialize(render(key, placeholders));
    }

    /** Список компонентов по ключу (для многострочных текстов). */
    public List<Component> getList(String key, Object... placeholders) {
        List<Component> result = new ArrayList<>();
        for (String line : template(key).split("\n", -1)) {
            result.add(mini.deserialize(fill(legacyToMini(line), placeholders)));
        }
        return result;
    }

    /**
     * Убирает MiniMessage-разметку из произвольной строки.
     *
     * <p>Нужно для имён из конфига (например, названий товаров): в чат они идут
     * как разметка, а в консоль и в строку журнала — обычным текстом.</p>
     */
    public static String stripTags(String raw) {
        return raw == null ? "" : raw.replaceAll("<[^>]+>", "").trim();
    }

    /**
     * Компонент из готовой разметки самого плагина.
     *
     * <p>Нужен там, где строка уже собрана кодом (например, ник с метками
     * аддонов), а показать её надо как украшение предмета. Ввод игроков сюда
     * передавать нельзя — только результат {@code decoratedName} и подобное.</p>
     */
    public Component markup(String miniMessageMarkup) {
        return mini.deserialize(miniMessageMarkup == null ? "" : miniMessageMarkup);
    }

    /** Текст без разметки — для логов, консоли и сравнений. */
    public String plain(String key, Object... placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(get(key, placeholders));
    }

    /** Сырой шаблон с подставленными (уже экранированными) значениями — для кода, который строит строку. */
    public String raw(String key, Object... placeholders) {
        return fill(template(key), placeholders);
    }

    /**
     * Готовая разметка из {@code messages.yml} для подстановки в другой шаблон.
     *
     * <p>Обычная строка в подстановке экранируется ({@link #escape}), поэтому
     * {@code raw} внутри {@code get}/{@code send} вывел бы теги литералами.
     * Значения самого плагина — не пользовательский ввод, их разметка должна
     * остаться живой; для этого и нужен {@link Trusted}.</p>
     */
    public Trusted mini(String key, Object... placeholders) {
        return trusted(raw(key, placeholders));
    }

    /** Отправляет игроку или в консоль текст по ключу. */
    public void send(CommandSender sender, String key, Object... placeholders) {
        if (sender == null) return;
        sender.sendMessage(get(key, placeholders));
    }

    /** Отправляет многострочный текст по ключу. */
    public void sendList(CommandSender sender, String key, Object... placeholders) {
        if (sender == null) return;
        for (Component line : getList(key, placeholders)) {
            sender.sendMessage(line);
        }
    }

    /** Есть ли такой ключ в файле (пользователя или в значениях по умолчанию). */
    public boolean has(String key) {
        return overrides.containsKey(key) || defaults.containsKey(key);
    }

    /** Рендер произвольного шаблона (например, формата чата из config.yml) с экранированием значений. */
    public Component renderTemplate(String template, Object... placeholders) {
        if (template == null) return Component.empty();
        return mini.deserialize(fill(legacyToMini(template), placeholders));
    }

    /** Значение по ключу без разметки и подстановок. */
    public String template(String key) {
        requested.add(key);
        String value = overrides.get(key);
        if (value == null) value = defaults.get(key);
        if (value == null) {
            if (warnedMissing.add(key)) {
                logger.warning("В messages.yml нет ключа '" + key + "' — текст не будет показан. "
                        + "Удалите файл и перезапустите сервер, чтобы получить актуальный набор ключей.");
            }
            return "";
        }
        return value;
    }

    /** Ключи, которые код запрашивал хотя бы один раз. */
    public Set<String> requestedKeys() {
        return Set.copyOf(requested);
    }

    /** Все ключи значений по умолчанию. */
    public Set<String> defaultKeys() {
        return Set.copyOf(defaults.keySet());
    }

    /** Все ключи, загруженные из файла пользователя. */
    public Set<String> overrideKeys() {
        return Set.copyOf(overrides.keySet());
    }

    /** Ключи, которых не хватает в файле пользователя (они берутся из значений по умолчанию). */
    public Set<String> missingKeys() {
        Set<String> missing = new LinkedHashSet<>(defaults.keySet());
        missing.removeAll(overrides.keySet());
        return missing;
    }

    /**
     * Проверяет тексты: ошибки разметки MiniMessage и потерянные подстановки.
     *
     * @return список проблем для администратора (пустой — всё в порядке)
     */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(overrides).entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!defaults.containsKey(key)) {
                problems.add("messages.yml: лишний ключ '" + key + "' (в этой версии плагина не используется)");
                continue;
            }
            if (value == null) continue;
            try {
                mini.deserialize(legacyToMini(value));
            } catch (Exception exception) {
                problems.add("messages.yml: ошибка разметки в '" + key + "': " + exception.getMessage());
            }
            String unclosed = firstUnclosedTag(value);
            if (unclosed != null) {
                problems.add("messages.yml: в '" + key + "' тег <" + unclosed + "> не закрыт — "
                        + "вся строка после него будет перекрашена");
            }
            Set<String> expected = placeholders(defaults.get(key));
            Set<String> actual = placeholders(value);
            for (String placeholder : expected) {
                if (!actual.contains(placeholder)) {
                    problems.add("messages.yml: в '" + key + "' потеряна подстановка %" + placeholder + "%");
                }
            }
        }
        return problems;
    }

    // -------------------------------------------------------------- внутреннее

    private String render(String key, Object... placeholders) {
        return fill(legacyToMini(template(key)), placeholders);
    }

    private String fill(String template, Object... placeholders) {
        if (placeholders == null || placeholders.length == 0) return template;
        String result = template;
        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            String name = placeholderName(placeholders[index]);
            if (name.isEmpty()) continue;
            Object replacement = placeholders[index + 1];
            String value = replacement instanceof Trusted trusted ? trusted.text() : escape(replacement);
            result = result.replace("%" + name + "%", value);
        }
        return result;
    }

    /**
     * Имя подстановки без обрамляющих процентов.
     *
     * <p>Код вправе передать и {@code "latest"}, и {@code "%latest%"}: второе
     * исторически писали рядом с шаблоном, и без нормализации получалось
     * {@code %%latest%%} — подстановка не срабатывала, а игрок видел сырой
     * {@code %latest%} в чате. Пустое имя пропускается: {@code "%%"} в тексте
     * заменять нечем.</p>
     */
    private static String placeholderName(Object raw) {
        String name = raw == null ? "" : String.valueOf(raw).trim();
        while (name.length() >= 2 && name.startsWith("%") && name.endsWith("%")) {
            name = name.substring(1, name.length() - 1).trim();
        }
        return name;
    }

    /** Значение подстановки с уже готовой MiniMessage-разметкой: вставляется в шаблон без экранирования. */
    public record Trusted(String text) { }

    /** Обёртка для внутренних плагинных строк с MiniMessage-тегами. НЕ использовать для ввода игроков. */
    public static Trusted trusted(String miniMessage) {
        return new Trusted(miniMessage == null ? "" : miniMessage);
    }

    /** Экранирует значение, чтобы ник или текст сообщения не могли вставить разметку. */
    public static String escape(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        if (text.indexOf('<') < 0 && text.indexOf('\\') < 0) return text;
        return text.replace("\\", "\\\\").replace("<", "\\<");
    }

    /** Переводит старые {@code &}-коды в теги MiniMessage (обратная совместимость). */
    public static String legacyToMini(String legacy) {
        if (legacy == null) return null;
        // Old live messages.yml used HTML brackets. Escape the opening bracket BEFORE parsing
        // colours: &lt; otherwise looks like the legacy &l (bold) code. Never create a live tag.
        String normalized = legacy.replace("&lt;", "\\<").replace("&gt;", ">");
        if (normalized.indexOf('&') < 0) return normalized;
        Matcher matcher = LEGACY_CODE.matcher(normalized);
        StringBuilder result = new StringBuilder(normalized.length() + 16);
        while (matcher.find()) {
            String tag = LEGACY_TAGS.get(Character.toLowerCase(matcher.group(1).charAt(0)));
            matcher.appendReplacement(result, tag == null ? Matcher.quoteReplacement(matcher.group())
                    : ("<" + tag + ">"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static final MiniMessage STATIC_RENDERER = MiniMessage.miniMessage();

    /** Рендерит произвольный шаблон (поддерживает MiniMessage и старые {@code &}-коды). */
    public static Component format(String template) {
        if (template == null || template.isEmpty()) return Component.empty();
        return STATIC_RENDERER.deserialize(legacyToMini(template));
    }

    /** Убирает цветовые коды и теги из строки (для консоли и логов). */
    public static String stripFormatting(String text) {
        if (text == null) return "";
        return PlainTextComponentSerializer.plainText()
                .serialize(MiniMessage.miniMessage().deserialize(legacyToMini(text)));
    }

    private void logMissingKeys(String fileName) {
        Set<String> missing = missingKeys();
        if (!missing.isEmpty()) {
            logger.info("messages.yml: " + missing.size() + " ключ(ей) отсутствуют в " + fileName
                    + " и взяты из значений по умолчанию (файл не изменён).");
        }
    }

    private static Map<String, String> readResource(JavaPlugin plugin, String name, Logger logger) {
        try (InputStream stream = plugin.getResource(name)) {
            if (stream == null) return Map.of();
            return read(stream);
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Не удалось прочитать ресурс " + name, exception);
            return Map.of();
        }
    }

    private static Map<String, String> read(InputStream stream) {
        if (stream == null) return Map.of();
        YamlConfiguration configuration = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            configuration.load(reader);
        } catch (Exception exception) {
            return Map.of();
        }
        return flatten(configuration);
    }

    private static Map<String, String> readFile(Path file) {
        if (file == null || !Files.exists(file)) return Map.of();
        YamlConfiguration configuration = new YamlConfiguration();
        try (java.io.BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            configuration.load(reader);
        } catch (Exception exception) {
            return Map.of();
        }
        return flatten(configuration);
    }

    private static Map<String, String> flatten(ConfigurationSection section) {
        // Файл читается с корневой секцией messages: — в коде ключи используются без неё.
        ConfigurationSection root = section.isConfigurationSection("messages")
                ? section.getConfigurationSection("messages")
                : section;
        // Аддоны оборачивают файл в единственную верхнюю секцию (например, "enchants:" или
        // "tiers:"): разворачиваем и её, чтобы ключи в коде шли без префикса обёртки.
        if (root == section) {
            java.util.Set<String> top = section.getKeys(false);
            if (top.size() == 1) {
                String only = top.iterator().next();
                if (section.isConfigurationSection(only)) {
                    root = section.getConfigurationSection(only);
                }
            }
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : root.getKeys(true)) {
            if (root.isConfigurationSection(key)) continue;
            Object value = root.get(key);
            if (value == null) continue;
            if (value instanceof List<?> list) {
                StringBuilder joined = new StringBuilder();
                for (Object element : list) {
                    if (joined.length() > 0) joined.append('\n');
                    joined.append(element);
                }
                result.put(key, joined.toString());
            } else {
                result.put(key, String.valueOf(value));
            }
        }
        return result;
    }

    private static Set<String> placeholders(String template) {
        Set<String> result = new LinkedHashSet<>();
        if (template == null) return result;
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    /**
     * Ищет незакрытый тег MiniMessage. Литеральные «заготовки» вида {@code <ник>} или {@code <файл>}
     * ошибкой не считаются — они и так остаются текстом.
     */
    static String firstUnclosedTag(String text) {
        if (text == null || text.indexOf('<') < 0) return null;
        Matcher matcher = MINI_TAG.matcher(text);
        List<String> open = new ArrayList<>();
        while (matcher.find()) {
            String raw = matcher.group();
            String name = matcher.group(1);
            if (raw.startsWith("</")) {
                if (!open.isEmpty() && open.get(open.size() - 1).equals(name)) {
                    open.remove(open.size() - 1);
                }
            } else if (!"reset".equals(name) && !"newline".equals(name)) {
                open.add(name);
            }
        }
        return open.isEmpty() ? null : open.get(open.size() - 1);
    }

    /** Помощник для тестов и диагностики: путь к файлу текстов в папке плагина. */
    public static Path userFile(JavaPlugin plugin) {
        return new File(plugin.getDataFolder(), FILE_NAME).toPath();
    }
}
