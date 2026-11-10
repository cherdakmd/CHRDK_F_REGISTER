package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Автоматическое обновление {@code config.yml} и {@code messages.yml} при смене
 * версии ядра или аддона. Вызывается первым делом в {@code onEnable()} сразу
 * после {@code saveDefaultConfig()}, пока модуль ещё не читал конфиг.
 *
 * <p>Политика (вариант C — схема версий):</p>
 * <ul>
 *   <li>версия схемы хранится в файле: ключом {@code config-version} для
 *       {@code config.yml} и комментарием {@code # messages-version} для
 *       {@code messages.yml} (корневой ключ сломал бы разворот секции в
 *       {@link Messages});</li>
 *   <li>дополнение выполняется только на переходе {@code vN -> vN+1}: файл с
 *       актуальной версией не меняется вообще, поэтому владельческие правки и
 *       даже намеренно удалённые ключи не возвращаются;</li>
 *   <li>добавляются только отсутствующие ключи, скопированные из эталонного
 *       ресурса JAR вместе с комментариями; существующие значения не трогаются;</li>
 *   <li>перед записью создаётся backup, запись атомарная, повторный старт
 *       ничего не меняет, при любой ошибке файл остаётся прежним.</li>
 * </ul>
 *
 * <p>Целевые версии и карты миграций берутся из необязательного ресурса
 * {@code config-schema.yml} модуля:</p>
 * <pre>
 * config-version: 2
 * messages-version: 1
 * migrations:
 *   config:
 *     2:
 *       rename: { old.key: new.key }
 *       remove: [ dead.key ]
 * </pre>
 * Если ресурса нет, действует базовая схема v1 без переименований. Разработчик
 * поднимает номер в {@code config-schema.yml} в том же релизе, в котором меняет
 * набор ключей, — иначе новые ключи не будут дописаны в файл владельца.
 *
 * <p>Класс лежит в ядре, но вызывается каждым аддоном: у всех модулей есть
 * {@code depend: [CHRDK_Core]}, поэтому их classloader видит этот класс.
 * Публичного API он не меняет, {@code API_VERSION} остаётся прежним.</p>
 */
public final class ConfigUpgrader {

    /** Текущая базовая версия схемы, если модуль не прислал config-schema.yml. */
    public static final int BASE_VERSION = 1;
    public static final String SCHEMA_RESOURCE = "config-schema.yml";
    public static final String CONFIG_MARKER = "config-version";
    public static final String MESSAGES_MARKER = "messages-version";

    private ConfigUpgrader() {
    }

    /**
     * Обновляет оба YAML-файла плагина. Никогда не бросает исключение наружу:
     * неудачная миграция оставляет конфиг владельца в покое и пишет WARNING.
     */
    public static void apply(JavaPlugin plugin) {
        if (plugin == null) {
            return;
        }
        try {
            Schema schema = Schema.read(plugin);
            boolean configChanged = upgrade(plugin, "config.yml", CONFIG_MARKER,
                    ConfigSchemaEngine.MarkerMode.KEY, schema.configVersion(),
                    schema.configSteps());
            upgrade(plugin, "messages.yml", MESSAGES_MARKER,
                    ConfigSchemaEngine.MarkerMode.COMMENT, schema.messagesVersion(),
                    schema.messagesSteps());
            if (configChanged) {
                plugin.reloadConfig();
            }
        } catch (Exception | LinkageError error) {
            plugin.getLogger().log(Level.WARNING,
                    "[config-schema] обновление пропущено, конфиги оставлены без изменений", error);
        }
    }

    private static boolean upgrade(JavaPlugin plugin, String fileName, String marker,
                                   ConfigSchemaEngine.MarkerMode mode, int target,
                                   Map<Integer, ConfigSchemaEngine.Step> steps) throws IOException {
        String reference;
        try (InputStream resource = plugin.getResource(fileName)) {
            if (resource == null) {
                return false;
            }
            reference = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            plugin.getLogger().log(Level.WARNING,
                    "[config-schema] не прочитан эталон " + fileName, error);
            return false;
        }
        Path file = plugin.getDataFolder().toPath().resolve(fileName);
        if (!Files.exists(file)) {
            // saveDefaultConfig()/saveResource() обычно уже создали файл; если
            // нет — эталон и есть новый конфиг, достаточно проставить маркер.
            Files.createDirectories(file.getParent());
            String seeded = ConfigSchemaEngine.apply(reference, reference,
                    new ConfigSchemaEngine.Spec(fileName, marker, mode, target, steps)).text();
            Files.writeString(file, seeded, StandardCharsets.UTF_8);
            plugin.getLogger().info("[config-schema] создан " + fileName + " (схема v" + target + ")");
            return "config.yml".equals(fileName);
        }
        String user = Files.readString(file, StandardCharsets.UTF_8);
        ConfigSchemaEngine.Result result = ConfigSchemaEngine.apply(reference, user,
                new ConfigSchemaEngine.Spec(fileName, marker, mode, target, steps));
        if (!result.changed()) {
            return false;
        }

        // Backup ДО любой записи: если копия не создалась, файл не меняем.
        Path backup = file.resolveSibling("config-before-schema-" + fileName.replace(".yml", "")
                + "-v" + target + "-" + System.currentTimeMillis() + ".yml");
        Files.copy(file, backup);
        Path temp = file.resolveSibling(fileName + ".schema.tmp");
        Files.writeString(temp, result.text(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        for (String line : result.report()) {
            plugin.getLogger().info("[config-schema] " + line);
        }
        plugin.getLogger().info("[config-schema] backup: " + backup.getFileName());
        return "config.yml".equals(fileName);
    }

    /** Целевые версии и карты миграций из ресурса config-schema.yml. */
    private record Schema(int configVersion, int messagesVersion,
                          Map<Integer, ConfigSchemaEngine.Step> configSteps,
                          Map<Integer, ConfigSchemaEngine.Step> messagesSteps) {

        static Schema read(JavaPlugin plugin) {
            int configVersion = BASE_VERSION;
            int messagesVersion = BASE_VERSION;
            Map<Integer, ConfigSchemaEngine.Step> configSteps = Map.of();
            Map<Integer, ConfigSchemaEngine.Step> messagesSteps = Map.of();
            try (InputStream resource = plugin.getResource(SCHEMA_RESOURCE)) {
                if (resource == null) {
                    return new Schema(configVersion, messagesVersion, configSteps, messagesSteps);
                }
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(resource, StandardCharsets.UTF_8));
                configVersion = positive(yaml.getInt(CONFIG_MARKER, BASE_VERSION));
                messagesVersion = positive(yaml.getInt(MESSAGES_MARKER, BASE_VERSION));
                ConfigurationSection migrations = yaml.getConfigurationSection("migrations");
                if (migrations != null) {
                    configSteps = steps(migrations.getConfigurationSection("config"));
                    messagesSteps = steps(migrations.getConfigurationSection("messages"));
                }
            } catch (Exception | LinkageError error) {
                plugin.getLogger().log(Level.WARNING,
                        "[config-schema] config-schema.yml не прочитан, взята базовая v1", error);
            }
            return new Schema(configVersion, messagesVersion, configSteps, messagesSteps);
        }

        private static int positive(int value) {
            return value < 1 ? BASE_VERSION : value;
        }

        private static Map<Integer, ConfigSchemaEngine.Step> steps(ConfigurationSection section) {
            if (section == null) {
                return Map.of();
            }
            Map<Integer, ConfigSchemaEngine.Step> out = new LinkedHashMap<>();
            for (String version : section.getKeys(false)) {
                int number;
                try {
                    number = positive(Integer.parseInt(version.trim()));
                } catch (NumberFormatException ignored) {
                    continue;
                }
                ConfigurationSection step = section.getConfigurationSection(version);
                Map<String, String> renames = new LinkedHashMap<>();
                Set<String> removals = new LinkedHashSet<>();
                if (step != null) {
                    ConfigurationSection rename = step.getConfigurationSection("rename");
                    if (rename != null) {
                        for (String key : rename.getKeys(true)) {
                            String value = rename.getString(key);
                            if (value != null && !value.isBlank()) {
                                renames.put(key, value);
                            }
                        }
                    }
                    List<String> remove = step.getStringList("remove");
                    removals.addAll(remove);
                }
                out.put(number, new ConfigSchemaEngine.Step(Map.copyOf(renames), Set.copyOf(removals)));
            }
            return Map.copyOf(out);
        }
    }
}
