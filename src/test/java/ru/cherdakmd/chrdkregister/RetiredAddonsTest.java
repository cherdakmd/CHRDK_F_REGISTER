package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Удалённые модули не рекламируются; старые личные настройки читаются без сдвига. */
class RetiredAddonsTest {
    @TempDir Path directory;
    private LabelPreferences load(String json) throws Exception {
        Files.writeString(directory.resolve("labels.json"), json);
        return reload();
    }
    private LabelPreferences reload() {
        var preferences = new LabelPreferences(directory, Logger.getAnonymousLogger());
        preferences.load(); return preferences;
    }
    @Test void exactIdentifiersDoNotDisableVanillaMechanics() {
        for (String id : List.of("bosses", "Treasures", " treasures ")) assertTrue(RetiredAddons.source(id));
        for (String id : List.of("CHRDK_Bosses", " chrdk_treasures ")) assertTrue(RetiredAddons.plugin(id));
        assertFalse(RetiredAddons.source(null)); assertFalse(RetiredAddons.plugin("bosses"));
        for (String id : List.of("hunt", "bossbar", "fishing-treasure")) assertFalse(RetiredAddons.source(id));
        for (String command : List.of("boss menu", "/bosses", "bossy", "bosss", "bossesadmin stop", "bossadmin", "treasuresadmin reload", "/chrdk_treasures:treasures"))
            assertTrue(RetiredAddons.command(command), command);
        for (String command : List.of("minecraft:bossbar list", "hunt", "treasures_other", "", "shop buy boss"))
            assertFalse(RetiredAddons.command(command), command);
    }
    @Test void customizedMenuHidesRetiredEntriesEvenWhenOldPluginAndPermissionsArePresent() {
        for (String plugin : List.of("CHRDK_Bosses", "CHRDK_Treasures")) {
            var entry = new ServerMenuSettings.Entry(-1, Material.PAPER, "custom", "", "custom", plugin, "");
            assertFalse(entry.available(id -> true, permission -> true));
        }
        var commandOnly = new ServerMenuSettings.Entry(-1, Material.PAPER, "custom", "", "/chrdk_bosses:boss menu");
        assertFalse(commandOnly.available(id -> true, permission -> true));
        var vanilla = new ServerMenuSettings.Entry(-1, Material.PAPER, "vanilla", "", "minecraft:bossbar list");
        assertTrue(vanilla.available(id -> true, permission -> true));
    }
    @Test void customAndLegacyAnnouncementsCannotAdvertiseRemovedCommands() {
        for (Object raw : List.of("<gray>Клады: <white>/treasures</white></gray>", "<gray>/chrdk_bosses:boss</gray>",
                Map.of("text", "custom", "plugin", "CHRDK_Treasures"), Map.of("text", "custom", "plugin", "CHRDK_Bosses"))) {
            var message = AutoMessage.parse(raw); assertNotNull(message);
            assertFalse(message.eligible(id -> true, permission -> true, false, false));
        }
        assertTrue(AutoMessage.parse("Ванильные боссы и сокровища рыбалки остаются").eligible(id -> true, permission -> true, false, false));
        assertFalse(RetiredAddons.announcement("/treasures_other"));
    }
    @Test void wikiHasNeitherRetiredSuggestionsNorBrokenExplicitLinks() {
        var wiki = WikiSettings.of(true, WikiSettings.DEFAULT_URL, List.of("treasures", "hunt", "bosses", "faq"));
        assertEquals(List.of("hunt", "faq"), wiki.pages());
        assertEquals(wiki.pageUrl(""), wiki.pageUrl("TREASURES"));
        assertEquals(wiki.pageUrl(""), wiki.pageUrl("bosses"));
        assertFalse(WikiSettings.DEFAULT_PAGES.contains("treasures"));
    }
    @Test void hiddenLabelsAndExplicitPlacesSurviveReloadWithoutSubstitution() throws Exception {
        var preferences = load("""
                {"version":4,"players":{"steve":{"hidden":["treasures","classes"],
                "slots":["rank","treasures","guilds","classes"],"color":"aqua",
                "presets":{"p1":{"slots":["treasures","classes"],"hidden":["guilds"]}}}}}
                """);
        assertNull(LabelPreferences.Label.byId("treasures"));
        assertFalse(preferences.visible("Steve", LabelPreferences.Label.CLASSES));
        assertTrue(preferences.visible("Steve", LabelPreferences.Label.GUILDS));
        assertEquals(1, preferences.hiddenCount("Steve"));
        assertEquals(List.of("rank", "treasures", "guilds", "classes"), preferences.slots("Steve").subList(0,4));
        assertEquals(List.of("treasures", "classes"), preferences.presets("Steve").get("p1").slots().subList(0,2));
        preferences.set("Steve", LabelPreferences.Label.STREAMS, false); preferences.saveNow();
        var restored = reload();
        assertEquals(preferences.slots("Steve"), restored.slots("Steve"));
        assertEquals(preferences.presets("Steve"), restored.presets("Steve"));
        var styles = new LabelStyleService((id, player, base) -> "[" + id + "] " + base, restored,
                new LabelStyleService.RankLookup() {
                    public LabelStyleService.Rank view(String player) { return null; }
                    public List<String> registrationOrder() { return List.of(); }
                });
        assertTrue(styles.selection("Steve").shown().isEmpty(), "места ранга и снятого источника пустуют, гильдия не подставляется");
    }
    @Test void oldExplicitOrderAndPresetMigrateToPositionsIncludingTheEmptySource() throws Exception {
        var preferences = load("""
                {"version":3,"players":{"steve":{"order":["treasures","guilds","classes"],
                "presets":{"old":{"order":["rank","treasures","guilds"]}}}}}
                """);
        assertEquals(List.of("treasures","guilds","classes"), preferences.slots("Steve").subList(0,3));
        assertEquals(List.of("rank","treasures","guilds"), preferences.presets("Steve").get("old").slots().subList(0,3));
        preferences.set("Steve", LabelPreferences.Label.RANK, false); preferences.saveNow();
        assertEquals(preferences.slots("Steve"), reload().slots("Steve"));
    }
    @Test void retiredProvidersAreRejectedButOtherAddonsStillRegister() {
        var profiles = new ProfileRegistry(Logger.getAnonymousLogger());
        var tags = new NameTags(Logger.getAnonymousLogger());
        for (String id : List.of("bosses", "treasures", " TREASURES ")) {
            assertFalse(profiles.register(id, context -> null));
            tags.register(id, (player, base) -> "removed");
        }
        assertEquals(0, profiles.size()); assertEquals("Steve", tags.decorate("Steve", "Steve"));
        assertTrue(profiles.register("hunt", context -> null));
        tags.register("guilds", (player, base) -> "guild " + base);
        assertEquals("guild Steve", tags.decorate("Steve", "Steve"));
    }
}
