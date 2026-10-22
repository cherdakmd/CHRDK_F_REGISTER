package ru.cherdakmd.chrdkclaims;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Обновление стоковых значений уведомлений о границах приватов.
 *
 * <p>До 1.4.147 уведомления по умолчанию шли только в строку над хотбаром и
 * молчали на собственных приватах: {@code notice-mode: "actionbar"} и
 * {@code notice-own: false}. Игрок, у которого уже лежит старый файл, не увидел
 * бы новое поведение до ручной правки, поэтому ровно эти два стоковых значения
 * заменяются на новые ({@code "both"} и {@code true}).</p>
 *
 * <p>Правило то же, что в миграции презентации ядра: меняется <b>только точное
 * стоковое значение</b>. Свой канал ({@code chat}, {@code actionbar},
 * {@code both}) модуль не переписывает. У {@code notice-own} стоковое значение
 * совпадает с осознанным «выключить», различить их в файле нельзя, поэтому
 * {@code false} тоже обновляется до {@code true}: после обновления так видно
 * новые сообщения, а вернуть тишину можно той же строкой в config.yml.</p>
 *
 * <p>Файл правится построчно, а не пересохраняется через YAML: русские
 * комментарии, порядок ключей и пустые строки остаются на месте. Миграция
 * одноразовая — рядом с конфигом появляется метка {@value #MARKER}, иначе
 * выбранные вручную значения откатывались бы при каждом запуске.</p>
 */
public final class ClaimNoticeMigration {

    /** Версия поведения уведомлений; метка в {@value #MARKER} её подтверждает. */
    static final int VERSION = 2;
    /** Файл-метка в папке плагина: миграция уже выполнена. */
    static final String MARKER = "notice-migration.txt";

    static final String SECTION = "general";
    static final String MODE_KEY = "notice-mode";
    static final String OWN_KEY = "notice-own";

    /** Прежнее стоковое значение канала. */
    static final String OLD_MODE = "actionbar";
    /** Новое стоковое значение канала: чат и хотбар вместе. */
    static final String NEW_MODE = "both";

    private static final Pattern SECTION_START =
            Pattern.compile("^" + SECTION + "\\s*:\\s*(#.*)?$");
    private static final Pattern TOP_LEVEL = Pattern.compile("^[^\\s#].*");
    /** Строка вида «  ключ: значение  # комментарий». */
    private static final Pattern ENTRY =
            Pattern.compile("^(\\s+)([A-Za-z0-9_.-]+)\\s*:\\s*(\"[^\"]*\"|'[^']*'|[^#\\s]*)\\s*(#.*)?$");

    private ClaimNoticeMigration() {
    }

    /** Результат построчной миграции. */
    public record Result(List<String> lines, List<String> changed) {
        /** Файл отличается от исходного. */
        public boolean dirty() {
            return !changed.isEmpty();
        }
    }

    /**
     * Переписывает стоковые строки секции {@code general}.
     *
     * <p>Ключи в других секциях и любые «не стоковые» значения не трогаются:
     * {@code chat}, {@code both}, {@code true} и весь остальной файл остаются
     * байт в байт.</p>
     */
    public static Result rewrite(List<String> lines) {
        if (lines == null) {
            return new Result(List.of(), List.of());
        }
        List<String> out = new ArrayList<>(lines.size());
        List<String> changed = new ArrayList<>(2);
        boolean inGeneral = false;
        for (String line : lines) {
            if (SECTION_START.matcher(line).matches()) {
                inGeneral = true;
                out.add(line);
                continue;
            }
            if (inGeneral && TOP_LEVEL.matcher(line).matches()) {
                inGeneral = false;
            }
            String replaced = inGeneral ? replaceEntry(line, changed) : null;
            out.add(replaced == null ? line : replaced);
        }
        return new Result(List.copyOf(out), List.copyOf(changed));
    }

    /**
     * Применяет миграцию к файлу конфига.
     *
     * @return {@code true}, если файл изменён и его нужно перечитать
     */
    public static boolean apply(Path configFile, Path dataFolder, Logger logger) {
        if (configFile == null || dataFolder == null) {
            return false;
        }
        Path marker = dataFolder.resolve(MARKER);
        if (done(marker)) {
            return false;
        }
        boolean changed = false;
        try {
            if (Files.exists(configFile)) {
                Result result = rewrite(Files.readAllLines(configFile, StandardCharsets.UTF_8));
                if (result.dirty()) {
                    write(configFile, result.lines());
                    changed = true;
                    if (logger != null) {
                        logger.info("Уведомления о границах приватов обновлены в config.yml ("
                                + String.join(", ", result.changed())
                                + "): сообщения идут в чат и над хотбаром, включая свои приваты. "
                                + "Вернуть прежнее поведение можно правкой этих строк.");
                    }
                }
            }
            Files.write(marker, String.valueOf(VERSION).getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            if (logger != null) {
                logger.log(Level.WARNING, "Не удалось обновить стоковые значения уведомлений "
                        + "о границах приватов; config.yml оставлен без изменений", exception);
            }
        }
        return changed;
    }

    /** Миграция уже выполнялась. */
    private static boolean done(Path marker) {
        if (marker == null || !Files.exists(marker)) {
            return false;
        }
        try {
            String text = Files.readString(marker, StandardCharsets.UTF_8).trim();
            return Integer.parseInt(text) >= VERSION;
        } catch (IOException | NumberFormatException exception) {
            // Битый маркер — считаем, что миграции не было: повтор безопасен.
            return false;
        }
    }

    /** Замена одной строки; {@code null} — строка не стоковая. */
    private static String replaceEntry(String line, List<String> changed) {
        Matcher matcher = ENTRY.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        String key = matcher.group(2);
        String value = unquote(matcher.group(3)).toLowerCase(Locale.ROOT);
        String comment = matcher.group(4) == null ? "" : " " + matcher.group(4);
        if (MODE_KEY.equals(key) && OLD_MODE.equals(value)) {
            changed.add(SECTION + "." + MODE_KEY);
            return matcher.group(1) + MODE_KEY + ": \"" + NEW_MODE + "\"" + comment;
        }
        if (OWN_KEY.equals(key) && "false".equals(value)) {
            changed.add(SECTION + "." + OWN_KEY);
            return matcher.group(1) + OWN_KEY + ": true" + comment;
        }
        return null;
    }

    private static String unquote(String raw) {
        if (raw == null || raw.length() < 2) {
            return raw == null ? "" : raw;
        }
        char first = raw.charAt(0);
        char last = raw.charAt(raw.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    /** Замена файла через временный: сбой записи не оставит половину конфига. */
    private static void write(Path target, List<String> lines) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
