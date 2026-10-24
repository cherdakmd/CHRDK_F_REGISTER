package ru.cherdakmd.chrdkparty;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Хранилище групп (1.4.158): создание, поиск, запись и чтение, устойчивость к
 * битому файлу и к игроку, который оказался в двух группах сразу.
 */
class PartyStoreTest {

    private static final Logger LOGGER = Logger.getLogger("PartyStoreTest");

    @Test
    void emptyStoreStartsClean(@TempDir Path dir) {
        PartyStore store = new PartyStore(dir, LOGGER);
        store.load();
        assertEquals(0, store.size());
        assertEquals(0, store.memberCount());
        assertNull(store.of(UUID.randomUUID()));
        assertTrue(store.all().isEmpty());
        assertFalse(store.isDirty());
    }

    @Test
    void createFindsAndRemoves(@TempDir Path dir) {
        PartyStore store = new PartyStore(dir, LOGGER);
        UUID anya = UUID.randomUUID();
        UUID boris = UUID.randomUUID();
        Party party = store.create(anya, "Аня");
        assertNotNull(party);
        assertTrue(store.isDirty());
        assertEquals(party.id(), store.of(anya).id());
        assertNull(store.create(anya, "Аня"), "второй группы у игрока быть не может");

        assertTrue(party.add(boris, "Борис"));
        assertEquals(2, store.memberCount());
        assertEquals(2, store.of(boris).size());
        assertFalse(party.add(boris, "Борис"), "повторное добавление ничего не делает");

        assertTrue(store.remove(party.id()));
        assertNull(store.of(anya));
        assertFalse(store.remove(party.id()));
    }

    @Test
    void fileRoundTripKeepsLeaderAndMembers(@TempDir Path dir) {
        PartyStore store = new PartyStore(dir, LOGGER);
        UUID anya = UUID.randomUUID();
        UUID boris = UUID.randomUUID();
        Party party = store.create(anya, "Аня");
        party.add(boris, "Борис");
        store.markDirty();
        store.flush();
        assertTrue(Files.isRegularFile(store.file()), "parties.json должен появиться");

        PartyStore loaded = new PartyStore(dir, LOGGER);
        loaded.load();
        Party restored = loaded.of(boris);
        assertNotNull(restored, "группа должна пережить перезапуск");
        assertEquals(party.id(), restored.id());
        assertEquals(anya, restored.leader());
        assertEquals("Аня", restored.leaderName());
        assertEquals("Борис", restored.nameOf(boris));
        assertEquals(2, restored.size());
        assertEquals(anya, restored.memberIds().get(0), "лидер идёт первым");
    }

    @Test
    void brokenFileDoesNotCrash(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("parties.json"), "}{ это не json",
                StandardCharsets.UTF_8);
        PartyStore store = new PartyStore(dir, LOGGER);
        store.load();
        assertEquals(0, store.size());
    }

    @Test
    void memberOfTwoPartiesIsSkipped(@TempDir Path dir) throws Exception {
        UUID anya = UUID.randomUUID();
        UUID boris = UUID.randomUUID();
        String json = """
                {
                  "version": 1,
                  "parties": {
                    "11111111-1111-1111-1111-111111111111": {
                      "leader": "%s",
                      "members": { "%s": "Аня", "%s": "Борис" }
                    },
                    "22222222-2222-2222-2222-222222222222": {
                      "leader": "%s",
                      "members": { "%s": "Борис" }
                    }
                  }
                }
                """.formatted(anya, anya, boris, boris, boris);
        Files.writeString(dir.resolve("parties.json"), json, StandardCharsets.UTF_8);

        PartyStore store = new PartyStore(dir, LOGGER);
        store.load();
        assertEquals(1, store.size(), "вторая группа с тем же игроком — мусор");
        Party party = store.of(boris);
        assertNotNull(party);
        assertEquals(2, party.size());
    }

    @Test
    void groupWithoutLeaderFallsBackToFirstMember(@TempDir Path dir) throws Exception {
        UUID anya = UUID.randomUUID();
        String json = """
                {
                  "version": 1,
                  "parties": {
                    "33333333-3333-3333-3333-333333333333": {
                      "leader": "не-uuid",
                      "members": { "%s": "Аня" }
                    }
                  }
                }
                """.formatted(anya);
        Files.writeString(dir.resolve("parties.json"), json, StandardCharsets.UTF_8);

        PartyStore store = new PartyStore(dir, LOGGER);
        store.load();
        Party party = store.of(anya);
        assertNotNull(party, "группа без лидера должна выжить");
        assertEquals(anya, party.leader());
        assertEquals("Аня", party.leaderName());
    }

    @Test
    void snapshotIsStableJson(@TempDir Path dir) {
        PartyStore store = new PartyStore(dir, LOGGER);
        UUID anya = UUID.randomUUID();
        store.create(anya, "Аня");
        String json = store.snapshotJson();
        assertTrue(json.contains("\"version\""));
        assertTrue(json.contains(anya.toString()));
        assertFalse(store.isDirty(), "снимок снимает флаг изменений");
    }
}
