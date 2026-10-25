package ru.cherdakmd.chrdkpets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetStoreTest {

    private static final Logger LOGGER = Logger.getLogger("PetStoreTest");
    private static final long DAY = 86_400_000L;

    @Test
    void createAndGetPet(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        PetStore.PetData data = store.create(id, owner, "Steve", PetSpecies.WOLF, "Барсик", 1000L);
        assertNotNull(data);
        assertEquals(id, data.id());
        assertEquals(owner, data.ownerId());
        assertEquals("Steve", data.ownerName());
        assertEquals(PetSpecies.WOLF, data.species());
        assertEquals("Барсик", data.name());
        assertEquals(0L, data.xp());
        assertEquals(1000L, data.created());
        assertFalse(data.dead());
        assertTrue(store.has(id));
        assertEquals(1, store.size());
        assertEquals(1, store.aliveCount());
    }

    @Test
    void createIsIdempotentAndRejectsBrokenInput(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        PetStore.PetData first = store.create(id, owner, "Steve", PetSpecies.WOLF, "", 1L);
        store.addXp(id, 100L);
        PetStore.PetData second = store.create(id, owner, "Steve", PetSpecies.WOLF, "Другой", 2L);
        assertEquals(first, second);
        assertEquals(100L, second.xp());
        assertEquals("", second.name());
        assertNull(store.create(null, owner, "Steve", PetSpecies.WOLF, "", 1L));
        assertNull(store.create(id, null, "Steve", PetSpecies.WOLF, "", 1L));
        assertNull(store.create(UUID.randomUUID(), owner, "Steve", null, "", 1L));
    }

    @Test
    void xpChangesAreClamped(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID id = UUID.randomUUID();
        store.create(id, UUID.randomUUID(), "Steve", PetSpecies.CAT, "", 1L);
        store.addXp(id, 50L);
        store.addXp(id, -10L);
        assertEquals(50L, store.get(id).xp());
        store.setXp(id, -100L);
        assertEquals(0L, store.get(id).xp());
        store.markDead(id, 10L);
        store.addXp(id, 50L);
        assertEquals(0L, store.get(id).xp());
    }

    @Test
    void ownerNamesFollowThePlayer(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID owner = UUID.randomUUID();
        UUID pet = UUID.randomUUID();
        store.create(pet, owner, "Old", PetSpecies.WOLF, "", 1L);
        assertEquals(1, store.touchOwnerName(owner, "New"));
        assertEquals(0, store.touchOwnerName(owner, "New"));
        assertEquals(0, store.touchOwnerName(owner, "   "));
        assertEquals("New", store.get(pet).ownerName());
    }

    @Test
    void petsAreSeparatedByOwner(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        store.create(first, a, "A", PetSpecies.WOLF, "", 1L);
        store.create(second, b, "B", PetSpecies.CAT, "", 2L);
        assertEquals(List.of(first), store.petsOf(a).stream().map(PetStore.PetData::id).toList());
        assertEquals(List.of(second), store.petsOf(b).stream().map(PetStore.PetData::id).toList());
        assertTrue(store.petsOf(UUID.randomUUID()).isEmpty());
        assertTrue(store.petsOf(null).isEmpty());
    }

    @Test
    void topIgnoresDeadPets(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID owner = UUID.randomUUID();
        UUID low = UUID.randomUUID();
        UUID high = UUID.randomUUID();
        UUID dead = UUID.randomUUID();
        store.create(low, owner, "A", PetSpecies.WOLF, "", 1L);
        store.create(high, owner, "A", PetSpecies.WOLF, "", 2L);
        store.create(dead, owner, "A", PetSpecies.WOLF, "", 3L);
        store.addXp(low, 100L);
        store.addXp(high, 500L);
        store.addXp(dead, 900L);
        store.markDead(dead, 10L);
        List<PetStore.PetData> top = store.top(2);
        assertEquals(2, top.size());
        assertEquals(high, top.get(0).id());
        assertEquals(low, top.get(1).id());
        assertEquals(1, store.top(2).size() - 1);
    }

    @Test
    void deadPetsAreRememberedThenPurged(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID old = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        long now = 2_000_000_000_000L;
        store.create(old, owner, "A", PetSpecies.CAT, "", now - 100 * DAY);
        store.create(fresh, owner, "A", PetSpecies.CAT, "", now - 100 * DAY);
        store.markDead(old, now - 31 * DAY);
        store.markDead(fresh, now - DAY);
        assertEquals(1, store.purgeDead(30, now));
        assertNull(store.get(old));
        assertNotNull(store.get(fresh));
        assertEquals(1, store.purgeDead(0, now));
        assertEquals(0, store.size());
    }

    @Test
    void renameAndOwnerTransfer(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID pet = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.create(pet, first, "A", PetSpecies.WOLF, "", 1L);
        store.rename(pet, "Новый");
        assertEquals("Новый", store.get(pet).name());
        store.rename(pet, null);
        assertEquals("", store.get(pet).name());
        store.setOwner(pet, second, "B");
        assertEquals(second, store.get(pet).ownerId());
        assertEquals("B", store.get(pet).ownerName());
        store.setOwner(pet, null, "C");
        assertEquals(second, store.get(pet).ownerId());
    }

    @Test
    void jsonRoundTripKeepsEverything(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID pet = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        store.create(pet, owner, "Steve", PetSpecies.CAT, "Мурка", 1000L);
        store.addXp(pet, 777L);
        store.markDead(pet, 5000L);
        store.flush();

        PetStore loaded = new PetStore(dir, LOGGER);
        loaded.load();
        PetStore.PetData data = loaded.get(pet);
        assertNotNull(data);
        assertEquals(owner, data.ownerId());
        assertEquals("Steve", data.ownerName());
        assertEquals(PetSpecies.CAT, data.species());
        assertEquals("Мурка", data.name());
        assertEquals(777L, data.xp());
        assertEquals(1000L, data.created());
        assertTrue(data.dead());
        assertEquals(5000L, data.diedAt());
    }

    @Test
    void brokenJsonDoesNotCrashAndSkipsBadRows(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pets.json"), "{ not json", StandardCharsets.UTF_8);
        PetStore broken = new PetStore(dir, LOGGER);
        broken.load();
        assertEquals(0, broken.size());

        UUID good = UUID.randomUUID();
        String json = """
                {
                  "version": 1,
                  "pets": {
                    "not-a-uuid": {"owner": "x", "species": "wolf"},
                    "%s": {"owner": "%s", "species": "dragon"},
                    "%s": {"owner": "%s", "owner-name": "Steve", "species": "wolf",
                           "name": "Барсик", "xp": 40, "created": 1000, "dead": false}
                  }
                }
                """.formatted(good, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Files.writeString(dir.resolve("pets.json"), json, StandardCharsets.UTF_8);
        PetStore store = new PetStore(dir, LOGGER);
        store.load();
        assertEquals(1, store.size());
        assertEquals("Барсик", store.all().get(0).name());
    }
}
