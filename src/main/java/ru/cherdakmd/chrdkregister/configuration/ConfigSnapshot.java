package ru.cherdakmd.chrdkregister.configuration;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.cherdakmd.chrdkregister.Messages;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.logging.*;

/** Подготовка отдельно от публикации: живые listeners не видят непроверенный YAML/default fallback. */
public record ConfigSnapshot<T>(FileConfiguration yaml, T value, Messages messages, List<Diagnostic> diagnostics) {
    public record Diagnostic(Level level, String text) { }
    @FunctionalInterface public interface TextLoader {
        Messages load(FileConfiguration yaml, Logger staged) throws IOException, InvalidConfigurationException;
    }
    public static <T> ConfigSnapshot<T> load(Path file, InputStream defaults,
                                             BiFunction<FileConfiguration, Logger, T> parser,
                                             TextLoader texts) throws IOException, InvalidConfigurationException {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Logger staged = new Logger("CHRDK/config-candidate", null) { };
        staged.setUseParentHandlers(false);
        staged.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                if (diagnostics.size() < 64) diagnostics.add(new Diagnostic(record.getLevel(), record.getMessage()));
            }
            @Override public void flush() { }
            @Override public void close() { }
        });
        YamlConfiguration fresh;
        try (InputStream resource = defaults) {
            if (resource == null) throw new IOException("Missing config resource");
            fresh = read(new InputStreamReader(resource, StandardCharsets.UTF_8));
        }
        YamlConfiguration candidate;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { candidate = read(reader); }
        validate(candidate, fresh);
        candidate.setDefaults(fresh);
        Messages messages = Objects.requireNonNull(texts.load(candidate, staged));
        T parsed = Objects.requireNonNull(parser.apply(candidate, staged));
        return new ConfigSnapshot<>(candidate, parsed, messages, List.copyOf(diagnostics));
    }
    private static YamlConfiguration read(Reader reader) throws IOException, InvalidConfigurationException {
        var yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        yaml.load(reader); // В отличие от loadConfiguration не скрывает IOException/ошибку YAML.
        return yaml;
    }
    /** Проверяем типы известных ключей, не отменяя прежние clamp/неизвестные пользовательские записи. */
    private static void validate(ConfigurationSection live, ConfigurationSection defaults) throws InvalidConfigurationException {
        for (String key : live.getKeys(false)) {
            Object actual = live.get(key), expected = defaults.get(key);
            if (expected != null) compatible(actual, expected);
        }
    }
    private static void compatible(Object actual, Object expected) throws InvalidConfigurationException {
        if (actual == null) return; // Отсутствие/null сохраняет обычный Bukkit fallback.
        if (expected instanceof ConfigurationSection section) {
            if (!(actual instanceof ConfigurationSection live)) throw type();
            validate(live, section);
        } else if (expected instanceof Map<?, ?> map) {
            if (!(actual instanceof Map<?, ?> live)) throw type();
            for (var entry : live.entrySet()) if (map.get(entry.getKey()) != null) compatible(entry.getValue(), map.get(entry.getKey()));
        } else if (expected instanceof List<?> list) {
            if (!(actual instanceof List<?> live)) throw type();
            // Пустой шаблон не определяет тип элементов — не выдумываем новую схему.
            if (!list.isEmpty()) for (Object item : live) compatible(item, list.getFirst());
        } else if (expected instanceof Boolean && !(actual instanceof Boolean)
                || expected instanceof Number && !(actual instanceof Number)
                || expected instanceof String && !(actual instanceof String)) throw type();
    }
    private static InvalidConfigurationException type() {
        // Ни пути/значения пользователя, ни YAML-snippet не попадают в диагностическое сообщение.
        return new InvalidConfigurationException("Wrong type for known configuration key");
    }
    /** Только ПОСЛЕ публикации снимка. Независимые cleanup-hooks не мешают друг другу. */
    public void committed(Logger logger, Runnable... cleanup) {
        for (Diagnostic diagnostic : diagnostics) logger.log(diagnostic.level, diagnostic.text);
        for (Runnable action : cleanup) {
            try { action.run(); }
            catch (RuntimeException | LinkageError failure) {
                logger.warning("Настройки приняты, но очистка прежних операций завершилась с ошибкой ("
                        + failure.getClass().getSimpleName() + "). Проверьте состояние модуля.");
            }
        }
    }
    public static void rejected(Logger logger, Throwable failure) {
        logger.warning("Перезагрузка отклонена (" + failure.getClass().getSimpleName()
                + "). Прежние настройки и операции сохранены. Проверьте config.yml и файл сообщений выбранной локали.");
    }
}
