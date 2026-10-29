package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Снятый модуль не возвращается через старый конфиг, провайдер или личное оформление. */
class StreamsRemovalTest {
    @TempDir Path directory;

    private YamlConfiguration yaml(String resource) {
        try (var input = getClass().getResourceAsStream("/" + resource)) {
            assertNotNull(input, resource);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); }
    }
    private LabelPreferences preferences() {
        var result = new LabelPreferences(directory, Logger.getAnonymousLogger());
        result.load(); return result;
    }
    @Test void moduleIsAbsentFromTheDistributionAndLiveUi() {
        assertFalse(PluginUpdateService.MODULES.containsKey("CHRDK_Streams"));
        assertEquals(26, PluginUpdateService.MODULES.size());
        assertEquals(27, ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.API_VERSION);
        assertNull(LabelPreferences.Label.byId("streams"));
        assertNull(LabelsCommandHandler.singleOf("streams"));
        assertNull(LabelsCommandHandler.singleOf("стример"));
        assertEquals(6, LabelPreferences.Label.nameTags().size());
        assertEquals(7, LabelPreferences.Label.values().length);
        var current = yaml("config.yml");
        var items = current.getMapList("server-menu.items");
        assertEquals(49, items.size());
        assertEquals(5, items.stream().filter(item -> "social".equals(item.get("category"))).count());
        assertFalse(items.stream().anyMatch(item -> RetiredAddons.command(String.valueOf(item.get("command")))
                || RetiredAddons.plugin(String.valueOf(item.get("plugin")))));
        var announcements = ChatConfig.fromBukkitConfig(current).autoMessageEntries();
        assertEquals(current.getList("auto-messages.messages").size(), announcements.size());
        assertFalse(announcements.stream().anyMatch(entry -> RetiredAddons.announcement(entry.text())));
        assertEquals(1, announcements.stream().filter(entry -> entry.text().contains("/donate")).count());
    }
    @Test void legacyAliasesAndNamespacedCommandsCannotReappearInCustomMenus() {
        for (String alias : List.of("stream", "streams", "strimer", "/STREAM", "/chrdk_streams:streams")) {
            assertTrue(RetiredAddons.command(alias + " status"));
            var item = new ServerMenuSettings.Entry(-1, Material.PAPER, "Custom", "", alias + " status");
            assertFalse(item.available(plugin -> true, permission -> true));
        }
        var byPlugin = new ServerMenuSettings.Entry(-1, Material.PAPER, "Custom", "", "custom", "CHRDK_Streams", "");
        assertFalse(byPlugin.available(plugin -> true, permission -> true));
        assertFalse(RetiredAddons.command("stream_other status"));
    }
    @Test void legacyProgramAnnouncementsAreBlockedButPasswordSafetyHintsRemain() {
        for (Object raw : List.of("Канал: /stream link", "Откройте /custom:STRIMER status",
                "Стримерская программа: час эфира даёт час буста", "Поддержка Twitch и VK Play Live",
                Map.of("text", "Свой текст", "plugin", "CHRDK_Streams"))) {
            var message = AutoMessage.parse(raw); assertNotNull(message);
            assertFalse(message.eligible(plugin -> true, permission -> true, false, false), raw.toString());
        }
        assertTrue(AutoMessage.parse("Безопасный вход на стриме: /login").eligible(
                plugin -> true, permission -> true, false, false));
    }
    @Test void retiredProfileAndNameProvidersCannotRegisterAndWikiDoesNotSuggestThePage() {
        var profiles = new ProfileRegistry(Logger.getAnonymousLogger());
        var names = new NameTags(Logger.getAnonymousLogger());
        assertFalse(profiles.register(" STREAMS ", context -> null));
        names.register("streams", (player, base) -> "removed");
        assertEquals(0, profiles.size()); assertEquals("Steve", names.decorate("Steve", "Steve"));
        assertTrue(profiles.register("guilds", context -> null));
        names.register("guilds", (player, base) -> "[ГИ] " + base);
        assertEquals("[ГИ] Steve", names.decorate("Steve", "Steve"));
        var wiki = WikiSettings.of(true, WikiSettings.DEFAULT_URL, List.of("streams", "ranks", "menu"));
        assertEquals(List.of("ranks", "menu"), wiki.pages());
        assertEquals(wiki.pageUrl(""), wiki.pageUrl("streams"));
    }
    @Test void oldHiddenFlagsAndExplicitAssignmentsSurviveWithoutShiftingOrSubstitution() throws Exception {
        Files.writeString(directory.resolve("labels.json"), """
                {"version":4,"players":{"steve":{"hidden":["streams","quests","achievements","header"],
                "slots":["streams","rank","guilds","classes"],"color":"aqua",
                "presets":{"p1":{"slots":["streams","classes"],"hidden":["quests","header"]}}}}}
                """);
        var old = preferences();
        for (var label : List.of(LabelPreferences.Label.QUESTS, LabelPreferences.Label.ACHIEVEMENTS, LabelPreferences.Label.HEADER))
            assertFalse(old.visible("Steve", label));
        assertTrue(old.visible("Steve", LabelPreferences.Label.CLASSES));
        assertEquals(3, old.hiddenCount("Steve"));
        assertEquals(List.of("streams", "rank", "guilds", "classes"), old.slots("Steve").subList(0, 4));
        var called = new ArrayList<String>();
        var styles = new LabelStyleService((id, player, base) -> { called.add(id); return "[" + id + "] " + base; }, old,
                new LabelStyleService.RankLookup() {
                    public LabelStyleService.Rank view(String player) { return null; }
                    public List<String> registrationOrder() { return List.of(); }
                });
        assertTrue(styles.selection("Steve").shown().isEmpty(), "снятое место и отсутствующий ранг не замещаются гильдией");
        assertFalse(called.contains("streams"));
        old.set("Steve", LabelPreferences.Label.GUILDS, false); old.saveNow();
        var restored = preferences();
        assertEquals(old.slots("Steve"), restored.slots("Steve"));
        assertEquals(old.presets("Steve"), restored.presets("Steve"));
        assertEquals("aqua", restored.color("Steve"));
    }
    @Test void oldOrderedPresetsKeepTheRetiredPositionThroughSaveAndReload() throws Exception {
        Files.writeString(directory.resolve("labels.json"), """
                {"version":3,"players":{"steve":{"order":["streams","guilds","rank"],
                "presets":{"p1":{"order":["classes","streams","guilds"]}}}}}
                """);
        var old = preferences();
        assertEquals(List.of("streams", "guilds", "rank"), old.slots("Steve").subList(0, 3));
        assertEquals(List.of("classes", "streams", "guilds"), old.presets("Steve").get("p1").slots().subList(0, 3));
        old.set("Steve", LabelPreferences.Label.HEADER, false); old.saveNow();
        assertEquals(old.slots("Steve"), preferences().slots("Steve"));
        assertEquals(old.presets("Steve"), preferences().presets("Steve"));
    }
    @Test void previousStockMigratesOnceAndCustomizedListsOrScheduleAreNotRewritten() {
        var legacy = yaml("presentation-legacy-1.4.240.yml");
        var live = yaml("presentation-legacy-1.4.240.yml"); var current = yaml("config.yml");
        assertTrue(PresentationMigration.LEGACY_RESOURCES.contains("presentation-legacy-1.4.240.yml"));
        live.set("auto-messages.enabled", false); live.set("auto-messages.interval-seconds", 777);
        var changes = PresentationMigration.replacements(live, List.of(legacy), current);
        assertTrue(changes.containsAll(List.of("server-menu.items", "auto-messages.messages")));
        assertEquals(2, legacy.getMapList("server-menu.items").size() - current.getMapList("server-menu.items").size());
        for (String key : changes) live.set(key, current.get(key));
        assertFalse(live.getBoolean("auto-messages.enabled")); assertEquals(777, live.getInt("auto-messages.interval-seconds"));
        assertTrue(PresentationMigration.replacements(live, List.of(legacy), current).isEmpty());
        live.set("server-menu.items", List.of(Map.of("name", "Custom", "command", "custom")));
        live.set("auto-messages.messages", List.of("Своё сообщение"));
        changes = PresentationMigration.replacements(live, List.of(legacy), current);
        assertFalse(changes.contains("server-menu.items")); assertFalse(changes.contains("auto-messages.messages"));
    }
    @Test void customLabelsUsageDropsOnlyTheRetiredOptionsIncludingFirstAndLastPositions() {
        for (String options : List.of("streams|rank|quests", "rank|streams|quests", "rank|quests|streams",
                "streams|treasures|rank|quests")) {
            assertEquals("<yellow>/labels [rank|quests]</yellow>", GameplayRewardTexts.message(
                    "CHRDK_Core", "labels.usage", "<yellow>/labels [" + options + "]</yellow>"));
        }
        assertEquals("[]", GameplayRewardTexts.message("CHRDK_Core", "labels.usage", "[streams]"));
    }
}
