package ru.cherdakmd.chrdkorders;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контракт текстов доски (1.4.154): все ключи, к которым обращается код,
 * существуют, а подстановки в шаблонах не потеряны.
 */
class OrdersMessagesTest {

    private static final List<String> REQUIRED = List.of(
            "no-permission", "player-only", "disabled", "unknown-subcommand",
            "help-header", "help-list", "help-deliver", "help-stats", "help-top",
            "help-footer",
            "list-header", "list-row", "list-row-pass", "list-row-locked", "list-empty",
            "list-footer",
            "delivered", "delivered-line", "delivered-pass", "delivered-dropped",
            "delivered-broadcast", "not-enough", "locked", "order-expired",
            "unknown-order", "cooldown", "daily-limit", "inventory-error",
            "menu-title", "menu-empty-name", "menu-empty-lore", "menu-order-bot",
            "menu-order-quote", "menu-order-need", "menu-order-have", "menu-order-reward",
            "menu-order-pass", "menu-order-time", "menu-order-reputation",
            "menu-order-locked-reputation", "menu-order-click", "menu-order-click-locked",
            "menu-order-expired",
            "menu-info-name", "menu-info-lore", "menu-info-done", "menu-info-today",
            "menu-info-next", "menu-info-max", "menu-info-cooldown", "menu-close",
            "stats-header", "stats-completed", "stats-reputation", "stats-rank",
            "stats-rank-next", "stats-rank-max", "stats-today", "stats-footer",
            "top-header", "top-row", "top-empty",
            "admin-reloaded", "admin-spawned", "admin-spawn-failed", "admin-cleared",
            "admin-rep-given", "admin-player-not-found", "admin-unknown-category",
            "admin-bad-amount", "admin-usage", "admin-info");

    private static ConfigurationSection load() throws Exception {
        try (InputStream stream = OrdersMessagesTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(stream, "messages.yml должен лежать в ресурсах модуля");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            ConfigurationSection section = yaml.getConfigurationSection("orders");
            assertNotNull(section, "тексты должны лежать под секцией orders:");
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
    void rankNamesCoverDefaultRanks() throws Exception {
        ConfigurationSection section = load();
        // Ключи записаны как «rank.novice»: Bukkit при загрузке разворачивает
        // точку в секцию, поэтому читаем обычным путём.
        for (String rank : OrdersConfig.defaultRanks().values()) {
            String text = section.getString("rank." + rank, "");
            assertFalse(text.isBlank(), "нет звания: " + rank);
        }
        assertNull(section.getString("menu-order-greeting"),
                "старый ключ переименован в menu-order-quote");
    }

    @Test
    void placeholdersMatchTheCode() throws Exception {
        ConfigurationSection section = load();
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("list-header", List.of("%count%", "%reputation%", "%rank%"));
        expected.put("list-row", List.of("%num%", "%material%", "%amount%", "%bot%",
                "%reward%", "%time%"));
        expected.put("list-row-pass", List.of("%num%", "%material%", "%amount%", "%bot%",
                "%reward%", "%hours%", "%time%"));
        expected.put("list-row-locked", List.of("%num%", "%material%", "%amount%", "%bot%",
                "%need%"));
        expected.put("delivered", List.of("%material%", "%amount%", "%bot%", "%reward%"));
        expected.put("delivered-line", List.of("%bot%", "%line%"));
        expected.put("delivered-broadcast", List.of("%player%", "%bot%", "%reward%"));
        expected.put("not-enough", List.of("%need%", "%material%", "%have%"));
        expected.put("cooldown", List.of("%seconds%"));
        expected.put("menu-title", List.of("%count%"));
        expected.put("menu-order-need", List.of("%material%", "%amount%"));
        expected.put("menu-order-have", List.of("%have%", "%need%"));
        expected.put("menu-order-pass", List.of("%hours%"));
        expected.put("menu-info-lore", List.of("%reputation%", "%rank%"));
        expected.put("top-row", List.of("%num%", "%player%", "%completed%", "%reputation%",
                "%rank%"));
        expected.put("admin-info", List.of("%orders%", "%size%", "%categories%", "%bots%",
                "%players%", "%completed%"));
        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            String text = section.getString(entry.getKey(), "");
            for (String placeholder : entry.getValue()) {
                assertTrue(text.contains(placeholder),
                        entry.getKey() + " потерял " + placeholder + ": " + text);
            }
        }
    }
}
