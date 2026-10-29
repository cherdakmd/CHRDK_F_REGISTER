package ru.cherdakmd.chrdkguilds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Хранилище гильдий (1.4.163): {@code guilds.json}, индекс участников,
 * приглашения, войны и чтение битого файла.
 *
 * <p>Индекс «игрок → гильдия» здесь важнее самого файла: если он разъедется с
 * составом, игрок сможет оказаться в двух гильдиях или ни в одной.</p>
 */
class GuildStoreTest {

    private static final Logger LOGGER = Logger.getLogger("GuildStoreTest");
    private static final UUID LEADER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID RIVAL = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    private static final UUID RIVAL_MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000e5");
    private static final UUID INVITEE = UUID.fromString("00000000-0000-0000-0000-0000000000f6");
    private static final long NOW = 1_792_000_000_000L;

    private static Guild guild(String id, String name, String tag) {
        Guild guild = new Guild(id, name, tag, LEADER, NOW);
        guild.addMember(MEMBER, GuildRank.MEMBER);
        return guild;
    }

    /**
     * Вторая гильдия со своим составом. Общий лидер у двух гильдий невозможен:
     * при загрузке такая запись потеряла бы участников и считалась бы брошенной.
     */
    private static Guild secondGuild() {
        Guild guild = new Guild("g2", "Второй", "TWO", RIVAL, NOW);
        guild.addMember(RIVAL_MEMBER, GuildRank.MEMBER);
        return guild;
    }

    @Test
    void emptyStoreStartsClean(@TempDir Path dir) {
        GuildStore store = new GuildStore(dir, LOGGER);
        store.load();
        assertEquals(dir.resolve("guilds.json"), store.file());
        assertEquals(0, store.count());
        assertTrue(store.guilds().isEmpty());
        assertNull(store.of(LEADER));
        assertNull(store.find("нет такой"));
        assertNull(store.guild(null));
        assertTrue(store.history().isEmpty());
        assertTrue(store.wars().isEmpty());
        assertFalse(store.isDirty());
    }

    @Test
    void addAndLookupWork(@TempDir Path dir) {
        GuildStore store = new GuildStore(dir, LOGGER);
        store.add(guild("g1", "Орден", "ORD"));
        store.add(guild("g1", "Дубль", "DBL"));
        assertEquals(1, store.count(), "повторный ид не добавляется");
        assertNotNull(store.of(LEADER));
        assertNotNull(store.of(MEMBER));
        assertEquals("g1", store.of(MEMBER).id());
        assertEquals("g1", store.byName("орден").id());
        assertEquals("g1", store.byTag("ord").id());
        assertEquals("g1", store.find("ORD").id());
        assertNull(store.byName(""));
        assertNull(store.byTag(""));
        assertTrue(store.isDirty());
    }

    @Test
    void removeClearsMemberIndex(@TempDir Path dir) {
        GuildStore store = new GuildStore(dir, LOGGER);
        Guild guild = guild("g1", "Орден", "ORD");
        store.add(guild);
        store.addInvite(new GuildInvite("g1", "Орден", OTHER, LEADER, NOW, NOW + 1_000));
        store.remove(guild);
        assertEquals(0, store.count());
        assertNull(store.of(LEADER));
        assertNull(store.of(MEMBER));
        assertNull(store.inviteOf(OTHER), "приглашения гильдии исчезают вместе с ней");
    }

    @Test
    void reindexFollowsMembership(@TempDir Path dir) {
        GuildStore store = new GuildStore(dir, LOGGER);
        Guild guild = guild("g1", "Орден", "ORD");
        store.add(guild);
        guild.removeMember(MEMBER);
        store.reindex(guild);
        assertNull(store.of(MEMBER));
        guild.addMember(OTHER, GuildRank.MEMBER);
        store.reindex(guild);
        assertEquals("g1", store.of(OTHER).id());
    }

    @Test
    void invitesAreUniquePerPlayerAndExpire(@TempDir Path dir) {
        GuildStore store = new GuildStore(dir, LOGGER);
        store.add(guild("g1", "Орден", "ORD"));
        store.add(secondGuild());
        store.addInvite(new GuildInvite("g1", "Орден", INVITEE, LEADER, NOW, NOW + 60_000));
        store.addInvite(new GuildInvite("g2", "Второй", INVITEE, RIVAL, NOW, NOW + 60_000));
        assertEquals("g2", store.inviteOf(INVITEE).guildId(), "новое приглашение заменяет старое");
        assertEquals(0, store.pruneInvites(NOW + 1_000));
        assertEquals(1, store.pruneInvites(NOW + 61_000));
        assertNull(store.inviteOf(INVITEE));
        assertFalse(store.removeInvite(INVITEE), "удалять нечего");
    }

    @Test
    void warLifecycle(@TempDir Path dir) {
        GuildStore store = new GuildStore(dir, LOGGER);
        store.add(guild("g1", "Орден", "ORD"));
        store.add(secondGuild());
        GuildWar war = new GuildWar("w1", "g1", "g2", 64, NOW);
        store.addWar(war);
        store.addWar(war);
        assertEquals(1, store.wars().size(), "повторная запись не дублируется");
        assertEquals(1, store.pendingWars());
        assertEquals(0, store.activeWars());
        assertNotNull(store.warOf("g1"));
        assertNotNull(store.warBetween("g1", "g2"));
        assertNull(store.warBetween("g1", "нет"));

        war.activate(NOW, 600);
        assertEquals(1, store.activeWars());
        assertEquals(0, store.pendingWars());
        war.finish();
        store.closeWar(war, "Орден");
        assertTrue(store.wars().isEmpty(), "закрытая война уходит из активных");
        assertEquals(1, store.history().size());
        assertEquals("Орден", store.history().get(0).winner());
        assertEquals(64L, store.history().get(0).wager());
        assertFalse(store.history().get(0).time().isBlank());
    }

    @Test
    void saveAndLoadRoundTrip(@TempDir Path dir) {
        GuildStore store = new GuildStore(dir, LOGGER);
        Guild guild = guild("g1", "Орден", "ORD");
        guild.deposit(120);
        guild.addGlory(50);
        guild.recordWarWin();
        store.add(guild);
        store.addInvite(new GuildInvite("g1", "Орден", INVITEE, LEADER, NOW, NOW + 60_000));
        GuildWar war = new GuildWar("w1", "g1", "g2", 64, NOW);
        war.activate(NOW, 600);
        war.addKill("g1", LEADER, RIVAL_MEMBER, false, NOW, 0L);
        store.add(secondGuild());
        store.addWar(war);
        store.closeWar(new GuildWar("old", "g1", "g2", 32, NOW), "Орден");
        store.saveNow();
        assertFalse(store.isDirty());

        GuildStore loaded = new GuildStore(dir, LOGGER);
        loaded.load();
        assertEquals(2, loaded.count());
        Guild back = loaded.of(MEMBER);
        assertNotNull(back);
        assertEquals("Орден", back.name());
        assertEquals("ORD", back.tag());
        assertEquals(LEADER, back.leader());
        assertEquals(120L, back.treasury());
        assertEquals(50, back.glory());
        assertEquals(1, back.warsWon());
        assertEquals(GuildRank.MEMBER, back.rankOf(MEMBER));
        assertNotNull(loaded.inviteOf(INVITEE));
        assertEquals(NOW + 60_000, loaded.inviteOf(INVITEE).expiresAt());
        assertEquals(1, loaded.wars().size());
        GuildWar loadedWar = loaded.wars().get(0);
        assertTrue(loadedWar.active());
        assertEquals(1, loadedWar.challengerPoints());
    }

    @Test
    void brokenFileStopsWithoutOverwrite(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("guilds.json"), "{[", StandardCharsets.UTF_8);
        GuildStore store = new GuildStore(dir, LOGGER);
        assertThrows(IllegalStateException.class,store::load);
        store.flush();
        assertEquals("{[",Files.readString(store.file()));
        assertEquals(0, store.count());
        assertFalse(store.isDirty());
    }

    @Test
    void ambiguousEntriesStopEntireImport(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("guilds.json"), """
                {
                  "version": 1,
                  "guilds": [
                    {"id": "g1", "name": "Орден", "tag": "ORD", "leader": "%s",
                     "created-at": %d,
                     "members": {"%s": "leader", "%s": "officer", "не-uuid": "member"},
                     "treasury": 100, "level": 3, "glory": 10, "wars-won": 1, "wars-lost": 2},
                    {"id": "g1", "name": "Дубль", "leader": "%s"},
                    {"name": "Безымянная"},
                    "строка"
                  ]
                }
                """.formatted(LEADER, NOW, LEADER, MEMBER, OTHER), StandardCharsets.UTF_8);
        GuildStore store = new GuildStore(dir, LOGGER);
        assertThrows(IllegalStateException.class,store::load);
        assertEquals(0,store.count());
    }

    @Test
    void completeLegacyGuildsKeepTheirOwners(@TempDir Path dir) throws Exception {
        GuildStore original = new GuildStore(dir,LOGGER);
        original.add(guild("g1","Первый","ONE")); original.add(secondGuild()); original.flush();
        GuildStore store = new GuildStore(dir,LOGGER); store.load();
        assertEquals(2,store.count());
        assertEquals("g1",store.of(LEADER).id());
        assertEquals("g2",store.of(RIVAL).id());
    }

    @Test
    void flushWritesVersion(@TempDir Path dir) throws Exception {
        GuildStore store = new GuildStore(dir, LOGGER);
        store.flush();
        String json = Files.readString(store.file(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"version\""));
        assertTrue(json.contains("\"guilds\""));
    }

    @Test
    void topSortsByGloryThenLevel(@TempDir Path dir) {
        GuildStore store = new GuildStore(dir, LOGGER);
        Guild first = new Guild("g1", "Первый", "A", LEADER, NOW);
        Guild second = new Guild("g2", "Второй", "B", OTHER, NOW);
        second.addGlory(100);
        store.add(first);
        store.add(second);
        assertEquals("g2", store.top(1).get(0).id());
        assertEquals(2, store.top(10).size());
        assertTrue(store.top(0).isEmpty());
    }
}
