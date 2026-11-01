package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Диагностика дублирующихся JAR-файлов одного и того же плагина в каталоге {@code plugins/}.
 *
 * <p>В Paper 1.21+ ({@code ModernPluginLoadingStrategy}) наличие двух файлов с одинаковым
 * {@code name} в {@code plugin.yml} (например, оставшегося старого {@code chrdk-battlepass-1.4.267.jar}
 * рядом с новым {@code chrdk-battlepass-1.4.268.jar}) приводит к ошибке
 * {@code Ambiguous plugin name} — оба файла остаются выключенными. Детектор выводит
 * заметное русскоязычное предупреждение при запуске ядра и в {@code /pass doctor}.</p>
 */
public final class DuplicatePluginJarDetector {

    private DuplicatePluginJarDetector() {
    }

    /**
     * Сканирует каталог {@code plugins/} и возвращает карту {@code имя_плагина -> список_файлов}
     * только для тех плагинов, которые представлены двумя и более JAR-файлами одновременно.
     */
    public static Map<String, List<String>> scan(Path pluginsDir) {
        if (pluginsDir == null || !Files.isDirectory(pluginsDir, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        Map<String, List<String>> byPluginName = new TreeMap<>();
        try (var stream = Files.list(pluginsDir)) {
            List<Path> jars = stream
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .toList();
            for (Path jar : jars) {
                String pluginName = readPluginName(jar);
                if (pluginName != null && !pluginName.isBlank()) {
                    byPluginName.computeIfAbsent(pluginName, k -> new ArrayList<>())
                            .add(jar.getFileName().toString());
                }
            }
        } catch (Exception ignored) {
            return Map.of();
        }

        Map<String, List<String>> duplicates = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : byPluginName.entrySet()) {
            if (entry.getValue().size() >= 2) {
                List<String> sorted = new ArrayList<>(entry.getValue());
                Collections.sort(sorted);
                duplicates.put(entry.getKey(), List.copyOf(sorted));
            }
        }
        return Collections.unmodifiableMap(duplicates);
    }

    /** Форматирует краткую сводку конфликтов для лога и {@code /pass doctor}. */
    public static String formatSummary(Map<String, List<String>> duplicates) {
        if (duplicates == null || duplicates.isEmpty()) {
            return "";
        }
        return duplicates.entrySet().stream()
                .map(e -> e.getKey() + " (" + String.join(", ", e.getValue()) + ")")
                .collect(Collectors.joining("; "));
    }

    /** Выводит заметное сообщение {@code SEVERE} в лог при запуске, если в {@code plugins/} есть дубликаты. */
    public static void warnIfDuplicates(Path pluginsDir, Logger logger) {
        if (logger == null) {
            return;
        }
        Map<String, List<String>> duplicates = scan(pluginsDir);
        if (duplicates.isEmpty()) {
            return;
        }
        logger.severe("================================================================");
        logger.severe("ВНИМАНИЕ! В каталоге plugins/ найдено несколько JAR одного плагина:");
        for (Map.Entry<String, List<String>> entry : duplicates.entrySet()) {
            logger.severe("  • " + entry.getKey() + ": " + String.join(" и ", entry.getValue()));
        }
        logger.severe("Из-за конфликта имён (Ambiguous plugin name) Paper не загружает такие плагины!");
        logger.severe("Остановите сервер, удалите старые версии JAR из plugins/ и запустите сервер снова.");
        logger.severe("================================================================");
    }

    private static String readPluginName(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getJarEntry("paper-plugin.yml");
            if (entry == null) {
                entry = jar.getJarEntry("plugin.yml");
            }
            if (entry == null) {
                return null;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            try (InputStream in = jar.getInputStream(entry)) {
                byte[] bytes = PluginUpdateService.readLimited(in, 256 * 1024);
                yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
            }
            return yaml.getString("name", "").trim();
        } catch (Exception ignored) {
            return null;
        }
    }
}
