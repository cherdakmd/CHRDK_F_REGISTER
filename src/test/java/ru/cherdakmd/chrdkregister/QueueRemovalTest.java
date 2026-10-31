package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Снятие входного аддона не удаляет пользовательские настройки и платёжные очереди. */
class QueueRemovalTest {
    private YamlConfiguration yaml(String resource) {
        var input = getClass().getResourceAsStream("/" + resource);
        assertNotNull(input, resource);
        return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    @Test void distributionAndStockPresentationContainOnlyActiveModules() throws Exception {
        assertFalse(Files.exists(Path.of("queue")));
        assertFalse(Files.exists(Path.of("wiki/queue.md")));
        assertFalse(Files.readString(Path.of("settings.gradle")).contains("include 'queue'"));
        assertFalse(PluginUpdateService.MODULES.containsKey("CHRDK_Queue"));
        assertEquals(25, PluginUpdateService.MODULES.size());
        assertEquals(31, ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.API_VERSION);
        var stock = yaml("config.yml");
        assertEquals(50, stock.getMapList("server-menu.items").size());
        assertEquals(43, stock.getList("auto-messages.messages").size());
        assertFalse(stock.saveToString().contains("CHRDK_Queue"));
        assertFalse(stock.saveToString().contains("Вход без очереди"));
        assertFalse(yaml("messages.yml").getString("messages.rank.ability-1").contains("очереди"));
        assertNotNull(yaml("plugin.yml").getConfigurationSection("commands.pass"));
    }

    @Test void oldAliasesAndPluginGatesCannotResurrectAnEntryOrAnnouncement() {
        for (String alias : List.of("queue", "qjoin", "ochered", "/QUEUE", "/chrdk_queue:QJOIN", "/custom:queue", "/custom-name:queue", "/custom.name:qjoin")) {
            String command = alias + " info";
            assertTrue(RetiredAddons.command(command), command);
            var entry = new ServerMenuSettings.Entry(-1, Material.HOPPER, "Old", "", command);
            assertFalse(entry.available(p -> true, p -> true));
            String withSlash = alias.startsWith("/") ? alias : "/" + alias;
            assertFalse(AutoMessage.parse("Сведения: " + withSlash + " info").eligible(p -> true, p -> true, false, false));
        }
        var entry = new ServerMenuSettings.Entry(-1, Material.HOPPER, "Old", "", "custom", "CHRDK_Queue", "");
        assertFalse(entry.available(p -> true, p -> true));
        var category = new ServerMenuSettings.Category("custom", Material.HOPPER, "Old", "", 1, "CHRDK_Queue", "");
        assertFalse(category.available(p -> true, p -> true));
        assertFalse(AutoMessage.parse(Map.of("text", "Old", "plugin", "CHRDK_Queue"))
                .eligible(p -> true, p -> true, false, false));
        assertFalse(RetiredAddons.command("queueadmin"));
        assertFalse(RetiredAddons.command("queue-info"));
        assertFalse(RetiredAddons.announcement("Платёж в очереди /pass pending; сохранение выполняется по очереди"));
        assertTrue(AutoMessage.parse("Платёжные решения: /pass pending").eligible(p -> true, p -> true, false, false));
    }

    @Test void exactStock247MigratesOnceWithoutChangingEconomyOrSchedule() {
        var legacy = yaml("presentation-legacy-1.4.247.yml");
        var live = yaml("presentation-legacy-1.4.247.yml");
        var fresh = yaml("config.yml");
        assertEquals(49, legacy.getMapList("server-menu.items").size());
        assertEquals(44, legacy.getList("auto-messages.messages").size());
        assertTrue(PresentationMigration.LEGACY_RESOURCES.contains("presentation-legacy-1.4.247.yml"));
        live.set("auto-messages.enabled", false);
        live.set("auto-messages.interval-seconds", 777);
        live.set("auto-messages.random-order", false);
        live.set("auto-messages.prefix", "Свой префикс");
        live.set("server-menu.enabled", false);
        String before = live.saveToString();
        var changes = PresentationMigration.replacements(live, List.of(legacy), fresh);
        assertTrue(changes.containsAll(List.of("server-menu.items", "server-menu.categories", "auto-messages.messages", "shop.products")));
        assertEquals(before, live.saveToString());
        for (String key : changes) live.set(key, fresh.get(key));
        assertFalse(live.getBoolean("auto-messages.enabled"));
        assertFalse(live.getBoolean("server-menu.enabled"));
        assertEquals(777, live.getInt("auto-messages.interval-seconds"));
        assertFalse(live.getBoolean("auto-messages.random-order"));
        assertEquals("Свой префикс", live.getString("auto-messages.prefix"));
        assertTrue(PresentationMigration.replacements(live, List.of(legacy), fresh).isEmpty());
        // В каталоге снято только прежнее обещание: цены, сроки, права и товары не меняются.
        var oldProducts = legacy.getMapList("shop.products");
        var newProducts = live.getMapList("shop.products");
        assertEquals(oldProducts.size(), newProducts.size());
        int changed = 0;
        for (int i = 0; i < oldProducts.size(); i++) {
            var old = new HashMap<Object, Object>(oldProducts.get(i));
            var current = new HashMap<Object, Object>(newProducts.get(i));
            if (!old.equals(current)) changed++;
            old.remove("description"); current.remove("description");
            assertEquals(old, current);
        }
        assertEquals(1, changed);
    }

    @Test void customAndEmptyListsAndEditedPricesAreNotOverwritten() {
        var legacy = yaml("presentation-legacy-1.4.247.yml");
        var fresh = yaml("config.yml");
        for (String path : List.of("server-menu.items", "auto-messages.messages", "shop.products")) {
            for (List<?> custom : List.<List<?>>of(List.of(), List.of(Map.of("name", "Свой пункт", "price", 123)))) {
                var live = yaml("presentation-legacy-1.4.247.yml");
                live.set(path, custom);
                assertFalse(PresentationMigration.replacements(live, List.of(legacy), fresh).contains(path), path);
                assertEquals(custom, live.getList(path));
            }
        }
        var live = yaml("presentation-legacy-1.4.247.yml");
        var products = new ArrayList<Map<?, ?>>(live.getMapList("shop.products"));
        var product = new HashMap<Object, Object>(products.getFirst());
        product.put("price", 123);
        products.set(0, product);
        live.set("shop.products", products);
        assertFalse(PresentationMigration.replacements(live, List.of(legacy), fresh).contains("shop.products"));
        assertEquals(123, live.getMapList("shop.products").getFirst().get("price"));
    }

    @Test void evenKnownOlderCatalogCannotChangePricesOrTerms() {
        var legacy = yaml("presentation-legacy-1.4.247.yml");
        var fresh = yaml("config.yml");
        var products = new ArrayList<Map<?, ?>>(legacy.getMapList("shop.products"));
        var product = new HashMap<Object, Object>(products.getFirst());
        product.put("price", 77);
        products.set(0, product);
        legacy.set("shop.products", products);
        var live = yaml("presentation-legacy-1.4.247.yml");
        live.set("shop.products", products);
        assertFalse(PresentationMigration.replacements(live, List.of(legacy), fresh).contains("shop.products"));
        assertEquals(77, live.getMapList("shop.products").getFirst().get("price"));
    }

    @Test void onlyExactLegacyRankMessageChangesInMemory() {
        String key = "rank.ability-1";
        String old = yaml("presentation-legacy-messages-1.4.247.yml").getString("messages." + key);
        String current = yaml("messages.yml").getString("messages." + key);
        assertNotEquals(old, current);
        for (String text : List.of(old, "Личный перевод", "")) {
            var overrides = new HashMap<>(Map.of(key, text));
            Messages.migrateStockOverrides(Map.of(key, current), overrides, Map.of(key, old));
            assertEquals(text.equals(old) ? current : text, overrides.get(key));
            Messages.migrateStockOverrides(Map.of(key, current), overrides, Map.of(key, old));
            assertEquals(text.equals(old) ? current : text, overrides.get(key));
        }
    }

    @Test void bootstrapDoesNotGrantRetiredPermissionsEvenWhenDiscovered() {
        var config = PluginConfig.fromBukkitConfig(new YamlConfiguration());
        var bootstrap = new LuckPermsBootstrap(() -> config);
        assertTrue(bootstrap.scheme().stream().flatMap(group -> group.permissions().stream())
                .noneMatch(permission -> permission.startsWith("chrdkqueue.")));
        assertEquals(List.of("chrdkclaims.use"), LuckPermsBootstrap.splitDiscovered(
                Set.of("chrdkqueue.use", "chrdkclaims.use"), ".use"));
        assertEquals(List.of("chrdkclaims.admin"), LuckPermsBootstrap.splitDiscovered(
                Set.of("chrdkqueue.admin", "chrdkclaims.admin"), ".admin"));
    }
}
