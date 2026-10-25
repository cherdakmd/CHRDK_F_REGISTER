package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Настройки книги-путеводителя (секция {@code guide} в config.yml).
 *
 * <p>Как и секция {@code spawn}, отсутствие секции не выключает функцию — применяются
 * значения по умолчанию, поэтому обновление не требует правки существующего config.yml.
 * Тексты книги лежат в {@code messages.yml} ({@code guide.title}, {@code guide.author},
 * {@code guide.pages}), а не в конфиге: их правят и переводят вместе с остальными текстами.</p>
 */
public record GuideBookSettings(boolean enabled, boolean giveOnFirstJoin) {

    /** Значения по умолчанию: книга включена и выдаётся новичкам при первом входе. */
    public static GuideBookSettings defaults() {
        return new GuideBookSettings(true, true);
    }

    public static GuideBookSettings parse(FileConfiguration config) {
        ConfigurationSection section = config == null ? null : config.getConfigurationSection("guide");
        if (section == null) {
            return defaults();
        }
        return new GuideBookSettings(
                section.getBoolean("enabled", true),
                section.getBoolean("give-on-first-join", true));
    }
}
