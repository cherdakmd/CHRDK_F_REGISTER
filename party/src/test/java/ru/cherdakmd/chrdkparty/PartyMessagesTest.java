package ru.cherdakmd.chrdkparty;

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
 * Контракт текстов групп (1.4.158): все ключи, к которым обращается код,
 * существуют, а подстановки в шаблонах не потеряны.
 */
class PartyMessagesTest {

    private static final List<String> REQUIRED = List.of(
            "no-permission", "player-only", "disabled", "unknown-subcommand", "invalid-name",
            "help-header", "help-create", "help-invite", "help-accept", "help-leave",
            "help-kick", "help-disband", "help-list", "help-chat", "help-info", "help-footer",
            "created", "already-in-party", "not-in-party", "target-in-party",
            "invite-sent", "invite-received", "invite-broadcast", "invite-already-sent",
            "invite-limit", "invite-self", "invite-target-in-party", "party-full",
            "invite-none", "invite-expired", "invite-denied-sender", "invite-denied-target",
            "invite-accepted-sender", "invite-accepted-target",
            "player-joined", "player-left", "you-left", "party-disbanded", "you-disbanded",
            "leader-changed", "kicked", "you-kicked", "not-leader", "target-not-in-party",
            "target-not-found",
            "list-header", "list-row", "list-row-offline", "list-role-leader",
            "list-role-member", "list-footer", "list-empty",
            "chat-format", "chat-usage",
            "bonus-xp", "friendly-fire-blocked", "join-invites",
            "profile-title", "profile-size", "profile-leader", "profile-none",
            "admin-reloaded", "admin-info", "admin-disbanded", "admin-usage");

    private static ConfigurationSection load() throws Exception {
        try (InputStream stream = PartyMessagesTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(stream, "messages.yml должен лежать в ресурсах модуля");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            ConfigurationSection section = yaml.getConfigurationSection("party");
            assertNotNull(section, "тексты должны лежать под секцией party:");
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
        expected.put("unknown-subcommand", List.of("%sub%"));
        expected.put("help-footer", List.of("%max%"));
        expected.put("invite-sent", List.of("%player%", "%seconds%"));
        expected.put("invite-received", List.of("%player%"));
        expected.put("invite-broadcast", List.of("%player%"));
        expected.put("invite-already-sent", List.of("%player%"));
        expected.put("invite-limit", List.of("%limit%"));
        expected.put("invite-target-in-party", List.of("%player%"));
        expected.put("party-full", List.of("%max%"));
        expected.put("invite-accepted-sender", List.of("%player%", "%size%", "%max%"));
        expected.put("invite-accepted-target", List.of("%size%", "%max%"));
        expected.put("player-joined", List.of("%player%", "%size%", "%max%"));
        expected.put("player-left", List.of("%player%", "%size%", "%max%"));
        expected.put("leader-changed", List.of("%player%"));
        expected.put("kicked", List.of("%player%"));
        expected.put("you-kicked", List.of("%player%"));
        expected.put("target-not-in-party", List.of("%player%"));
        expected.put("target-not-found", List.of("%player%"));
        expected.put("list-header", List.of("%size%", "%max%", "%bonus%"));
        expected.put("list-row", List.of("%role%", "%player%", "%hp%"));
        expected.put("list-row-offline", List.of("%role%", "%player%"));
        expected.put("list-footer", List.of("%percent%"));
        expected.put("chat-format", List.of("%player%", "%message%"));
        expected.put("bonus-xp", List.of("%percent%"));
        expected.put("join-invites", List.of("%count%"));
        expected.put("profile-size", List.of("%size%", "%max%", "%bonus%"));
        expected.put("profile-leader", List.of("%leader%"));
        expected.put("admin-info", List.of("%parties%", "%members%", "%max%", "%enabled%"));
        expected.put("admin-disbanded", List.of("%player%"));
        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            String text = section.getString(entry.getKey(), "");
            for (String placeholder : entry.getValue()) {
                assertTrue(text.contains(placeholder),
                        entry.getKey() + " потерял " + placeholder + ": " + text);
            }
        }
    }
}
