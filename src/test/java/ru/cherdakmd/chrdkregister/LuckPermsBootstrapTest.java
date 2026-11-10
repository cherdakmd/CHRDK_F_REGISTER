package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Схема авто-настройки LuckPerms (1.4.63/1.4.64): состав групп, deny-лист,
 * слияние обнаруженных у сервера chrdk*-прав с базовыми наборами.
 */
class LuckPermsBootstrapTest {

    private static LuckPermsBootstrap bootstrap(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        if (yaml != null && !yaml.isBlank()) {
            config.loadFromString(yaml);
        }
        PluginConfig parsed = PluginConfig.fromBukkitConfig(config);
        return new LuckPermsBootstrap(() -> parsed);
    }

    @Test
    void passGroupNameFallsBackToPass() throws Exception {
        assertEquals("pass", bootstrap("").passGroupName(),
                "без integrations.pass-group предлагаем группу 'pass'");
        assertEquals("vip", bootstrap("integrations:\n  pass-group: vip\n").passGroupName(),
                "настроенное имя проходки имеет приоритет");
    }

    @Test
    void schemeHasDefaultPassModeratorAdminWithWeightsAndParents() throws Exception {
        List<LuckPermsBootstrap.GroupSpec> scheme = bootstrap("").scheme();
        assertEquals(4, scheme.size());
        LuckPermsBootstrap.GroupSpec base = scheme.get(0);
        assertEquals("default", base.name());
        assertEquals(0, base.weight());
        assertNull(base.parent(), "у default нет родителя");
        LuckPermsBootstrap.GroupSpec pass = scheme.get(1);
        assertEquals("pass", pass.name());
        assertEquals(10, pass.weight());
        assertEquals("default", pass.parent());
        LuckPermsBootstrap.GroupSpec moderator = scheme.get(2);
        assertEquals("moderator", moderator.name());
        assertEquals(50, moderator.weight());
        assertEquals("default", moderator.parent());
        LuckPermsBootstrap.GroupSpec admin = scheme.get(3);
        assertEquals("admin", admin.name());
        assertEquals(100, admin.weight());
        assertEquals("default", admin.parent());
        assertFalse(base.permissions().isEmpty(), "default наполняется правами игрока");
        assertTrue(base.permissions().contains("chrdkregister.menu"));
        assertTrue(admin.permissions().contains("chrdkregister.admin"));
        for (String prefix : List.of("chrdklottery.", "chrdkparty.", "chrdkref.", "chrdkevents.")) {
            assertFalse(base.permissions().stream().anyMatch(key -> key.startsWith(prefix)));
            assertFalse(admin.permissions().stream().anyMatch(key -> key.startsWith(prefix)));
        }
        assertTrue(base.permissions().contains("chrdkauction.use"));
        assertTrue(admin.permissions().contains("chrdkauction.admin"));
        assertFalse(base.permissions().contains("chrdkauction.admin"));
    }

    @Test
    void commandProtectIsBaselinePlusLowercasedExtra() throws Exception {
        LuckPermsBootstrap plain = bootstrap("");
        assertFalse(plain.available(), "тестовый бутстрап без хуков");
        List<String> baseline = plain.commandProtect();
        assertTrue(baseline.contains("bukkit.command.plugins"));
        assertTrue(baseline.contains("minecraft.command.op"));
        assertTrue(baseline.contains("bukkit.command.reload"));

        LuckPermsBootstrap withExtra = bootstrap(
                "integrations:\n  lp-deny-extra: [\"Essentials.Fly\", \"vanish.silent\"]\n");
        List<String> merged = withExtra.commandProtect();
        assertTrue(merged.contains("essentials.fly"), "extra приводится к lower-case");
        assertTrue(merged.contains("vanish.silent"));
        assertTrue(merged.contains("bukkit.command.plugins"), "встроенные не теряются");
    }

    @Test
    void mergeUniqueDeduplicatesCaseInsensitiveAndKeepsBaseline() {
        java.util.LinkedHashSet<String> discovered = new java.util.LinkedHashSet<>();
        discovered.add("ChrdkArtifacts.Use");
        discovered.add("chrdknew.use");
        discovered.add(" ");
        discovered.add("");
        List<String> merged = LuckPermsBootstrap.mergeUnique(
                List.of("chrdkregister.menu", "chrdkartifacts.use"), discovered);
        assertEquals(List.of("chrdkregister.menu", "chrdkartifacts.use", "chrdknew.use"),
                merged, "дубликаты в другом регистре не множатся, порядок — базовый, затем новые");
    }

    @Test
    void socialPermissionsArePresentButBypassNeverLeaksIntoDefaultOrAdmin() throws Exception {
        List<LuckPermsBootstrap.GroupSpec> scheme = bootstrap("").scheme();
        assertTrue(scheme.get(0).permissions().contains("chrdksocial.use"),
                "базовые права социального плагина есть даже без загруженного CHRDK_VK");
        assertTrue(scheme.get(3).permissions().contains("chrdksocial.admin"));
        assertFalse(scheme.get(0).permissions().contains("chrdksocial.bypass"));
        assertFalse(scheme.get(3).permissions().contains("chrdksocial.bypass"));

        assertEquals(List.of("chrdkregister.menu", "chrdksocial.admin"),
                LuckPermsBootstrap.withoutBypass(
                        List.of("chrdkregister.menu", "ChrdkSocial.Bypass", "chrdksocial.admin"),
                        "chrdksocial.bypass"),
                "узел обхода вырезается регистронезависимо");
        assertEquals(List.of("chrdkregister.menu"),
                LuckPermsBootstrap.withoutBypass(List.of("chrdkregister.menu"), ""),
                "пустой узел ничего не ломает");
    }

    @Test
    void bypassNodeIsConfigurableAndDefaultsToSocialBypass() throws Exception {
        YamlConfiguration plain = new YamlConfiguration();
        plain.loadFromString("");
        assertEquals("chrdksocial.bypass",
                PluginConfig.fromBukkitConfig(plain).lpSocialBypassPermission());

        YamlConfiguration custom = new YamlConfiguration();
        custom.loadFromString("integrations:\n  lp-social-bypass-permission: \"Custom.Bypass\"\n");
        assertEquals("custom.bypass",
                PluginConfig.fromBukkitConfig(custom).lpSocialBypassPermission());
    }

    @Test
    void passGroupNeverInheritsBypassEvenIfAddedManually() throws Exception {
        var scheme = bootstrap("integrations:\n  pass-group: donator\n"
                + "  pass-group-permissions: [\"chrdksocial.bypass\", \"chrdkregister.menu\"]\n").scheme();
        var pass = scheme.stream().filter(spec -> spec.name().equals("donator")).findFirst();
        assertTrue(pass.isPresent());
        assertEquals(List.of("chrdkregister.menu"), pass.get().permissions(),
                "узел обхода вырезается и из группы проходки");
    }

    @Test
    void dayPassCreatesTemporaryLuckPermsGroupWithBypassOnly() throws Exception {
        String yaml = """
                shop:
                  enabled: true
                  max-total-days: 365
                  products:
                    - id: social-access-day
                      name: "Вход без ВК и MAX — 1 день"
                      price: 15
                      days: 1
                      group: "social-day"
                      description: "Пробный доступ на сутки"
                      tag: ""
                      permissions: ["chrdksocial.bypass"]
                      commands: []
                """;
        List<LuckPermsBootstrap.GroupSpec> scheme = bootstrap(yaml).scheme();
        var group = scheme.stream().filter(spec -> spec.name().equals("social-day")).findFirst();
        assertTrue(group.isPresent(), "временная группа товара создаётся /pass luckperms setup");
        assertEquals(20, group.get().weight());
        assertEquals("default", group.get().parent());
        assertEquals(List.of("chrdksocial.bypass"), group.get().permissions());
        assertFalse(scheme.get(0).permissions().contains("chrdksocial.bypass"),
                "default не наследует обход привязки");
    }

    @Test
    void moderatorHasOnlyModerationPermissionsAndNoPrivilegeEscalation() throws Exception {
        LuckPermsBootstrap.GroupSpec moderator = bootstrap("").scheme().stream()
                .filter(spec -> spec.name().equals(LuckPermsBootstrap.MODERATOR_GROUP)).findFirst().orElseThrow();
        assertEquals(List.of("chrdkregister.admin.panel", "chrdkregister.chat.mute",
                "chrdkregister.admin.audit"), moderator.permissions());
        for (String forbidden : List.of("chrdkregister.admin", "chrdkregister.admin.moderators",
                "chrdkregister.chat.socialspy", "chrdksocial.bypass", "*")) {
            assertFalse(moderator.permissions().contains(forbidden));
        }
        assertFalse(PluginConfig.fromBukkitConfig(new YamlConfiguration()).lpModeratorSocialspy());
    }

    @Test
    void moderatorSocialspyIsAnExplicitOptIn() throws Exception {
        LuckPermsBootstrap.GroupSpec moderator = bootstrap(
                "integrations:\n  lp-moderator-socialspy: true\n").scheme().stream()
                .filter(spec -> spec.name().equals("moderator")).findFirst().orElseThrow();
        assertTrue(moderator.permissions().contains("chrdkregister.chat.socialspy"));
        assertFalse(moderator.permissions().contains("chrdkregister.admin.moderators"));
    }

    @Test
    void splitDiscoveredFiltersBySuffix() {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        keys.add("chrdkartifacts.use");
        keys.add("chrdkshop.use");
        keys.add("chrdkshop.admin");
        keys.add("other.use");
        assertEquals(List.of("chrdkartifacts.use", "chrdkshop.use"),
                LuckPermsBootstrap.splitDiscovered(keys, ".use"));
        assertEquals(List.of("chrdkshop.admin"),
                LuckPermsBootstrap.splitDiscovered(keys, ".admin"));
    }
}
