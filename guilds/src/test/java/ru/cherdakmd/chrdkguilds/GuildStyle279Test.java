package ru.cherdakmd.chrdkguilds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class GuildStyle279Test {
    @TempDir Path folder;
    final UUID owner = UUID.randomUUID();
    final Logger log = Logger.getAnonymousLogger();
    @Test void allAnimationsAreDeterministicSafeAndActuallyChangeColorWithoutChangingText() {
        var mini = MiniMessage.miniMessage(); var plain = PlainTextComponentSerializer.plainText();
        for (String animation : GuildStyleDefs.ANIMATIONS) {
            Set<String> frames = new HashSet<>();
            for (long t = 0; t < 12000; t += 500) {
                String frame = GuildStyleAnimation.render("&c<red>Clan", "gold", "crown", animation, t);
                assertEquals(frame, GuildStyleAnimation.render("&c<red>Clan", "gold", "crown", animation, t));
                assertEquals("⚜ &c<red>Clan", plain.serialize(mini.deserialize(frame)));
                assertFalse(frame.contains("<click:")); frames.add(frame);
            }
            assertTrue(frames.size() >= 8, animation);
            assertEquals(GuildStyleAnimation.render("Tag", "gold", null, animation, 0),
                    GuildStyleAnimation.render("Tag", "gold", null, animation, 12000));
        }
        assertEquals(GuildStyleDefs.name("Tag", "gold", "star"), GuildStyleAnimation.render("Tag", "gold", "star", "forged", 0));
    }
    @Test void fourChoicesUseTheSameBindingSurviveRestartAndOldThreeChoiceFilesStillLoad() {
        var store = new GuildStyleStore(folder, log);
        store.select(owner,"Steve","clan-a","name","aurora",1000);
        store.select(owner,"Steve","clan-a","emblem","star",1001);
        store.select(owner,"Steve","clan-a","frame","gold",1002);
        assertTrue(new GuildStyleStore(folder, log).healthy());
        assertEquals(GuildStyleStore.Result.OK, store.select(owner,"Steve","clan-a","animation","shine",1003));
        var restored = new GuildStyleStore(folder, log);
        assertTrue(restored.healthy()); assertEquals(4, restored.of(owner).choices().size());
        assertEquals(GuildStyleStore.Result.COOLDOWN, restored.select(owner,"Steve","clan-b","animation","pulse",1004));
        assertEquals(GuildStyleStore.Result.OK, restored.select(owner,"Steve","clan-b","animation","pulse",86_401_000));
        assertEquals(4, restored.of(owner).choices().size()); assertTrue(restored.forGuild("clan-a").isEmpty());
        assertEquals("clan-style-pack", GuildStyleDefs.PRODUCTS.get("animation"));
    }
    @Test void bonusExpiresAndCannotTransferToNewLeaderOrDifferentUuid() {
        var store = new GuildStyleStore(folder, log);
        store.select(owner,"Steve","clan-a","animation","pulse",1000);
        var guild = new Guild("clan-a","Alpha","TAG",owner,1000);
        var rows = store.forGuild("clan-a");
        assertEquals("pulse",GuildStylePolicy.active(rows,guild,"animation",2000,row->3000,row->true));
        assertNull(GuildStylePolicy.active(rows,guild,"animation",3000,row->3000,row->true));
        assertNull(GuildStylePolicy.active(rows,guild,"animation",2000,row->3000,row->false));
        assertNull(GuildStylePolicy.active(rows,new Guild("clan-a","Alpha","TAG",UUID.randomUUID(),1000),"animation",2000,row->3000,row->true));
    }
    @Test void aReplacedApprovalIsNotThePreviewedOffer() {
        var first = new GuildStyleController.Pending("Steve", "name", "gold", 1000);
        assertNotEquals(first, new GuildStyleController.Pending("Steve", "name", "gold", 1001));
        assertNotEquals(first, new GuildStyleController.Pending("Steve", "animation", "pulse", 1000));
    }
    @Test void everyPresetAndGuiControlHasRussianMessages() throws Exception {
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(Path.of("src/main/resources/messages.yml").toFile());
        for (String kind : GuildStyleGui.KINDS) {
            assertTrue(yaml.contains("guilds.style-kind-" + kind));
            for (String preset : GuildStyleDefs.options(kind)) assertTrue(yaml.contains("guilds.style-preset-" + kind + "-" + preset), kind + ":" + preset);
        }
        assertEquals(14, Arrays.stream(GuildStyleGui.OPTIONS).distinct().count());
        assertTrue(Arrays.stream(GuildStyleGui.OPTIONS).allMatch(slot -> slot >= 19 && slot <= 34));
    }
}
