package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Полное снятие двух аддонов без команд-заглушек и возврата через старые конфиги. */
class HuntProfessionsRemovalTest {
    @Test void modulesSourcesDescriptorsAndPublicPagesAreGone() throws Exception {
        String settings = Files.readString(Path.of("settings.gradle"));
        assertFalse(settings.contains("include 'hunt'"));
        assertFalse(settings.contains("include 'professions'"));
        for (String path : List.of("hunt", "professions", "wiki/hunt.md", "wiki/professions.md",
                "src/main/java/ru/cherdakmd/chrdkregister/api/events/QualifiedHuntKillEvent.java",
                "src/main/java/ru/cherdakmd/chrdkregister/api/MobProgressionKeys.java",
                "enchants/src/main/java/ru/cherdakmd/chrdkenchants/MobBookListener.java")) {
            assertFalse(Files.exists(Path.of(path)), path);
        }
        String descriptor = Files.readString(Path.of("src/main/resources/plugin.yml"));
        assertFalse(descriptor.contains("aliases: [prof]"));
        assertFalse(Files.readString(Path.of("src/main/resources/config.yml")).contains("CHRDK_Hunt"));
        assertFalse(Files.readString(Path.of("src/main/resources/config.yml")).contains("CHRDK_Professions"));
    }

    @Test void oldPluginsCommandsAndProfileProvidersCannotReturnThroughSavedConfiguration() {
        for (String plugin : List.of("CHRDK_Hunt", "CHRDK_Professions")) {
            assertTrue(RetiredAddons.plugin(plugin));
            assertFalse(new ServerMenuSettings.Entry(-1, Material.PAPER, "old", "", "old", plugin, "")
                    .available(id -> true, permission -> true));
        }
        for (String command : List.of("hunt", "huntadmin", "ohota", "hunter", "prof", "profession",
                "professions", "professii", "profadmin", "professionsadmin", "profsadmin")) {
            assertTrue(RetiredAddons.command(command), command);
            assertFalse(RetiredAddons.announcement('/' + command + " menu"));
        }
        var profiles = new ProfileRegistry(java.util.logging.Logger.getAnonymousLogger());
        assertFalse(profiles.register("hunt", context -> null));
        assertFalse(profiles.register("professions", context -> null));
        var wiki = WikiSettings.of(true, WikiSettings.DEFAULT_URL, List.of("hunt", "professions", "faq"));
        assertEquals(List.of("faq"), wiki.pages());
        assertEquals(wiki.url(), wiki.pageUrl("hunt"));
        assertEquals(wiki.url(), wiki.pageUrl("professions"));
    }
}
