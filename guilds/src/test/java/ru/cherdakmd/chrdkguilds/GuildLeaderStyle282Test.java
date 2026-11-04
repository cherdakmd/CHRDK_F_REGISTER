package ru.cherdakmd.chrdkguilds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class GuildLeaderStyle282Test {
    @TempDir Path folder;
    private final UUID leader = UUID.randomUUID(), member = UUID.randomUUID();
    private final Logger logger = Logger.getAnonymousLogger();

    @Test void onlyCurrentLeaderOwnPaidChoicesCanRenderForEveryKind() {
        var store = new GuildStyleStore(folder, logger);
        var guild = new Guild("clan", "Alpha", "TAG", leader, 1000);
        guild.addMember(member, GuildRank.OFFICER);
        Map<String,String> presets = Map.of("name", "gold", "emblem", "crown", "frame", "gold", "animation", "shine");
        presets.forEach((kind,preset) -> {
            store.select(leader, "Steve", "clan", kind, preset, 1000);
            store.select(member, "Alex", "clan", kind, preset, 2000);
        });
        var restored = new GuildStyleStore(folder, logger);
        for (var entry : presets.entrySet()) {
            var rows = restored.forGuild("clan");
            assertEquals(entry.getValue(), GuildStylePolicy.active(rows, guild, entry.getKey(), 2500, row -> 5000, row -> true));
            // Покупка участника не заменяет истёкшую покупку главы.
            assertNull(GuildStylePolicy.active(rows, guild, entry.getKey(), 2500,
                    row -> row.owner().equals(leader) ? 2500 : 5000, row -> true));
            assertNull(GuildStylePolicy.active(rows, guild, entry.getKey(), 2500, row -> 5000, row -> false));
        }
        assertTrue(guild.transferLeader(member));
        for (String kind : presets.keySet()) {
            assertNull(GuildStylePolicy.active(restored.forGuild("clan"), guild, kind, 2500,
                    row -> row.owner().equals(member) ? 2500 : 5000, row -> true));
        }
        // Смена руководства не удаляет/не переоформляет личный выбор и покупки.
        assertEquals(4, restored.of(leader).choices().size());
        assertEquals(4, restored.of(member).choices().size());
    }

    @Test void removingFormerLeaderChoiceCannotEraseNewLeaderChoiceOrRestoreMemberFallback() {
        var store = new GuildStyleStore(folder, logger);
        var guild = new Guild("clan", "Alpha", "TAG", leader, 1000);
        guild.addMember(member, GuildRank.MEMBER);
        store.select(leader, "Steve", "clan", "animation", "shine", 1000);
        store.select(member, "Alex", "clan", "animation", "pulse", 2000);
        assertTrue(guild.transferLeader(member));
        assertTrue(store.remove(leader, "animation"));
        assertEquals("pulse", GuildStylePolicy.active(store.forGuild("clan"), guild, "animation", 2500, row -> 5000, row -> true));
        assertTrue(store.remove(member, "animation"));
        assertNull(GuildStylePolicy.active(store.forGuild("clan"), guild, "animation", 2500, row -> 5000, row -> true));
    }

    @Test void oldFutureMovedAtCannotCreateAnyWait() {
        var store = new GuildStyleStore(folder, logger);
        assertEquals(GuildStyleStore.Result.OK, store.select(leader, "Steve", "old", "animation", "shine", 999999));
        var restored = new GuildStyleStore(folder, logger);
        assertEquals(GuildStyleStore.Result.OK, restored.select(leader, "Steve", "new", "name", "gold", 1));
        assertTrue(restored.forGuild("old").isEmpty());
        assertEquals(2, restored.of(leader).choices().size());
    }

    @Test void controllerGuardsEveryMutationAndDeferredGuiRechecksCurrentRole() throws Exception {
        String controller = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkguilds/GuildStyleController.java"));
        int guard = controller.indexOf("if (!GuildStylePolicy.canManage(guild, actor.getUniqueId()))");
        assertTrue(guard > 0);
        assertTrue(guard < controller.indexOf("action.equals(\"off\")"));
        assertTrue(guard < controller.indexOf("store.select("));
        assertFalse(controller.contains("offers.put"));
        assertFalse(controller.contains("store.clear("));
        String gui = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkguilds/GuildStyleGui.java"));
        int deferred = gui.indexOf("Bukkit.getScheduler().runTask");
        int repeated = gui.indexOf("if (!GuildStylePolicy.canManage(current, player.getUniqueId()))", deferred);
        assertTrue(repeated > deferred);
        assertTrue(repeated < gui.indexOf("plugin.styles().command(player", repeated));
        assertTrue(gui.contains("if (manager) h.actions.put"));
    }
}
