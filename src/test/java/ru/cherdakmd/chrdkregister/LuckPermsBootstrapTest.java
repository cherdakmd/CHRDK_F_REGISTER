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
    void schemeHasDefaultPassAdminWithWeightsAndParents() throws Exception {
        List<LuckPermsBootstrap.GroupSpec> scheme = bootstrap("").scheme();
        assertEquals(3, scheme.size());
        LuckPermsBootstrap.GroupSpec base = scheme.get(0);
        assertEquals("default", base.name());
        assertEquals(0, base.weight());
        assertNull(base.parent(), "у default нет родителя");
        LuckPermsBootstrap.GroupSpec pass = scheme.get(1);
        assertEquals("pass", pass.name());
        assertEquals(10, pass.weight());
        assertEquals("default", pass.parent());
        LuckPermsBootstrap.GroupSpec admin = scheme.get(2);
        assertEquals("admin", admin.name());
        assertEquals(100, admin.weight());
        assertEquals("default", admin.parent());
        assertFalse(base.permissions().isEmpty(), "default наполняется правами игрока");
        assertTrue(base.permissions().contains("chrdkregister.menu"));
        assertTrue(admin.permissions().contains("chrdkregister.admin"));
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
