package ru.cherdakmd.chrdkpets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetStoreTest {

    private static final Logger LOGGER = Logger.getLogger("PetStoreTest");
    private static final long DAY = 86_400_000L;

    @Test
    void createIsIdempotentByPetUuid(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID pet = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        PetStore.PetData first = store.create(pet, owner, "Steve", PetSpecies.WOLF, "Барсик", 1000L);
        PetStore.PetData second = store.create(pet, owner, "Alex", PetSpecies.CAT, "Мурка", 2000L);
        assertNotNull(first);
        assertSame(first, second);
        assertEquals(1, store.size());
        assertEquals("Барсик", store.get(pet).name());
    }

    @Test
    void xpAndOwnerQueries(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID owner = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.create(first, owner, "Steve", PetSpecies.WOLF, "", 1L);
        store.create(second, owner, "Steve", PetSpecies.CAT, "Мурка", 2L);
        store.addXp(first, 106L);
        store.addXp(second, 40L);

        assertEquals(106L, store.get(first).xp());
        assertEquals(2, store.alivePetsOf(owner).size());
        assertEquals(2, store.petsOf(owner).size());
        assertEquals(first, store.top(1).get(0).id());
        assertEquals(3, PetCurve.levelFor(106L, PetCurve.Params.DEFAULT));
    }

    @Test
    void negativeXpIsIgnored(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID pet = UUID.randomUUID();
        store.create(pet, UUID.randomUUID(), "Steve", PetSpecies.WOLF, "", 1L);
        store.addXp(pet, 50L);
        store.addXp(pet, -100L);
        assertEquals(50L, store.get(pet).xp());
    }

    @Test
    void deathMovesPetOutOfAliveAndTop(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID pet = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        store.create(pet, owner, "Steve", PetSpecies.WOLF, "Барсик", 1L);
        store.addXp(pet, 500L);
        assertEquals(1, store.alivePetsOf(owner).size());
        store.markDead(pet, 10_000L);
        assertTrue(store.get(pet).dead());
        assertEquals(10_000L, store.get(pet).diedAt());
        assertTrue(store.alivePetsOf(owner).isEmpty());
        assertTrue(store.top(10).isEmpty());
        assertEquals(1, store.petsOf(owner).size());
    }

    @Test
    void ownerNameIsRemembered(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID owner = UUID.randomUUID();
        store.create(UUID.randomUUID(), owner, "", PetSpecies.CAT, "", 1L);
        assertEquals(1, store.touchOwnerName(owner, "Steve"));
        assertEquals("Steve", store.petsOf(owner).get(0).ownerName());
        assertEquals(0, store.touchOwnerName(owner, "Steve"));
    }

    @Test
    void snapshotRoundTripsThroughFile(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID pet = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        store.create(pet, owner, "Стив", PetSpecies.CAT, "Мурка", 123L);
        store.addXp(pet, 777L);
        store.markDead(UUID.randomUUID(), 1L);
        store.flush();
        assertTrue(Files.isRegularFile(dir.resolve("pets.json")));

        PetStore loaded = new PetStore(dir, LOGGER);
        loaded.load();
        PetStore.PetData data = loaded.get(pet);
        assertNotNull(data);
        assertEquals(owner, data.ownerId());
        assertEquals("Стив", data.ownerName());
        assertEquals(PetSpecies.CAT, data.species());
        assertEquals("Мурка", data.name());
        assertEquals(777L, data.xp());
        assertFalse(data.dead());
    }

    @Test
    void purgeRemovesOnlyOldMemorials(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        long now = 1_700_000_000_000L;
        UUID old = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        store.create(old, owner, "Steve", PetSpecies.WOLF, "", now - 40 * DAY);
        store.markDead(old, now - 40 * DAY);
        store.create(recent, owner, "Steve", PetSpecies.CAT, "", now - 10 * DAY);
        store.markDead(recent, now - 10 * DAY);

        assertEquals(1, store.purgeDead(30, now));
        assertNull(store.get(old));
        assertNotNull(store.get(recent));
        assertEquals(1, store.size());
    }

    @Test
    void purgeZeroDaysDropsAllMemorials(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID pet = UUID.randomUUID();
        store.create(pet, UUID.randomUUID(), "Steve", PetSpecies.WOLF, "", 1L);
        store.markDead(pet, 2L);
        assertEquals(1, store.purgeDead(0, System.currentTimeMillis()));
        assertEquals(0, store.size());
    }

    @Test
    void dirtyFlagFollowsMutations(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID pet = UUID.randomUUID();
        store.create(pet, UUID.randomUUID(), "Steve", PetSpecies.WOLF, "", 1L);
        assertTrue(store.isDirty());
        store.snapshotJson();
        assertFalse(store.isDirty());
        store.rename(pet, "Барсик");
        assertTrue(store.isDirty());
    }

    @Test
    void petsAreOrderedNewestFirst(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID owner = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.create(first, owner, "Steve", PetSpecies.WOLF, "", 1L);
        store.create(second, owner, "Steve", PetSpecies.CAT, "", 2L);
        List<PetStore.PetData> pets = store.petsOf(owner);
        assertEquals(second, pets.get(0).id());
        assertEquals(first, pets.get(1).id());
    }
}
