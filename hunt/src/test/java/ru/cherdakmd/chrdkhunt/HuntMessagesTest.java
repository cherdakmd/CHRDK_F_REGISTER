package ru.cherdakmd.chrdkhunt;

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
 * Контракт текстов охоты (1.4.155): все ключи, к которым обращается код,
 * существуют, а подстановки в шаблонах не потеряны.
 */
class HuntMessagesTest {

    private static final List<String> REQUIRED = List.of(
            "no-permission", "player-only", "disabled", "unknown-subcommand",
            "help-header", "help-stats", "help-shop", "help-buy", "help-exchange",
            "help-keys", "help-chest", "help-bestiary", "help-trophies", "help-top",
            "help-footer",
            "kill-coins", "kill-separator", "kill-shards", "kill-key", "kill-dropped",
            "coins-cap", "boss-killed", "boss-broadcast",
            "exchange-closed", "exchange-limit", "exchange-min", "exchange-no-coins",
            "exchange-ok", "exchange-dropped", "exchange-bad-amount",
            "shop-title", "shop-empty-name", "shop-empty-lore", "shop-info-name",
            "shop-info-lore", "shop-icon-price", "shop-icon-limit", "shop-icon-limit-none",
            "shop-icon-earned", "shop-icon-wallet", "shop-icon-click", "shop-icon-locked",
            "shop-unknown", "shop-locked", "shop-limit", "shop-no-coins", "shop-bought",
            "shop-dropped", "shop-bad-amount",
            "keys-title", "keys-icon-name", "keys-icon-shards", "keys-icon-kills",
            "keys-icon-chest", "keys-icon-no-chest", "keys-info-name", "keys-info-lore",
            "keys-info-total", "chest-unknown", "chest-no-chest", "chest-empty",
            "chest-no-key", "chest-opened", "chest-loot-line", "chest-diamonds",
            "chest-dropped",
            "trophy-title", "trophy-empty-name", "trophy-empty-lore", "trophy-info-name",
            "trophy-info-lore", "trophy-icon-cost", "trophy-icon-shards",
            "trophy-icon-earned", "trophy-icon-owned", "trophy-icon-locked",
            "trophy-icon-click", "trophy-unknown", "trophy-locked", "trophy-no-shards",
            "trophy-no-coins", "trophy-crafted", "trophy-not-owned", "trophy-claimed",
            "trophy-received", "trophy-dropped", "trophy-item-tag",
            "stats-header", "stats-coins", "stats-rank", "stats-next", "stats-rank-max",
            "stats-shards", "stats-keys", "stats-kills", "stats-trophies",
            "stats-exchange", "stats-family", "stats-footer", "stats-player-not-found",
            "top-header", "top-row", "top-empty", "top-footer",
            "menu-title", "menu-info-name", "menu-info-lore", "menu-info-rank",
            "menu-info-next", "menu-info-max", "menu-info-exchange", "menu-shop",
            "menu-shop-lore", "menu-bestiary", "menu-bestiary-lore", "menu-bestiary-empty",
            "menu-bestiary-empty-lore", "menu-trophies", "menu-trophies-lore", "menu-keys",
            "menu-keys-lore", "menu-exchange", "menu-exchange-lore", "menu-stats",
            "menu-stats-lore", "menu-top", "menu-top-lore", "menu-close",
            "admin-reloaded", "admin-given", "admin-reset", "admin-reset-missing",
            "admin-player-not-found", "admin-bad-amount", "admin-bad-kind",
            "admin-unknown-family", "admin-usage", "admin-info",
            "bestiary-title", "bestiary-section-title", "bestiary-empty",
            "bestiary-unknown-section", "bestiary-info-name", "bestiary-info-seen",
            "bestiary-info-sections", "bestiary-info-full", "bestiary-info-hint",
            "bestiary-section-name", "bestiary-section-progress", "bestiary-section-reward",
            "bestiary-section-claimed", "bestiary-section-ready", "bestiary-section-locked",
            "bestiary-section-open", "bestiary-section-done", "bestiary-reward-claimed",
            "bestiary-entry-name", "bestiary-entry-kills", "bestiary-entry-boss",
            "bestiary-entry-locked", "bestiary-entry-unknown-name",
            "bestiary-entry-hint-known", "bestiary-entry-hint-unknown", "bestiary-new",
            "bestiary-reward-locked-name", "bestiary-reward-locked",
            "bestiary-reward-ready-name", "bestiary-reward-ready",
            "bestiary-reward-claimed-name", "bestiary-reward-claimed-lore",
            "bestiary-reward-value", "bestiary-reward-coins", "bestiary-reward-shards",
            "bestiary-reward-keys", "bestiary-reward-none", "bestiary-back",
            "bestiary-full", "bestiary-full-broadcast", "profile-bestiary");

    private static ConfigurationSection load() throws Exception {
        try (InputStream stream = HuntMessagesTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(stream, "messages.yml должен лежать в ресурсах модуля");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            ConfigurationSection section = yaml.getConfigurationSection("hunt");
            assertNotNull(section, "тексты должны лежать под секцией hunt:");
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
    void ranksCoverTheDefaultLadder() throws Exception {
        ConfigurationSection section = load();
        for (String rank : HuntConfig.defaultRanks().values()) {
            String text = section.getString("rank." + rank, "");
            assertFalse(text.isBlank(), "нет звания: " + rank);
        }
    }

    @Test
    void placeholdersMatchTheCode() throws Exception {
        ConfigurationSection section = load();
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("kill-coins", List.of("%coins%"));
        expected.put("kill-shards", List.of("%shards%", "%shard%"));
        expected.put("kill-key", List.of("%keys%", "%key%"));
        expected.put("coins-cap", List.of("%limit%"));
        expected.put("boss-killed", List.of("%family%", "%coins%", "%shard%"));
        expected.put("boss-broadcast", List.of("%player%", "%family%"));
        expected.put("exchange-ok", List.of("%diamonds%", "%coins%", "%left%"));
        expected.put("exchange-no-coins", List.of("%cost%", "%coins%"));
        expected.put("exchange-limit", List.of("%limit%"));
        expected.put("shop-bought", List.of("%item%", "%amount%", "%price%", "%coins%"));
        expected.put("shop-icon-price", List.of("%price%", "%amount%"));
        expected.put("shop-icon-limit", List.of("%limit%", "%bought%"));
        expected.put("shop-icon-earned", List.of("%need%", "%earned%"));
        expected.put("shop-locked", List.of("%need%", "%earned%"));
        expected.put("chest-opened", List.of("%family%", "%coins%", "%shards%", "%shard%"));
        expected.put("chest-loot-line", List.of("%item%", "%amount%"));
        expected.put("trophy-crafted", List.of("%trophy%", "%shards%", "%coins%", "%shard%"));
        expected.put("trophy-no-shards", List.of("%need%", "%have%", "%shard%"));
        expected.put("trophy-icon-cost", List.of("%shards%", "%shard%", "%coins%"));
        expected.put("stats-coins", List.of("%coins%", "%earned%"));
        expected.put("stats-family", List.of("%family%", "%kills%", "%shards%", "%keys%"));
        expected.put("top-row", List.of("%num%", "%player%", "%earned%", "%kills%",
                "%trophies%", "%rank%"));
        expected.put("menu-info-lore", List.of("%coins%", "%earned%"));
        expected.put("menu-info-exchange", List.of("%left%", "%limit%"));
        expected.put("keys-icon-name", List.of("%keys%", "%key%", "%family%"));
        expected.put("shop-title", List.of("%coins%"));
        expected.put("trophy-title", List.of("%owned%", "%total%"));
        expected.put("admin-given", List.of("%player%", "%amount%", "%kind%"));
        expected.put("admin-info", List.of("%families%", "%mobs%", "%shop%", "%trophies%",
                "%players%", "%earned%", "%enabled%"));
        expected.put("bestiary-title", List.of("%seen%", "%total%"));
        expected.put("bestiary-section-name", List.of("%section%", "%seen%", "%total%"));
        expected.put("bestiary-section-progress", List.of("%seen%", "%total%", "%percent%"));
        expected.put("bestiary-section-reward", List.of("%reward%"));
        expected.put("bestiary-section-done", List.of("%section%", "%reward%", "%total%"));
        expected.put("bestiary-section-locked", List.of("%seen%", "%total%"));
        expected.put("bestiary-reward-claimed", List.of("%section%"));
        expected.put("bestiary-unknown-section", List.of("%id%"));
        expected.put("bestiary-new", List.of("%mob%", "%section%", "%seen%", "%total%"));
        expected.put("bestiary-entry-name", List.of("%mob%"));
        expected.put("bestiary-entry-kills", List.of("%kills%"));
        expected.put("bestiary-entry-hint-known", List.of("%mob%", "%kills%"));
        expected.put("bestiary-reward-coins", List.of("%coins%"));
        expected.put("bestiary-reward-shards", List.of("%shards%", "%shard%"));
        expected.put("bestiary-reward-keys", List.of("%keys%", "%key%"));
        expected.put("bestiary-full", List.of("%entries%", "%coins%"));
        expected.put("bestiary-full-broadcast", List.of("%player%"));
        expected.put("bestiary-info-sections", List.of("%closed%", "%sections%"));
        expected.put("bestiary-info-full", List.of("%coins%"));
        expected.put("bestiary-reward-locked", List.of("%seen%", "%total%"));
        expected.put("bestiary-reward-value", List.of("%reward%"));
        expected.put("menu-bestiary", List.of("%seen%", "%total%"));
        expected.put("profile-bestiary", List.of("%seen%", "%total%"));
        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            String text = section.getString(entry.getKey(), "");
            for (String placeholder : entry.getValue()) {
                assertTrue(text.contains(placeholder),
                        entry.getKey() + " потерял " + placeholder + ": " + text);
            }
        }
    }
}
