package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Движок версий схемы YAML: добавляет новые ключи в существующий файл владельца,
 * НЕ перезаписывая его значения, комментарии и форматирование.
 *
 * <p>Работает с текстом, а не с {@link YamlConfiguration#save}: сохранение через
 * Bukkit переформатирует весь файл и уничтожает комментарии, что недопустимо для
 * конфигов CHRDK. Движок вставляет только отсутствующие поддеревья, копируя их
 * из эталонного ресурса JAR вместе с комментариями и пересчитывая шаг отступа под
 * файл владельца.</p>
 *
 * <p>Идемпотентность обеспечивается маркером версии: дополнение выполняется
 * только на переходе {@code current -> target}. Файл с актуальной версией не
 * меняется вообще, поэтому владелец может удалить или поправить любой ключ и
 * быть уверенным, что следующий рестарт его не вернёт. Версия в файле выше, чем
 * в JAR (откат сборки), пропускается без правок.</p>
 *
 * <p>Класс не зависит от работающего сервера: все входы — строки, поэтому
 * логика целиком покрывается JUnit без Bukkit и JVM-сервера.</p>
 */
final class ConfigSchemaEngine {

    /** Строка-ключ YAML: отступ, имя (в кавычках или без) и значение. */
    private static final Pattern KEY_LINE = Pattern.compile(
            "^(\\s*)(?:\"([^\"]+)\"|'([^']+)'|([A-Za-z0-9_.\\-]+))\\s*:(?:\\s*(.*))?$");
    private static final Pattern LEADING_SPACES = Pattern.compile("^( *)(.*)$");

    /** Как хранить маркер версии. */
    enum MarkerMode {
        /** Корневой ключ {@code config-version: N}; безопасно для config.yml. */
        KEY,
        /**
         * Комментарий {@code # messages-version: N}. Для messages.yml корневой
         * ключ нельзя: {@link Messages} разворачивает единственную верхнюю секцию,
         * и второй корневой ключ превратил бы все тексты в {@code messages.*}.
         */
        COMMENT
    }

    /** Одна миграция N-1 → N: переименования и удаление мёртвых ключей. */
    record Step(Map<String, String> renames, Set<String> removals) {
        static final Step EMPTY = new Step(Map.of(), Set.of());
    }

    /** Параметры обработки одного файла. */
    record Spec(String fileName, String marker, MarkerMode mode, int target,
                Map<Integer, Step> steps) {
    }

    /** Результат: новый текст (или исходный), была ли запись, строки для лога. */
    record Result(String text, boolean changed, List<String> report) {
        static Result unchanged(String text, String reason) {
            return new Result(text, false, List.of(reason));
        }
    }

    private record KeyLine(int index, int indent, String key, String path, String raw) {
    }

    private ConfigSchemaEngine() {
    }

    /**
     * Обновляет файл владельца до целевой версии схемы.
     *
     * @param reference текст эталонного ресурса из JAR
     * @param user      текущий текст файла владельца
     */
    static Result apply(String reference, String user, Spec spec) {
        if (reference == null || user == null || spec == null || spec.target() < 1) {
            return Result.unchanged(user, "Пропущено: нет эталонного ресурса");
        }
        Set<String> referenceKeys = keys(reference);
        if (referenceKeys == null || referenceKeys.isEmpty() || keys(user) == null) {
            // Битый YAML владельца или ресурса: не трогаем файл, пусть плагин
            // сам решит, как читать конфиг с дефолтами.
            return Result.unchanged(user, "Пропущено: YAML не читается, файл не изменён");
        }

        int current = readMarker(user, spec);
        if (current > spec.target()) {
            return Result.unchanged(user, spec.fileName() + ": схема v" + current
                    + " новее сборки (v" + spec.target() + "), файл не изменён");
        }
        if (current == spec.target()) {
            return Result.unchanged(user, spec.fileName() + ": схема v" + current + " актуальна");
        }

        List<String> report = new ArrayList<>();
        String work = user;
        for (int version = current + 1; version <= spec.target(); version++) {
            Step step = spec.steps().getOrDefault(version, Step.EMPTY);
            if (!step.renames().isEmpty() || !step.removals().isEmpty()) {
                work = applyStep(work, step, version, report);
            }
        }
        work = addMissing(work, reference, referenceKeys, spec, report);
        work = writeMarker(work, spec, spec.target());

        Set<String> produced = keys(work);
        if (produced == null || !produced.containsAll(referenceKeys)) {
            return Result.unchanged(user, spec.fileName()
                    + ": результат не прошёл проверку YAML, файл не изменён");
        }
        if (work.equals(user)) {
            return Result.unchanged(user, spec.fileName() + ": схема v" + spec.target()
                    + ", новых ключей нет");
        }
        report.add(spec.fileName() + ": схема v" + current + " → v" + spec.target());
        return new Result(work, true, List.copyOf(report));
    }

    // ------------------------------------------------------------------
    // Маркер версии
    // ------------------------------------------------------------------

    /** 0 означает «маркера нет», то есть файл создан до появления схемы. */
    static int readMarker(String text, Spec spec) {
        Pattern pattern = spec.mode() == MarkerMode.COMMENT
                ? Pattern.compile("^\\s*#\\s*" + Pattern.quote(spec.marker()) + "\\s*:\\s*(\\d+)\\b.*$")
                : Pattern.compile("^" + Pattern.quote(spec.marker()) + "\\s*:\\s*(\\d+)\\b.*$");
        for (String line : text.split("\n", -1)) {
            Matcher matcher = pattern.matcher(stripCr(line));
            if (matcher.matches()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String writeMarker(String text, Spec spec, int target) {
        Pattern pattern = spec.mode() == MarkerMode.COMMENT
                ? Pattern.compile("^(\\s*#\\s*" + Pattern.quote(spec.marker()) + "\\s*:\\s*)\\d+(\\b.*)$")
                : Pattern.compile("^(" + Pattern.quote(spec.marker()) + "\\s*:\\s*)\\d+(\\b.*)$");
        List<String> lines = new ArrayList<>(List.of(normalize(text).split("\n", -1)));
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = pattern.matcher(lines.get(i));
            if (matcher.matches()) {
                lines.set(i, matcher.group(1) + target + matcher.group(2));
                return String.join("\n", lines);
            }
        }
        String header = spec.mode() == MarkerMode.COMMENT
                ? "# " + spec.marker() + ": " + target + "  — версия схемы текстов; не удаляйте."
                : "# Версия схемы конфигурации: не удаляйте, она защищает владельческие правки.\n"
                + spec.marker() + ": " + target;
        List<String> updated = new ArrayList<>();
        updated.add(header);
        updated.add("");
        updated.addAll(lines);
        return String.join("\n", updated);
    }

    // ------------------------------------------------------------------
    // Карты миграций N-1 → N
    // ------------------------------------------------------------------

    private static String applyStep(String text, Step step, int version, List<String> report) {
        String work = text;
        for (Map.Entry<String, String> entry : step.renames().entrySet()) {
            String old = entry.getKey();
            String replacement = entry.getValue();
            if (!parent(old).equals(parent(replacement))) {
                report.add("v" + version + ": переименование " + old + " → " + replacement
                        + " пропущено (разные секции)");
                continue;
            }
            int unit = indentUnit(work);
            Map<String, KeyLine> parsed = parse(work, unit);
            KeyLine line = parsed.get(old);
            if (line == null) {
                continue;
            }
            String oldKey = old.substring(old.lastIndexOf('.') + 1);
            String newKey = replacement.substring(replacement.lastIndexOf('.') + 1);
            String prefix = line.raw().substring(0, line.raw().indexOf(oldKey));
            String suffix = line.raw().substring(line.raw().indexOf(oldKey) + oldKey.length());
            List<String> lines = new ArrayList<>(List.of(work.split("\n", -1)));
            lines.set(line.index(), prefix + newKey + suffix);
            work = String.join("\n", lines);
            report.add("v" + version + ": переименован " + old + " → " + replacement
                    + " (значение владельца сохранено)");
        }
        for (String path : step.removals()) {
            int unit = indentUnit(work);
            Map<String, KeyLine> parsed = parse(work, unit);
            KeyLine line = parsed.get(path);
            if (line == null) {
                continue;
            }
            List<String> lines = new ArrayList<>(List.of(work.split("\n", -1)));
            int end = line.index() + 1;
            Map<Integer, KeyLine> indexed = byIndex(parsed);
            while (end < lines.size()) {
                KeyLine next = indexed.get(end);
                if (next != null && next.indent() <= line.indent()) {
                    break;
                }
                end++;
            }
            lines.subList(line.index(), end).clear();
            work = String.join("\n", lines);
            report.add("v" + version + ": удалён устаревший ключ " + path);
        }
        return work;
    }

    // ------------------------------------------------------------------
    // Аддитивное дополнение без переформатирования
    // ------------------------------------------------------------------

    private static String addMissing(String user, String reference, Set<String> referenceKeys,
                                     Spec spec, List<String> report) {
        Set<String> userKeys = keys(user);
        if (userKeys == null) {
            return user;
        }
        int refUnit = indentUnit(reference);
        int userUnit = indentUnit(user);
        Map<String, KeyLine> refParsed = parse(reference, refUnit);
        Map<String, KeyLine> userParsed = parse(user, userUnit);

        // Только максимальные отсутствующие поддеревья: если родитель тоже
        // отсутствует, вставляем его блок целиком, а не каждую строку потомков.
        List<String> missing = new ArrayList<>();
        for (String path : referenceKeys) {
            if (userKeys.contains(path)) {
                continue;
            }
            String parent = parent(path);
            if (!parent.isEmpty() && !userKeys.contains(parent)) {
                continue;
            }
            if (refParsed.containsKey(path)) {
                missing.add(path);
            }
        }
        if (missing.isEmpty()) {
            return user;
        }
        missing.sort(Comparator.<String>comparingInt(path -> refParsed.get(path).index()));

        String[] refLines = normalize(reference).split("\n", -1);
        Map<Integer, KeyLine> refByIndex = byIndex(refParsed);
        Map<Integer, KeyLine> userByIndex = byIndex(userParsed);
        List<Insertion> insertions = new ArrayList<>();
        int order = 0;
        for (String path : missing) {
            KeyLine anchor = refParsed.get(path);
            int start = anchor.index();
            int end = start;
            for (int i = start + 1; i < refLines.length; i++) {
                KeyLine next = refByIndex.get(i);
                if (next != null && next.indent() <= anchor.indent()) {
                    break;
                }
                end = i;
            }
            // Хвостовые пустые строки и комментарии относятся к следующему разделу.
            while (end > start && (refLines[end].isBlank()
                    || refLines[end].stripLeading().startsWith("#"))) {
                end--;
            }
            int lead = start;
            while (lead > 0 && !refByIndex.containsKey(lead - 1)
                    && (refLines[lead - 1].isBlank()
                    || refLines[lead - 1].stripLeading().startsWith("#"))) {
                lead--;
            }

            List<String> block = new ArrayList<>();
            for (int i = lead; i <= end; i++) {
                block.add(rescaleIndent(refLines[i], refUnit, userUnit));
            }

            String parent = parent(path);
            int position;
            if (parent.isEmpty()) {
                position = normalize(user).split("\n", -1).length;
            } else {
                KeyLine parentLine = userParsed.get(parent);
                if (parentLine == null) {
                    continue;
                }
                String[] currentLines = normalize(user).split("\n", -1);
                position = currentLines.length;
                for (int i = parentLine.index() + 1; i < currentLines.length; i++) {
                    KeyLine next = userByIndex.get(i);
                    if (next != null && next.indent() <= parentLine.indent()) {
                        position = i;
                        break;
                    }
                }
                // Комментарии следующего раздела не должны «прилипнуть» к
                // добавленному блоку: отступаем назад по непрерывному хвосту
                // комментариев и пустых строк.
                while (position > parentLine.index() + 1
                        && !userByIndex.containsKey(position - 1)
                        && (currentLines[position - 1].isBlank()
                        || currentLines[position - 1].stripLeading().startsWith("#"))) {
                    position--;
                }
            }
            insertions.add(new Insertion(position, anchor.indent(), order++,
                    String.join("\n", block)));
            report.add("добавлен ключ/раздел " + path);
        }
        if (insertions.isEmpty()) {
            return user;
        }
        // Вставки с ОДНОЙ позицией объединяются в один блок. Внутри блока
        // сначала идут более глубокие отступы: они принадлежат внутренней
        // секции, которая заканчивается ровно в этой точке. Если поставить
        // первым ключ внешней секции (скаляр с меньшим отступом), следующий за
        // ним потомок окажется «после скаляра» и YAML станет нечитаемым —
        // движок тогда честно откажется от всей правки.
        Map<Integer, List<Insertion>> groups = new TreeMap<>();
        for (Insertion insertion : insertions) {
            groups.computeIfAbsent(insertion.position(), ignored -> new ArrayList<>())
                    .add(insertion);
        }
        List<Integer> positions = new ArrayList<>(groups.keySet());
        positions.sort(Comparator.reverseOrder()); // с конца: ранние индексы не сдвинутся
        List<String> result = new ArrayList<>(List.of(normalize(user).split("\n", -1)));
        for (int position : positions) {
            List<Insertion> group = groups.get(position);
            group.sort(Comparator.<Insertion>comparingInt(Insertion::indent).reversed()
                    .thenComparingInt(Insertion::order));
            List<String> block = new ArrayList<>();
            for (Insertion insertion : group) {
                block.addAll(List.of(insertion.text().split("\n", -1)));
            }
            int at = Math.min(position, result.size());
            result.addAll(at, block);
        }
        return String.join("\n", result);
    }

    private record Insertion(int position, int indent, int order, String text) {
    }

    private static String rescaleIndent(String line, int from, int to) {
        if (from == to || line.isBlank()) {
            return line;
        }
        Matcher matcher = LEADING_SPACES.matcher(line);
        if (!matcher.matches()) {
            return line;
        }
        int width = matcher.group(1).length();
        int levels = width / Math.max(1, from);
        return " ".repeat(levels * to) + matcher.group(2);
    }

    // ------------------------------------------------------------------
    // Чтение YAML и разбор строк
    // ------------------------------------------------------------------

    /** Все пути эталона/файла, включая секции; null — YAML не читается. */
    private static Set<String> keys(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(normalize(text));
        } catch (InvalidConfigurationException | RuntimeException error) {
            return null;
        }
        return new LinkedHashSet<>(yaml.getKeys(true));
    }

    private static Map<String, KeyLine> parse(String text, int unit) {
        Map<String, KeyLine> out = new LinkedHashMap<>();
        String[] lines = normalize(text).split("\n", -1);
        List<String> stack = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.stripLeading();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")
                    || trimmed.startsWith("---")) {
                continue;
            }
            Matcher matcher = KEY_LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String key = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            if (key == null) {
                continue;
            }
            int indent = matcher.group(1).length();
            int level = indent / Math.max(1, unit);
            while (stack.size() > level) {
                stack.remove(stack.size() - 1);
            }
            while (stack.size() < level) {
                stack.add("");
            }
            StringBuilder path = new StringBuilder();
            for (String part : stack) {
                if (!part.isEmpty()) {
                    if (path.length() > 0) {
                        path.append('.');
                    }
                    path.append(part);
                }
            }
            if (path.length() > 0) {
                path.append('.');
            }
            path.append(key);
            stack.add(key);
            out.put(path.toString(), new KeyLine(i, indent, key, path.toString(), line));
        }
        return out;
    }

    private static Map<Integer, KeyLine> byIndex(Map<String, KeyLine> parsed) {
        Map<Integer, KeyLine> out = new HashMap<>();
        for (KeyLine line : parsed.values()) {
            out.put(line.index(), line);
        }
        return out;
    }

    /** Минимальный положительный отступ среди ключей: 2 или 4 пробела. */
    private static int indentUnit(String text) {
        int unit = Integer.MAX_VALUE;
        for (String line : normalize(text).split("\n", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            if (!KEY_LINE.matcher(line).matches()) {
                continue;
            }
            int indent = line.length() - trimmed.length();
            if (indent > 0 && indent < unit) {
                unit = indent;
            }
        }
        return unit == Integer.MAX_VALUE ? 2 : unit;
    }

    private static String parent(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(0, dot);
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
