package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.MilestoneAwards;
import ru.cherdakmd.chrdkregister.api.MilestoneStore;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Снятие мировых событий не затрагивает общие события Bukkit и долговые квитанции. */
class EventsRemovalTest {
    @TempDir Path directory;
    private YamlConfiguration yaml(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/" + name)) {
            assertNotNull(input, name);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
    }
    @Test void removedModuleIsAbsentFromDistributionAndStockMenu() throws Exception {
        assertFalse(Files.exists(Path.of("events")));
        assertFalse(PluginUpdateService.MODULES.containsKey("CHRDK_Events"));
        assertEquals(25, PluginUpdateService.MODULES.size());
        var stock = yaml("config.yml"); var items = stock.getMapList("server-menu.items");
        assertEquals(48, items.size());
        assertFalse(items.stream().anyMatch(item -> RetiredAddons.plugin(String.valueOf(item.get("plugin")))
                || RetiredAddons.command(String.valueOf(item.get("command")))));
        assertEquals(1, items.stream().filter(item -> "adventure".equals(item.get("category"))).count());
        assertTrue(items.stream().anyMatch(item -> "hunt".equals(item.get("command"))));
        assertEquals(2, items.stream().filter(item -> "donate".equals(item.get("command"))).count());
        var category = stock.getMapList("server-menu.categories").stream()
                .filter(item -> "adventure".equals(item.get("id"))).findFirst().orElseThrow();
        assertEquals("<gold><bold>Охота</bold></gold>", category.get("name"));
        assertEquals(stock.getList("auto-messages.messages").size(), ChatConfig.fromBukkitConfig(stock).autoMessageEntries().size());
    }
    @Test void oldCommandsAndPluginReferencesCannotReappearInCustomMenus() {
        for (String command : List.of("events", "/event info", "sobytiya next", "eventsadmin force",
                "/CHRDK_EVENTS:EVENTS list", "/other:eventsadmin stop")) {
            assertTrue(RetiredAddons.command(command), command);
            var entry = new ServerMenuSettings.Entry(-1, Material.PAPER, "Custom", "", command);
            assertFalse(entry.available(plugin -> true, permission -> true));
        }
        var entry = new ServerMenuSettings.Entry(-1, Material.PAPER, "Custom", "", "custom", "CHRDK_Events", "");
        assertFalse(entry.available(plugin -> true, permission -> true));
        for (String command : List.of("hunt", "event_other", "minecraft:time query daytime"))
            assertFalse(RetiredAddons.command(command), command);
    }
    @Test void onlyRemovedWorldEventAdvertisementsAreFiltered() {
        for (Object raw : List.of("Ближайший ивент — /events next", "Команда /sobytiya",
                "Мировые события: бонусы по расписанию", Map.of("text", "Custom", "plugin", "CHRDK_Events"))) {
            var message = AutoMessage.parse(raw); assertNotNull(message);
            assertFalse(message.eligible(plugin -> true, permission -> true, false, false));
        }
        for (String text : List.of("Важные события входа записываются в аудит", "Опыт за события профессии",
                "https://jd.papermc.io/paper/org/bukkit/event/player/PlayerJoinEvent.html",
                "src/main/java/ru/cherdakmd/chrdkregister/api/events/PassGrantedEvent.java")) {
            assertFalse(RetiredAddons.announcement(text), text);
            assertTrue(AutoMessage.parse(text).eligible(plugin -> true, permission -> true, false, false));
        }
    }
    @Test void retiredProvidersAndWikiPagesAreRejectedButOtherProfilesStayAvailable() {
        var profiles = new ProfileRegistry(Logger.getAnonymousLogger());
        var names = new NameTags(Logger.getAnonymousLogger());
        assertFalse(profiles.register(" EVENTS ", context -> null));
        names.register("events", (player, base) -> "removed");
        assertEquals(0, profiles.size()); assertEquals("Steve", names.decorate("Steve", "Steve"));
        assertTrue(profiles.register("hunt", context -> null));
        var wiki = WikiSettings.of(true, WikiSettings.DEFAULT_URL, List.of("events", "hunt", "profile"));
        assertEquals(List.of("hunt", "profile"), wiki.pages());
        assertEquals(wiki.pageUrl(""), wiki.pageUrl("events"));
    }
    @Test void stock241MigratesOnceWithoutChangingCustomListsOrSchedules() throws Exception {
        var legacy = yaml("presentation-legacy-1.4.241.yml");
        var live = yaml("presentation-legacy-1.4.241.yml"); var stock = yaml("config.yml");
        assertTrue(PresentationMigration.LEGACY_RESOURCES.contains("presentation-legacy-1.4.241.yml"));
        live.set("auto-messages.enabled", false); live.set("auto-messages.interval-seconds", 777);
        live.set("tablist.pinned-names", List.of("Admin"));
        var changes = PresentationMigration.replacements(live, List.of(legacy), stock);
        assertTrue(changes.containsAll(List.of("server-menu.items", "server-menu.categories")));
        assertTrue(changes.contains("auto-messages.messages"), "1.4.245 обновляет подсказку общего /bal у магазина; реклама снятого модуля не возвращается");
        assertEquals(2, legacy.getMapList("server-menu.items").size() - stock.getMapList("server-menu.items").size());
        for (String key : changes) live.set(key, stock.get(key));
        assertFalse(live.getBoolean("auto-messages.enabled")); assertEquals(777, live.getInt("auto-messages.interval-seconds"));
        assertEquals(List.of("Admin"), live.getStringList("tablist.pinned-names"));
        assertTrue(PresentationMigration.replacements(live, List.of(legacy), stock).isEmpty());
        live.set("server-menu.items", List.of(Map.of("command", "custom", "name", "Custom")));
        live.set("auto-messages.messages", List.of("Свой текст"));
        changes = PresentationMigration.replacements(live, List.of(legacy), stock);
        assertFalse(changes.contains("server-menu.items")); assertFalse(changes.contains("auto-messages.messages"));
    }
    @Test void coreEventContractsAndSharedMonetaryInfrastructureAreStillPresent() {
        assertEquals(28, ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.API_VERSION);
        assertTrue(org.bukkit.event.Event.class.isAssignableFrom(
                ru.cherdakmd.chrdkregister.api.events.PassGrantedEvent.class));
        assertTrue(org.bukkit.event.Event.class.isAssignableFrom(
                ru.cherdakmd.chrdkregister.api.events.PassRevokedEvent.class));
        assertNotNull(ru.cherdakmd.chrdkregister.api.MoneyLoot.class);
        assertNotNull(MilestoneAwards.class);
        assertFalse(RetiredAddons.source("guilds"));
    }
    @Test void historicalPendingMoneyAndBankReceiptsRemainReadableAndIdempotent() throws Exception {
        String source = "events-loot"; UUID owner = UUID.randomUUID();
        Path data = directory.resolve("old-plugin-data");
        var old = new MilestoneStore(data, source); old.initialize(Map.of());
        old.reserve(owner, "mob:" + UUID.randomUUID(), 9);
        var bank = new BankStore(directory.resolve("core"), Logger.getAnonymousLogger()); bank.load();
        var gateway = new MilestoneFinanceTest.Gateway(bank); gateway.available = false;
        assertFalse(MilestoneAwards.deliver(old, gateway, source, owner, 9));
        var restored = new MilestoneStore(data, source);
        assertEquals(9, restored.totals().get(owner));
        gateway.available = true;
        assertTrue(MilestoneAwards.deliver(restored, gateway, source, owner, 9));
        assertEquals(9, bank.balance(owner));
        assertTrue(bank.withdraw(owner, 9)); bank.flush();
        assertTrue(MilestoneAwards.deliver(new MilestoneStore(data, source), gateway, source, owner, 9));
        assertEquals(0, bank.balance(owner), "общая квитанция не выдаёт старую сумму снова");
    }
}
