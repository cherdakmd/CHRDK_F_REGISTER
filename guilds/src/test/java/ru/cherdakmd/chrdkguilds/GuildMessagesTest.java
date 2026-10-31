package ru.cherdakmd.chrdkguilds;

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
 * Контракт текстов гильдий (1.4.163): все ключи, к которым обращается код,
 * существуют, а подстановки в шаблонах не потеряны.
 *
 * <p>Пропавший ключ не роняет сервер, но оставляет игрока без ответа — а в
 * гильдиях это дорого: человек не понимает, ушёл ли взнос в казну.</p>
 */
class GuildMessagesTest {

    private static final List<String> REQUIRED = List.of(
            "dues-bank","dues-join-required","dues-range","dues-same","dues-cooldown","dues-settle-first","dues-rate-set","dues-paid","dues-member-grace","dues-leader-grace","dues-auto-kick","dues-auto-disband","dues-join-confirm","dues-create-confirm","dues-rate-confirm","dues-status","dues-info","dues-invite","dues-menu-name","dues-menu-hint","dues-new-clan", "tab-battle-prefix", "tab-peace-prefix", "tab-peace-pending-prefix", "menu-mode-peaceful", "menu-mode-battle", "peace-help", "peace-usage", "peace-same-mode", "peace-war-busy", "peace-war-disabled", "peace-combat", "peace-mode-cooldown", "peace-debt-mode", "peace-no-money", "peace-mode-battle", "peace-mode-on", "peace-not-peaceful", "peace-days", "peace-treasury-low", "peace-prepay-cap", "peace-prepay-behind", "peace-prepaid", "peace-idle", "peace-paid", "peace-leader-grace", "peace-member-grace", "peace-auto-disband", "peace-auto-kick", "peace-fined", "peace-incident-active", "peace-debt-collected", "peace-confirm", "peace-preview-cost", "peace-quote-expired", "peace-mode-notice", "peace-debt-status", "peace-status", "peace-term-status", "peace-grace-status", "peace-leader-help", "peace-personal-notice", "peace-join-required", "peace-menu-name", "peace-menu-mode", "peace-menu-price", "peace-menu-hint", "peace-chat-prefix", "peace-profile-mode", "peace-profile-term", "peace-join-confirm", "peace-join-terms", "peace-settle-first", "peace-leader-risk", "peace-pending-prefix",
            "menu-claims-name", "menu-claims-lore", "menu-personal-name", "menu-personal-lore", "menu-personal-boundary",
            "no-permission", "player-only", "disabled", "unknown-subcommand", "guild-not-found",
            "not-in-guild", "not-leader", "already-in-guild", "target-not-found",
            "target-in-guild", "target-not-in-guild", "target-self", "guild-full", "max-guilds",
            "amount-bad", "status-online", "status-offline", "help-header", "help-create",
            "help-invite", "help-join", "help-leave", "help-ranks", "help-treasury", "help-war",
            "help-chat", "help-info", "help-top", "create-usage", "create-ready", "create-ok",
            "create-broadcast", "create-no-diamonds", "create-no-economy", "name-short",
            "name-long", "tag-short", "tag-long", "name-taken", "tag-taken", "rename-ok",
            "rename-usage", "retag-ok", "retag-usage", "disband-confirm", "disband-ok",
            "disband-broadcast", "disband-war-none", "disband-war-cancelled", "invite-usage",
            "invite-sent", "invite-received", "invite-reminder", "invite-declined", "invite-none",
            "invite-expired", "invite-guild-gone", "invite-no-rank", "join-ok", "join-broadcast",
            "leave-ok", "leave-leader", "leave-broadcast", "kick-usage", "kick-ok", "kick-no-rank",
            "kick-broadcast", "kick-notice", "rank-usage", "rank-officer", "rank-max", "rank-min",
            "rank-no-rights", "promote-ok", "demote-ok", "promote-notice", "demote-notice",
            "transfer-usage", "transfer-ok", "transfer-no-target", "transfer-notice",
            "rank.leader", "rank.officer", "rank.member", "deposit-usage", "deposit-ok",
            "deposit-broadcast", "deposit-empty", "deposit-no-diamonds", "deposit-no-economy",
            "treasury-full", "treasury-empty", "treasury-info", "withdraw-usage", "withdraw-ok",
            "withdraw-empty", "withdraw-leader-only", "upgrade-ok", "upgrade-max",
            "upgrade-no-money", "upgrade-broadcast", "war-usage", "war-disabled", "war-no-target",
            "war-cooldown", "war-busy", "war-target-busy", "war-too-many", "war-wager-min",
            "war-wager-max", "war-no-money", "war-declared", "war-declared-broadcast", "war-start",
            "war-start-broadcast", "war-declined", "war-accept-none", "war-accept-target-only",
            "war-not-active", "war-surrender", "war-point", "war-point-actionbar",
            "war-finished-win", "war-finished-draw", "war-expired", "war-status",
            "war-status-none", "war-state-pending", "war-state-active",
            "war-result.challenger_win", "war-result.target_win", "war-result.draw",
            "war-result.cancelled", "info-header", "info-leader", "info-members", "info-level",
            "info-treasury", "info-wars", "info-war", "members-header", "members-line",
            "member-info", "top-header", "top-line", "top-empty", "top-usage", "chat-usage",
            "chat-empty", "chat-format", "chat-prefix", "menu-title", "menu-info-title",
            "menu-info-no-guild", "menu-no-guild", "menu-no-guild-hint", "menu-invite-name",
            "menu-invite-lore", "menu-info-name", "menu-info-rank", "menu-info-members",
            "menu-info-level", "menu-info-glory", "menu-member-rank", "menu-member-click",
            "menu-item-name", "menu-treasury-name", "menu-treasury-balance", "menu-treasury-hint",
            "menu-upgrade-name", "menu-upgrade-level", "menu-upgrade-cost", "menu-upgrade-click",
            "menu-upgrade-max", "menu-war-name", "menu-war-none", "menu-war-cooldown",
            "menu-war-hint", "menu-war-pending", "menu-war-active", "menu-war-opponent",
            "menu-war-score", "menu-war-left", "menu-close", "menu-back", "profile-title",
            "profile-none", "profile-none-hint", "profile-name", "profile-rank", "profile-members",
            "profile-treasury", "profile-glory", "profile-war", "admin-usage", "admin-reloaded",
            "admin-guild-info", "admin-disbanded", "admin-cooldown", "admin-cooldown-none",
            "admin-history-empty", "admin-history-line", "admin-history-unknown",
            "finance-paused", "finance-auth", "finance-mode", "treasury-war-returns",
            // Общий PaidActions читает эти ключи из Messages вызывающего модуля.
            "finance.service-quote", "finance.service-mode", "finance.service-wait",
            "finance.service-insufficient", "finance.service-refused", "finance.service-refunding",
            "finance.service-checked", "finance.service-payment", "finance.service-action",
            "finance.service-status", "finance.service-resolve-usage", "finance.service-lock"
            );

    private static ConfigurationSection load() throws Exception {
        try (InputStream stream = GuildMessagesTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(stream, "messages.yml должен лежать в ресурсах модуля");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            ConfigurationSection section = yaml.getConfigurationSection("guilds");
            assertNotNull(section, "тексты должны лежать под секцией guilds:");
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
    void ranksAndStatusesArePlainText() throws Exception {
        ConfigurationSection section = load();
        for (String key : List.of("rank.leader", "rank.officer", "rank.member",
                "status-online", "status-offline", "war-state-pending", "war-state-active",
                "admin-history-unknown", "profile-title", "menu-info-title", "menu-back",
                "menu-close", "menu-treasury-name", "menu-upgrade-name", "menu-war-name",
                "menu-info-no-guild")) {
            String text = section.getString(key, "");
            assertFalse(text.contains("%"), "статус не должен ждать подстановок: " + key);
        }
    }

    @Test
    void placeholdersMatchTheCode() throws Exception {
        ConfigurationSection section = load();
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("peace-mode-on", List.of("%seconds%", "%cost%", "%treasury%", "%personal%"));
        expected.put("peace-treasury-low", List.of("%cost%"));
        expected.put("peace-prepaid", List.of("%members%", "%days%", "%cost%"));
        expected.put("peace-paid", List.of("%days%", "%cost%"));
        expected.put("peace-leader-grace", List.of("%deadline%"));
        expected.put("peace-member-grace", List.of("%deadline%"));
        expected.put("peace-auto-disband", List.of("%name%", "%treasury%", "%returns%"));
        expected.put("peace-auto-kick", List.of("%name%"));
        expected.put("peace-fined", List.of("%fine%", "%debt%"));
        expected.put("peace-debt-collected", List.of("%paid%", "%debt%"));
        expected.put("peace-confirm", List.of("%action%", "%days%", "%personal%", "%rate%"));
        expected.put("peace-preview-cost", List.of("%treasury%", "%personal%"));
        expected.put("peace-mode-notice", List.of("%mode%", "%rate%"));
        expected.put("peace-debt-status", List.of("%debt%"));
        expected.put("peace-status", List.of("%mode%", "%rate%", "%total%"));
        expected.put("peace-term-status", List.of("%paid%", "%active%", "%deadline%"));
        expected.put("peace-grace-status", List.of("%deadline%"));
        expected.put("peace-personal-notice", List.of("%text%"));
        expected.put("peace-menu-mode", List.of("%mode%"));
        expected.put("peace-menu-price", List.of("%rate%", "%total%"));
        expected.put("peace-chat-prefix", List.of("%tag%"));
        expected.put("peace-profile-mode", List.of("%mode%", "%rate%"));
        expected.put("peace-profile-term", List.of("%paid%"));
        expected.put("peace-join-confirm", List.of("%cost%"));
        expected.put("peace-join-terms", List.of("%cost%"));
        expected.put("peace-leader-risk", List.of("%deadline%"));
        expected.put("peace-pending-prefix", List.of("%tag%"));
        expected.put("treasury-war-returns", List.of("%amount%"));
        expected.put("finance.service-insufficient", List.of("%cost%"));
        expected.put("unknown-subcommand", List.of("%sub%"));
        expected.put("guild-not-found", List.of("%name%"));
        expected.put("target-not-found", List.of("%player%"));
        expected.put("target-in-guild", List.of("%player%"));
        expected.put("guild-full", List.of("%max%"));
        expected.put("max-guilds", List.of("%max%"));
        expected.put("create-usage", List.of("%min%", "%max%"));
        expected.put("create-ready", List.of("%name%", "%cost%"));
        expected.put("create-ok", List.of("%name%", "%tag%"));
        expected.put("create-broadcast", List.of("%name%", "%tag%", "%leader%"));
        expected.put("create-no-diamonds", List.of("%cost%"));
        expected.put("name-short", List.of("%min%"));
        expected.put("name-long", List.of("%max%"));
        expected.put("tag-short", List.of("%min%"));
        expected.put("tag-long", List.of("%max%"));
        expected.put("name-taken", List.of("%name%"));
        expected.put("tag-taken", List.of("%tag%"));
        expected.put("rename-ok", List.of("%name%"));
        expected.put("retag-ok", List.of("%tag%"));
        expected.put("retag-usage", List.of("%max%"));
        expected.put("disband-confirm", List.of("%name%", "%treasury%", "%returns%"));
        expected.put("disband-ok", List.of("%name%", "%members%", "%treasury%", "%returns%", "%war%"));
        expected.put("disband-broadcast", List.of("%name%"));
        expected.put("invite-sent", List.of("%name%", "%seconds%"));
        expected.put("invite-received", List.of("%player%", "%name%", "%seconds%"));
        expected.put("invite-reminder", List.of("%name%", "%seconds%"));
        expected.put("join-ok", List.of("%name%", "%members%"));
        expected.put("join-broadcast", List.of("%player%"));
        expected.put("leave-ok", List.of("%name%"));
        expected.put("leave-broadcast", List.of("%player%"));
        expected.put("kick-ok", List.of("%player%", "%members%"));
        expected.put("kick-broadcast", List.of("%by%", "%player%"));
        expected.put("kick-notice", List.of("%name%"));
        expected.put("promote-ok", List.of("%name%", "%rank%"));
        expected.put("demote-ok", List.of("%name%"));
        expected.put("promote-notice", List.of("%rank%"));
        expected.put("demote-notice", List.of("%rank%"));
        expected.put("transfer-notice", List.of("%player%"));
        expected.put("deposit-ok", List.of("%amount%", "%total%"));
        expected.put("deposit-broadcast", List.of("%player%", "%amount%"));
        expected.put("deposit-no-diamonds", List.of("%amount%"));
        expected.put("treasury-full", List.of("%max%"));
        expected.put("treasury-info", List.of("%amount%", "%cap%"));
        expected.put("withdraw-ok", List.of("%amount%", "%total%"));
        expected.put("upgrade-ok", List.of("%level%", "%cap%"));
        expected.put("upgrade-no-money", List.of("%cost%", "%have%"));
        expected.put("upgrade-broadcast", List.of("%level%", "%cap%"));
        expected.put("war-cooldown", List.of("%time%"));
        expected.put("war-target-busy", List.of("%name%"));
        expected.put("war-too-many", List.of("%max%"));
        expected.put("war-wager-min", List.of("%min%"));
        expected.put("war-wager-max", List.of("%max%"));
        expected.put("war-no-money", List.of("%cost%", "%have%"));
        expected.put("war-declared", List.of("%challenger%", "%target%", "%wager%", "%seconds%"));
        expected.put("war-declared-broadcast", List.of("%name%", "%wager%", "%seconds%"));
        expected.put("war-start", List.of("%challenger%", "%target%", "%wager%", "%time%"));
        expected.put("war-start-broadcast", List.of("%challenger%", "%target%", "%wager%",
                "%time%"));
        expected.put("war-declined", List.of("%challenger%"));
        expected.put("war-surrender", List.of("%name%", "%winner%"));
        expected.put("war-point", List.of("%points%", "%name%", "%score%"));
        expected.put("war-point-actionbar", List.of("%points%", "%score%"));
        expected.put("war-finished-win", List.of("%winner%", "%points%", "%wager%"));
        expected.put("war-finished-draw", List.of("%points%", "%wager%"));
        expected.put("war-expired", List.of("%challenger%", "%target%"));
        expected.put("war-status", List.of("%name%", "%opponent%", "%state%", "%score%",
                "%wager%", "%time%"));
        expected.put("info-header", List.of("%name%", "%tag%"));
        expected.put("info-leader", List.of("%leader%"));
        expected.put("info-members", List.of("%count%", "%cap%"));
        expected.put("info-level", List.of("%level%", "%glory%"));
        expected.put("info-treasury", List.of("%amount%"));
        expected.put("info-wars", List.of("%won%", "%lost%"));
        expected.put("info-war", List.of("%state%", "%name%", "%score%"));
        expected.put("members-header", List.of("%name%"));
        expected.put("members-line", List.of("%player%", "%rank%", "%online%"));
        expected.put("member-info", List.of("%name%", "%rank%", "%guild%"));
        expected.put("top-header", List.of("%count%"));
        expected.put("top-line", List.of("%place%", "%name%", "%level%", "%members%", "%glory%"));
        expected.put("chat-format", List.of("%tag%", "%player%", "%message%"));
        expected.put("chat-prefix", List.of("%tag%"));
        expected.put("menu-no-guild", List.of("%cost%"));
        expected.put("menu-invite-lore", List.of("%name%", "%seconds%"));
        expected.put("menu-info-name", List.of("%name%", "%tag%"));
        expected.put("menu-info-rank", List.of("%rank%"));
        expected.put("menu-info-members", List.of("%count%", "%cap%"));
        expected.put("menu-info-level", List.of("%level%"));
        expected.put("menu-info-glory", List.of("%glory%", "%won%", "%lost%"));
        expected.put("menu-member-rank", List.of("%rank%"));
        expected.put("menu-item-name", List.of("%name%"));
        expected.put("menu-treasury-balance", List.of("%amount%"));
        expected.put("menu-upgrade-level", List.of("%level%", "%cap%"));
        expected.put("menu-upgrade-cost", List.of("%next%", "%cost%", "%cap%"));
        expected.put("menu-war-cooldown", List.of("%time%"));
        expected.put("menu-war-hint", List.of("%min%", "%max%"));
        expected.put("menu-war-opponent", List.of("%name%", "%state%"));
        expected.put("menu-war-score", List.of("%score%", "%wager%"));
        expected.put("menu-war-left", List.of("%time%"));
        expected.put("profile-none", List.of("%cost%"));
        expected.put("profile-name", List.of("%name%", "%tag%"));
        expected.put("profile-rank", List.of("%rank%"));
        expected.put("profile-members", List.of("%count%", "%cap%"));
        expected.put("profile-treasury", List.of("%amount%", "%level%"));
        expected.put("profile-glory", List.of("%glory%", "%won%"));
        expected.put("profile-war", List.of("%opponent%", "%score%", "%state%"));
        expected.put("admin-reloaded", List.of("%guilds%", "%wars%"));
        expected.put("admin-guild-info", List.of("%id%", "%created%", "%war%"));
        expected.put("admin-disbanded", List.of("%name%", "%treasury%", "%returns%", "%war%"));
        expected.put("admin-cooldown", List.of("%name%"));
        expected.put("admin-cooldown-none", List.of("%name%"));
        expected.put("admin-history-line", List.of("%time%", "%challenger%", "%target%",
                "%result%", "%wager%"));

        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            String text = section.getString(entry.getKey(), "");
            for (String placeholder : entry.getValue()) {
                assertTrue(text.contains(placeholder),
                        "в «" + entry.getKey() + "» нет подстановки " + placeholder + ": " + text);
            }
        }
    }
}
