package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item 12 из docs/IMPROVEMENTS.md: тексты вынесены в messages.yml, ключи сверяются с кодом,
 * работает MiniMessage, старые &-коды, экранирование подстановок и проверка файла для /pass reload.
 */
class MessagesTest {
    private static final Logger LOGGER = Logger.getLogger("MessagesTest");

    /** Вызовы вида messages.get("ключ", ...) и messages().send("ключ", ...). */
    private static final Pattern CALL = Pattern.compile(
            "messages(?:\\(\\))?\\.(?:send|sendList|get|getList|raw|plain|has)\\(");
    private static final Pattern LITERAL = Pattern.compile("\"([a-z][a-z0-9.\\-]*)\"");
    /** Группы ключей, которые код собирает динамически (наборы подсказок PasswordPolicy, ответы DonationService). */
    private static final List<String> DYNAMIC_PREFIXES = List.of(
            "password.error.", "password.hint.", "password.strength.", "donation.claim.");

    private static final String RESOURCE_PATH = "src/main/resources/messages.yml";

    private static Messages resource() throws Exception {
        try (InputStream stream = Files.newInputStream(Path.of(RESOURCE_PATH))) {
            return Messages.from(stream, null, LOGGER);
        }
    }

    private static Messages resourceWithOverrides(Path userFile) throws Exception {
        try (InputStream stream = Files.newInputStream(Path.of(RESOURCE_PATH))) {
            return Messages.from(stream, userFile, LOGGER);
        }
    }

    private static Messages inline(String yaml) {
        return Messages.from(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), null, LOGGER);
    }

    @Test
    void resourceContainsEveryKeyUsedInCode() throws Exception {
        Set<String> resourceKeys = resource().defaultKeys();
        Set<String> missing = new TreeSet<>(keysReferencedInCode());
        missing.removeAll(resourceKeys);

        assertTrue(missing.isEmpty(), "В messages.yml нет ключей, которые читает код: " + missing);
        assertTrue(resourceKeys.size() > 300, "Ожидается полный набор текстов, а не заглушка");
    }

    @Test
    void resourceHasNoDeadKeys() throws Exception {
        Set<String> codeKeys = keysReferencedInCode();
        Set<String> dead = new TreeSet<>();
        for (String key : resource().defaultKeys()) {
            if (codeKeys.contains(key)) continue;
            if (DYNAMIC_PREFIXES.stream().anyMatch(key::startsWith)) continue;
            dead.add(key);
        }
        assertTrue(dead.isEmpty(), "Ключи, которые никто не читает (ни код, ни динамические наборы): " + dead);
    }

    @Test
    void everyResourceTextIsRenderable() throws Exception {
        Messages messages = resource();
        for (String key : messages.defaultKeys()) {
            assertNotNull(messages.get(key), "Ключ " + key + " не отрисовался");
        }
        assertTrue(messages.getList("pass.admin-help", "label", "pass", "grant_days", 30).size() >= 15,
                "Справка администратора должна быть многострочным списком");
        assertTrue(messages.getList("two-factor.help", "label", "2fa").size() >= 4,
                "Справка по 2FA должна быть многострочным списком");
    }

    @Test
    void miniMessageRendersColoursPlaceholdersAndLists() {
        Messages messages = inline("""
                messages:
                  greeting: "<gradient:gold:yellow>Привет, %player%!</gradient>"
                  legacy: "&cОшибка &lважная"
                  lines: "первая строка\\nвторая строка"
                """);

        assertEquals("Привет, Стив!", messages.plain("greeting", "player", "Стив"));
        assertEquals("Привет, %player%!", messages.plain("greeting"), "Без значения подстановка остаётся текстом");

        Component legacy = messages.get("legacy");
        assertEquals("Ошибка важная", messages.plain("legacy"));
        assertEquals(NamedTextColor.RED, legacy.color(), "&-код &c должен превратиться в красный");

        List<Component> lines = messages.getList("lines");
        assertEquals(2, lines.size());
        assertEquals("первая строка", ChatService.plainText(lines.get(0)));
        assertEquals("вторая строка", ChatService.plainText(lines.get(1)));
    }

    @Test
    void placeholderValuesAreEscaped() {
        Messages messages = inline("""
                messages:
                  warn: "<red>Нарушитель: %player%</red>"
                """);

        String plain = messages.plain("warn", "player", "<bold>НеНик</bold>");
        assertTrue(plain.contains("<bold>НеНик</bold>"), "Разметка из значения не должна исполняться: " + plain);
        assertEquals("\\<bold>НеНик\\</bold>", Messages.escape("<bold>НеНик</bold>"));
        assertEquals("обычный ник", Messages.escape("обычный ник"));
    }

    @Test
    void missingKeysFallBackToBundledDefaults(@TempDir Path tempDir) throws Exception {
        Path userFile = tempDir.resolve("messages.yml");
        Files.writeString(userFile, """
                messages:
                  no-pass.title: "<green>Свой заголовок</green>"
                """, StandardCharsets.UTF_8);

        Messages defaults = resource();
        Messages messages = resourceWithOverrides(userFile);

        assertEquals("<green>Свой заголовок</green>", messages.template("no-pass.title"),
                "Правка из файла имеет приоритет");
        assertEquals(Set.of("no-pass.title"), messages.overrideKeys());
        assertTrue(messages.missingKeys().contains("no-pass.title-subtitle"),
                "Остальные ключи берутся из ресурса плагина");
        assertTrue(messages.has("no-pass.title-subtitle"), "has() видит значения по умолчанию");
        assertEquals(defaults.template("no-pass.reminder"), messages.template("no-pass.reminder"),
                "Неправленные ключи продолжают работать после обновления плагина");
        assertEquals("Донат 150₽ на DonatePay (ник: Steve)", messages.plain("no-pass.title-subtitle",
                "price", 150, "player", "Steve"));
        assertEquals(List.of(), messages.validate(), "Корректный файл не должен давать замечаний");
    }

    @Test
    void validateReportsProblemsForReloadCommand(@TempDir Path tempDir) throws Exception {
        Path userFile = tempDir.resolve("messages.yml");
        Files.writeString(userFile, """
                messages:
                  no-pass.title: "<red>Проходка не активна</red>"
                  no-pass.title-subtitle: "<yellow>Донат 150₽ на DonatePay</yellow>"
                  totally.unknown.key: "<red>лишний ключ</red>"
                """, StandardCharsets.UTF_8);

        Messages messages = resourceWithOverrides(userFile);
        List<String> problems = messages.validate();

        assertEquals(3, problems.size(),
                "Ожидаются три замечания: лишний ключ и две потерянные подстановки: " + problems);
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("totally.unknown.key")),
                "Неизвестный ключ должен попасть в отчёт /pass reload: " + problems);
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("%player%")) && problems.stream()
                        .anyMatch(problem -> problem.contains("%price%")),
                "Потерянные подстановки должны попасть в отчёт /pass reload: " + problems);
    }

    @Test
    void validateReportsUnclosedMiniMessageTag() {
        assertEquals("red", Messages.firstUnclosedTag("<red>текст"));
        assertNull(Messages.firstUnclosedTag("<red>текст</red>"));
        assertNull(Messages.firstUnclosedTag("<yellow>Использование: /pass add <ник></yellow>"),
                "Литеральные заготовки вида <ник> ошибкой не считаются");
        assertEquals("bold", Messages.firstUnclosedTag("<red><bold>жирный</red>"));
        assertEquals("hover", Messages.firstUnclosedTag(
                "<click:suggest_command:'/login '><hover:show_text:'подсказка'>вход"));
    }

    @Test
    void legacyConversionAndStripFormattingWork() {
        assertEquals("<red>текст<reset>", Messages.legacyToMini("&cтекст&r"));
        assertEquals("Привет мир", Messages.stripFormatting("<red>Привет </red><bold>мир</bold>"));
        assertEquals("Цена: 150 ₽", Messages.stripFormatting("<yellow>Цена: <gold>150</gold> ₽</yellow>"));
        assertEquals("Цена: 150 ₽", Messages.stripFormatting("&eЦена: &6150&r ₽"));
        assertEquals("Ошибка", ChatService.plainText(Messages.format("&cОшибка")));
    }

    /** Собирает ключи, которые код передаёт в Messages: первый и последующие строковые литералы вызова. */
    private static Set<String> keysReferencedInCode() throws Exception {
        Set<String> keys = new LinkedHashSet<>();
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(Path.of("src/main/java"))) {
            stream.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
        }
        for (Path source : sources) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            Matcher call = CALL.matcher(text);
            while (call.find()) {
                int depth = 1;
                int index = call.end();
                while (index < text.length() && depth > 0) {
                    char symbol = text.charAt(index);
                    if (symbol == '(') {
                        depth++;
                    } else if (symbol == ')') {
                        depth--;
                    } else if (symbol == '"') {
                        int end = text.indexOf('"', index + 1);
                        if (end < 0) break;
                        index = end;
                    }
                    index++;
                }
                String arguments = text.substring(call.end(), Math.max(call.end(), index - 1));
                Matcher literal = LITERAL.matcher(arguments);
                while (literal.find()) {
                    String key = literal.group(1);
                    if (key.indexOf('.') > 0) {
                        keys.add(key);
                    }
                }
            }
        }
        return keys;
    }
}
