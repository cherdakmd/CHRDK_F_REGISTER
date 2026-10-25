package ru.cherdakmd.chrdkevents;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контракт текстов мировых событий (1.4.160): все ключи, к которым
 * обращается код, существуют, а подстановки в шаблонах не потеряны.
 */
class EventMessagesTest {

    private static final List<String> REQUIRED = List.of(
            "no-permission", "disabled", "unknown-sub", "player-only",
            "help-header", "help-info", "help-next", "help-list", "help-admin",
            "no-active", "active", "active-description", "active-bonus",
            "next", "next-while-active", "next-unknown",
            "list-empty", "list-header", "list-line", "list-ready", "list-cooling",
            "kind.xp", "kind.mining", "kind.loot", "kind.effect",
            "bonus-value-xp", "bonus-value-mining", "bonus-value-loot", "bonus-value-effect",
            "start-broadcast", "end-broadcast", "bossbar", "join-active",
            "bonus-xp", "bonus-mining", "bonus-loot",
            "admin-usage", "admin-reloaded", "admin-force-active", "admin-force-unknown",
            "admin-force-none", "admin-force-ok", "admin-stop-none", "admin-stop-ok",
            "admin-next-active", "admin-next-ok",
            "admin-history-header", "admin-history-line", "admin-history-running",
            "admin-history-empty", "admin-history-bad-count",
            "profile-title", "profile-active", "profile-next", "profile-none", "profile-stats");

    private static ConfigurationSection load() throws Exception {
        try (InputStream stream = EventMessagesTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(stream, "messages.yml должен лежать в ресурсах модуля");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            ConfigurationSection section = yaml.getConfigurationSection("events");
            assertNotNull(section, "тексты должны лежать под секцией events:");
            return section;
        }
    }

    @Test
    void everyKeyUsedByCodeExists() throws Exception {
        ConfigurationSection section = load();
        for (String key : REQUIRED) {
            assertTrue(section.isString(key), "нет ключа: " + key);
            assertFalse(section.getString(key, "").isBlank(), "пустой текст: " + key);
        }
    }

    @Test
    void placeholdersMatchTheCode() throws Exception {
        ConfigurationSection section = load();
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("unknown-sub", List.of("%sub%"));
        expected.put("active", List.of("%name%", "%time%", "%kind%"));
        expected.put("active-description", List.of("%description%"));
        expected.put("active-bonus", List.of("%bonus%"));
        expected.put("next", List.of("%time%", "%human%"));
        expected.put("list-header", List.of("%count%"));
        expected.put("list-line", List.of("%name%", "%kind%", "%time%", "%state%"));
        expected.put("bonus-value-xp", List.of("%percent%"));
        expected.put("bonus-value-mining", List.of("%percent%"));
        expected.put("bonus-value-loot", List.of("%percent%"));
        expected.put("start-broadcast", List.of("%name%", "%description%", "%time%"));
        expected.put("end-broadcast", List.of("%name%"));
        expected.put("bossbar", List.of("%name%", "%time%"));
        expected.put("join-active", List.of("%name%", "%time%"));
        expected.put("bonus-xp", List.of("%percent%", "%name%"));
        expected.put("bonus-mining", List.of("%item%", "%name%"));
        expected.put("bonus-loot", List.of("%item%", "%name%"));
        expected.put("admin-reloaded", List.of("%count%", "%min%", "%max%"));
        expected.put("admin-force-active", List.of("%name%"));
        expected.put("admin-force-unknown", List.of("%id%"));
        expected.put("admin-force-ok", List.of("%name%", "%time%"));
        expected.put("admin-stop-ok", List.of("%name%"));
        expected.put("admin-next-ok", List.of("%time%"));
        expected.put("admin-history-header", List.of("%count%"));
        expected.put("admin-history-line", List.of("%time%", "%name%", "%duration%"));
        expected.put("profile-active", List.of("%name%", "%time%"));
        expected.put("profile-next", List.of("%time%"));
        expected.put("profile-stats", List.of("%total%"));

        for (Map.Entry<String, List<String>> row : expected.entrySet()) {
            String text = section.getString(row.getKey(), "");
            for (String placeholder : row.getValue()) {
                assertTrue(text.contains(placeholder),
                        "в «" + row.getKey() + "» нет " + placeholder + ": " + text);
            }
        }
    }

    @Test
    void configHasEveryKindAndEveryEvent() throws Exception {
        try (InputStream stream = EventMessagesTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(stream, "config.yml должен лежать в ресурсах модуля");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            ConfigurationSection events = yaml.getConfigurationSection("events");
            assertNotNull(events, "в config.yml должна быть секция events");
            assertTrue(events.getKeys(false).size() >= 6, "событий должно быть не меньше шести");
            for (String key : events.getKeys(false)) {
                assertNotNull(EventKind.parse(events.getString(key + ".type")),
                        "неизвестный вид у события " + key);
                assertTrue(events.getLong(key + ".weight") > 0, "нулевой вес у " + key);
            }
        }
    }
}
