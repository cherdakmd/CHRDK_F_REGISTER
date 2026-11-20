package ru.cherdakmd.chrdkguilds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GuildRelationTest {
    @Test
    void readsMembershipAndOnlyActiveWar(@TempDir Path dir) {
        GuildStore store = new GuildStore(dir, Logger.getLogger("GuildRelationTest"));
        store.load();
        GuildService service = new GuildService(GuildConfig.defaults(), store);
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID enemy = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        Guild first = new Guild("first", "First", "FST", leader, 1L);
        first.addMember(member, GuildRank.MEMBER);
        Guild second = new Guild("second", "Second", "SND", enemy, 1L);
        store.add(first);
        store.add(second);

        assertEquals(GuildRelation.SAME_GUILD, service.relation(leader, member));
        assertEquals(GuildRelation.SAME_GUILD, service.relation(member, leader));
        assertEquals(GuildRelation.OTHER, service.relation(leader, outsider));
        assertEquals(GuildRelation.OTHER, service.relation(outsider, outsider));
        assertEquals(GuildRelation.UNKNOWN, service.relation(null, enemy));
        assertEquals(GuildRelation.UNKNOWN, service.relation(leader, null));

        GuildWar war = new GuildWar("war", first.id(), second.id(), 0L, 1L);
        store.addWar(war);
        assertEquals(GuildRelation.OTHER, service.relation(leader, enemy), "ожидаемая война ещё не активна");
        war.activate(10L, 60L);
        assertEquals(GuildRelation.ACTIVE_WAR, service.relation(leader, enemy));
        assertEquals(GuildRelation.ACTIVE_WAR, service.relation(enemy, member));
        assertEquals(GuildRelation.OTHER, service.relation(enemy, outsider));
        war.finish();
        assertEquals(GuildRelation.OTHER, service.relation(leader, enemy));
        store.remove(first);
        assertEquals(GuildRelation.OTHER, service.relation(leader, member));
    }

    @Test
    void corruptedStoreDoesNotBecomeAnEmptyGuildList(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("guilds.json"), "{invalid");
        GuildStore store = new GuildStore(dir, Logger.getLogger("GuildRelationTest"));
        assertThrows(IllegalStateException.class, store::load);
        GuildService service = new GuildService(GuildConfig.defaults(), store);
        assertEquals(GuildRelation.UNKNOWN, service.relation(UUID.randomUUID(), UUID.randomUUID()));
        assertEquals(GuildRelation.UNKNOWN, new GuildService(GuildConfig.defaults(), null)
                .relation(UUID.randomUUID(), UUID.randomUUID()));
    }
}
