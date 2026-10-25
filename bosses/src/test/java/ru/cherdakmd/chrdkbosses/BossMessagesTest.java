package ru.cherdakmd.chrdkbosses;

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
 * Контракт текстов боссов (1.4.162): все ключи, к которым обращается код,
 * существуют, а подстановки в шаблонах не потеряны.
 *
 * <p>Пропавший ключ в MiniMessage не ломает сервер, но оставляет игрока без
 * сообщения — поэтому набор проверяется тестом, а не глазами в бою.</p>
 */
class BossMessagesTest {

    private static final List<String> REQUIRED = List.of(
            "no-permission", "player-only", "disabled", "unknown-subcommand", "boss-not-found",
            "help-header", "help-menu", "help-list", "help-info", "help-zones", "help-where",
            "list-empty", "list-header", "list-line", "list-alive", "list-cooldown",
            "list-ready", "boss-spawned", "boss-name", "boss-phase", "boss-bar", "boss-timeout",
            "boss-despawned", "boss-killed", "boss-reward-xp", "boss-reward-trophy",
            "boss-reward-dropped", "boss-trophy-name", "boss-trophy-lore", "zone-enter",
            "zone-left", "zone-actionbar", "zone-bonus", "zones-empty", "zones-header",
            "zones-line", "zones-on", "zones-off", "zone-info-header", "zone-info-description",
            "zone-info-level", "zone-info-bonus", "zone-info-damage", "zone-info-effect",
            "menu-title", "menu-info-name", "menu-info-alive", "menu-info-kills",
            "menu-info-zones", "menu-info-hint", "menu-item-name", "menu-close",
            "menu-boss-description", "menu-boss-arena", "menu-boss-health", "menu-boss-alive",
            "menu-boss-participants", "menu-boss-cooldown", "menu-boss-ready",
            "menu-boss-reward", "menu-boss-click", "menu-zone-description", "menu-zone-level",
            "menu-zone-bonus", "menu-zone-damage", "menu-zone-enabled", "menu-zone-disabled",
            "boss-info-header", "boss-info-description", "boss-info-arena", "boss-info-health",
            "boss-info-phase", "boss-info-alive", "boss-info-cooldown", "boss-info-ready",
            "boss-info-reward", "info-usage", "where-empty", "where-line", "profile-title",
            "profile-kills", "profile-alive", "profile-fighting", "profile-zone",
            "profile-last", "profile-top", "admin-usage", "admin-spawned",
            "admin-spawn-failed", "admin-stopped", "admin-stopped-all", "admin-not-alive",
            "admin-reloaded", "admin-state-idle", "admin-state-alive", "admin-info",
            "admin-cooldown-usage", "admin-cooldown", "admin-cooldown-all",
            "admin-history-usage", "admin-history-empty", "admin-history-line",
            "admin-history-unknown", "admin-reset");

    private static ConfigurationSection load() throws Exception {
        try (InputStream stream = BossMessagesTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(stream, "messages.yml должен лежать в ресурсах модуля");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            ConfigurationSection section = yaml.getConfigurationSection("bosses");
            assertNotNull(section, "тексты должны лежать под секцией bosses:");
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
    void noExtraKeysBeyondTheContract() throws Exception {
        ConfigurationSection section = load();
        for (String key : section.getKeys(true)) {
            if (section.isConfigurationSection(key)) {
                continue;
            }
            assertTrue(REQUIRED.contains(key), "лишний ключ без использования в коде: " + key);
        }
    }

    @Test
    void placeholdersMatchTheCode() throws Exception {
        ConfigurationSection section = load();
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("unknown-subcommand", List.of("%sub%"));
        expected.put("boss-not-found", List.of("%id%"));
        expected.put("list-header", List.of("%count%"));
        expected.put("list-line", List.of("%name%", "%entity%", "%state%"));
        expected.put("list-alive", List.of("%phase%", "%percent%"));
        expected.put("list-cooldown", List.of("%time%"));
        expected.put("boss-spawned", List.of("%name%", "%arena%"));
        expected.put("boss-name", List.of("%name%", "%phase%"));
        expected.put("boss-phase", List.of("%name%", "%phase%"));
        expected.put("boss-bar", List.of("%name%", "%phase%", "%health%"));
        expected.put("boss-timeout", List.of("%name%"));
        expected.put("boss-despawned", List.of("%name%"));
        expected.put("boss-killed", List.of("%name%", "%killer%", "%participants%"));
        expected.put("boss-reward-xp", List.of("%xp%"));
        expected.put("boss-reward-trophy", List.of("%name%"));
        expected.put("boss-reward-dropped", List.of("%item%"));
        expected.put("boss-trophy-name", List.of("%name%"));
        expected.put("boss-trophy-lore", List.of("%name%"));
        expected.put("zone-enter", List.of("%zone%", "%level%"));
        expected.put("zone-left", List.of("%zone%"));
        expected.put("zone-actionbar", List.of("%zone%", "%level%"));
        expected.put("zone-bonus", List.of("%xp%"));
        expected.put("zones-header", List.of("%count%"));
        expected.put("zones-line", List.of("%name%", "%level%", "%radius%", "%bonus%", "%state%"));
        expected.put("zone-info-header", List.of("%name%"));
        expected.put("zone-info-description", List.of("%description%"));
        expected.put("zone-info-level", List.of("%level%", "%radius%"));
        expected.put("zone-info-bonus", List.of("%percent%"));
        expected.put("zone-info-damage", List.of("%damage%"));
        expected.put("zone-info-effect", List.of("%effect%", "%level%", "%seconds%"));
        expected.put("menu-info-alive", List.of("%alive%", "%total%"));
        expected.put("menu-info-kills", List.of("%kills%", "%bosses%"));
        expected.put("menu-info-zones", List.of("%zones%"));
        expected.put("menu-item-name", List.of("%name%"));
        expected.put("menu-boss-description", List.of("%description%"));
        expected.put("menu-boss-arena", List.of("%arena%"));
        expected.put("menu-boss-health", List.of("%health%", "%phases%"));
        expected.put("menu-boss-alive", List.of("%phase%", "%percent%"));
        expected.put("menu-boss-participants", List.of("%count%"));
        expected.put("menu-boss-cooldown", List.of("%time%"));
        expected.put("menu-boss-reward", List.of("%xp%", "%items%"));
        expected.put("menu-zone-description", List.of("%description%"));
        expected.put("menu-zone-level", List.of("%level%", "%radius%"));
        expected.put("menu-zone-bonus", List.of("%percent%"));
        expected.put("menu-zone-damage", List.of("%damage%"));
        expected.put("boss-info-header", List.of("%name%"));
        expected.put("boss-info-description", List.of("%description%"));
        expected.put("boss-info-arena", List.of("%arena%"));
        expected.put("boss-info-health", List.of("%health%", "%phases%"));
        expected.put("boss-info-phase", List.of("%percent%", "%name%", "%abilities%"));
        expected.put("boss-info-alive", List.of("%phase%", "%percent%"));
        expected.put("boss-info-cooldown", List.of("%time%"));
        expected.put("boss-info-reward", List.of("%xp%", "%items%"));
        expected.put("where-line", List.of("%name%", "%phase%", "%world%", "%x%", "%y%", "%z%"));
        expected.put("profile-kills", List.of("%kills%", "%total%"));
        expected.put("profile-alive", List.of("%count%"));
        expected.put("profile-fighting", List.of("%name%", "%phase%", "%percent%"));
        expected.put("profile-zone", List.of("%zone%", "%bonus%"));
        expected.put("profile-last", List.of("%name%", "%time%"));
        expected.put("profile-top", List.of("%kills%"));
        expected.put("admin-spawned", List.of("%name%"));
        expected.put("admin-spawn-failed", List.of("%name%"));
        expected.put("admin-stopped", List.of("%name%"));
        expected.put("admin-stopped-all", List.of("%count%"));
        expected.put("admin-not-alive", List.of("%name%"));
        expected.put("admin-reloaded", List.of("%bosses%", "%zones%"));
        expected.put("admin-info", List.of("%name%", "%state%", "%kills%", "%next%", "%arena%"));
        expected.put("admin-cooldown", List.of("%name%"));
        expected.put("admin-cooldown-all", List.of("%count%"));
        expected.put("admin-history-line", List.of("%time%", "%name%", "%killer%",
                "%participants%"));

        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            String text = section.getString(entry.getKey(), "");
            for (String placeholder : entry.getValue()) {
                assertTrue(text.contains(placeholder),
                        "в «" + entry.getKey() + "» нет подстановки " + placeholder + ": " + text);
            }
        }
    }

    @Test
    void stateKeysNeedNoPlaceholders() throws Exception {
        ConfigurationSection section = load();
        for (String key : List.of("list-ready", "zones-on", "zones-off", "menu-boss-ready",
                "menu-zone-enabled", "menu-zone-disabled", "boss-info-ready",
                "admin-state-idle", "admin-state-alive", "admin-history-unknown",
                "profile-title", "menu-info-name", "menu-close")) {
            String text = section.getString(key, "");
            assertFalse(text.contains("%"), "статус не должен ждать подстановок: " + key);
        }
    }
}
