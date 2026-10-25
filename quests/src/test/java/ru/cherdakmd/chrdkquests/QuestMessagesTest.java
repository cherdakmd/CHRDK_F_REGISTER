package ru.cherdakmd.chrdkquests;

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
 * Контракт текстов квестов (1.4.161): все ключи, к которым обращается код,
 * существуют, а подстановки в шаблонах не потеряны.
 */
class QuestMessagesTest {

    private static final List<String> REQUIRED = List.of(
            "no-permission", "player-only", "disabled", "unknown-subcommand", "help-header",
            "help-menu", "help-list", "help-info", "help-claim", "help-join", "help-leave",
            "help-faction", "help-footer", "list-empty", "list-header", "list-line",
            "list-footer", "status-available", "status-progress", "status-ready",
            "status-done", "status-locked", "status-faction", "status-rank", "rank.newcomer",
            "rank.fighter", "rank.veteran", "rank.captain", "rank.marshal", "info-usage",
            "info-header", "info-description", "info-target", "info-progress", "info-reward",
            "info-rank", "info-status", "claim-usage", "claim-ok", "claim-actionbar",
            "claim-xp", "claim-rep", "claim-dropped", "claim-daily", "claim-not-ready",
            "claim-no-items", "claim-done", "claim-locked", "claim-wrong-faction",
            "claim-rank", "claim-unknown", "join-usage", "join-ok", "join-hint", "join-same",
            "join-switch", "join-unknown", "leave-ok", "leave-none", "faction-none",
            "faction-nobody", "faction-header", "faction-description", "faction-rank",
            "faction-reputation", "faction-done", "faction-max", "menu-title",
            "menu-npc-title", "menu-item-name", "menu-close", "menu-back", "menu-info-name",
            "menu-info-faction", "menu-info-rank", "menu-info-reputation", "menu-info-done",
            "menu-info-claims", "menu-info-ready", "menu-faction-description",
            "menu-faction-reputation", "menu-faction-rank", "menu-faction-current",
            "menu-faction-join-hint", "menu-npc-description", "menu-npc-faction",
            "menu-npc-progress", "menu-npc-current", "menu-npc-click",
            "menu-quest-description", "menu-quest-target", "menu-quest-progress",
            "menu-quest-reward", "menu-quest-rank", "menu-quest-status", "menu-quest-claim",
            "faction-neutral", "profile-title", "profile-none", "profile-faction",
            "profile-reputation", "profile-progress", "profile-claims", "profile-quest",
            "progress-ready", "admin-usage", "admin-reloaded", "admin-info",
            "admin-player-not-found", "admin-unknown-faction", "admin-bad-number",
            "admin-granted", "admin-reset", "admin-completed");

    private static ConfigurationSection load() throws Exception {
        try (InputStream stream = QuestMessagesTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(stream, "messages.yml должен лежать в ресурсах модуля");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            ConfigurationSection section = yaml.getConfigurationSection("quests");
            assertNotNull(section, "тексты должны лежать под секцией quests:");
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
        expected.put("help-footer", List.of("%limit%"));
        expected.put("list-header", List.of("%count%"));
        expected.put("list-line", List.of("%name%", "%npc%", "%have%", "%need%", "%status%"));
        expected.put("list-footer", List.of("%left%", "%limit%"));
        expected.put("info-header", List.of("%name%", "%npc%"));
        expected.put("info-description", List.of("%description%"));
        expected.put("info-target", List.of("%target%", "%amount%"));
        expected.put("info-progress", List.of("%have%", "%need%"));
        expected.put("info-reward", List.of("%xp%", "%reputation%"));
        expected.put("info-rank", List.of("%rank%"));
        expected.put("info-status", List.of("%status%"));
        expected.put("claim-ok", List.of("%name%"));
        expected.put("claim-actionbar", List.of("%name%"));
        expected.put("claim-xp", List.of("%xp%"));
        expected.put("claim-rep", List.of("%amount%", "%faction%"));
        expected.put("claim-dropped", List.of("%item%"));
        expected.put("claim-daily", List.of("%limit%"));
        expected.put("claim-not-ready", List.of("%amount%", "%target%"));
        expected.put("claim-no-items", List.of("%amount%", "%have%", "%target%"));
        expected.put("claim-wrong-faction", List.of("%faction%", "%id%"));
        expected.put("claim-rank", List.of("%rank%"));
        expected.put("join-ok", List.of("%faction%"));
        expected.put("join-switch", List.of("%current%"));
        expected.put("join-unknown", List.of("%faction%"));
        expected.put("faction-header", List.of("%faction%"));
        expected.put("faction-description", List.of("%description%"));
        expected.put("faction-rank", List.of("%rank%", "%number%"));
        expected.put("faction-reputation", List.of("%reputation%", "%next%"));
        expected.put("faction-done", List.of("%done%", "%total%"));
        expected.put("menu-npc-title", List.of("%npc%"));
        expected.put("menu-item-name", List.of("%name%"));
        expected.put("menu-info-faction", List.of("%faction%"));
        expected.put("menu-info-rank", List.of("%rank%", "%number%"));
        expected.put("menu-info-reputation", List.of("%reputation%", "%next%"));
        expected.put("menu-info-done", List.of("%done%", "%total%"));
        expected.put("menu-info-claims", List.of("%left%", "%limit%"));
        expected.put("menu-info-ready", List.of("%ready%"));
        expected.put("menu-faction-description", List.of("%description%"));
        expected.put("menu-faction-reputation", List.of("%reputation%"));
        expected.put("menu-faction-rank", List.of("%rank%"));
        expected.put("menu-npc-description", List.of("%description%"));
        expected.put("menu-npc-faction", List.of("%faction%"));
        expected.put("menu-npc-progress", List.of("%done%", "%total%"));
        expected.put("menu-npc-current", List.of("%name%"));
        expected.put("menu-quest-description", List.of("%description%"));
        expected.put("menu-quest-target", List.of("%target%"));
        expected.put("menu-quest-progress", List.of("%have%", "%need%"));
        expected.put("menu-quest-reward", List.of("%xp%", "%reputation%"));
        expected.put("menu-quest-rank", List.of("%rank%"));
        expected.put("menu-quest-status", List.of("%status%"));
        expected.put("profile-faction", List.of("%faction%", "%rank%"));
        expected.put("profile-reputation", List.of("%reputation%", "%next%"));
        expected.put("profile-progress", List.of("%done%", "%total%"));
        expected.put("profile-claims", List.of("%left%", "%limit%"));
        expected.put("profile-quest", List.of("%name%", "%have%", "%need%"));
        expected.put("progress-ready", List.of("%name%", "%id%"));
        expected.put("admin-reloaded", List.of("%factions%", "%npcs%", "%quests%"));
        expected.put("admin-info", List.of("%player%", "%faction%", "%reputation%",
                "%done%", "%total%", "%claims%"));
        expected.put("admin-player-not-found", List.of("%player%"));
        expected.put("admin-unknown-faction", List.of("%faction%"));
        expected.put("admin-granted", List.of("%player%", "%amount%", "%faction%",
                "%reputation%"));
        expected.put("admin-reset", List.of("%player%"));
        expected.put("admin-completed", List.of("%player%", "%name%"));

        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            String text = section.getString(entry.getKey(), "");
            for (String placeholder : entry.getValue()) {
                assertTrue(text.contains(placeholder),
                        "в «" + entry.getKey() + "» нет подстановки " + placeholder + ": " + text);
            }
        }
    }

    @Test
    void statusAndRankKeysArePlainText() throws Exception {
        ConfigurationSection section = load();
        for (String key : List.of("status-available", "status-progress", "status-ready",
                "status-done", "status-locked", "status-faction", "status-rank",
                "rank.newcomer", "rank.fighter", "rank.veteran", "rank.captain",
                "rank.marshal", "faction-max", "faction-neutral", "faction-nobody")) {
            String text = section.getString(key, "");
            assertFalse(text.contains("%"), "статус не должен ждать подстановок: " + key);
        }
    }
}
