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

class PetStoreCareTest {

    private static final Logger LOGGER = Logger.getLogger("PetStoreCareTest");

    private static PetStore.PetData create(PetStore store, UUID id) {
        return store.create(id, UUID.randomUUID(), "Steve", PetSpecies.WOLF, "", 1000L);
    }

    @Test
    void careCountersAndCooldowns(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID pet = UUID.randomUUID();
        create(store, pet);

        store.setCare(pet, 55, 66, 1000L);
        assertEquals(55, store.get(pet).satiety());
        assertEquals(66, store.get(pet).mood());
        assertEquals(1000L, store.get(pet).careAt());

        store.addKill(pet);
        store.addKill(pet);
        store.addFish(pet);
        store.addDeath(pet);
        store.setBestLevel(pet, 7);
        store.setBestLevel(pet, 3);
        assertEquals(2, store.get(pet).kills());
        assertEquals(1, store.get(pet).fish());
        assertEquals(1, store.get(pet).deaths());
        assertEquals(7, store.get(pet).bestLevel());

        store.addTogetherSeconds(pet, 125L);
        assertEquals(125L, store.get(pet).togetherSeconds());
        assertEquals(2L, store.get(pet).minutesTogether());

        store.markAction(pet, "fed", 5_000L);
        store.markAction(pet, "petted", 6_000L);
        store.markAction(pet, "renamed", 7_000L);
        store.markAction(pet, "unknown", 9_000L);
        assertEquals(5_000L, store.get(pet).lastFed());
        assertEquals(6_000L, store.get(pet).lastPetted());
        assertEquals(7_000L, store.get(pet).lastRenamed());
        assertEquals(0L, store.get(pet).lastCalled());
    }

    @Test
    void tricksAchievementsAndCosmetics(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID pet = UUID.randomUUID();
        create(store, pet);

        assertTrue(store.learnTrick(pet, "sit"));
        assertFalse(store.learnTrick(pet, "sit"));
        assertTrue(store.get(pet).hasTrick("sit"));
        assertEquals(1, store.get(pet).tricks().size());

        assertTrue(store.unlockAchievement(pet, "first-level"));
        assertFalse(store.unlockAchievement(pet, "first-level"));
        assertTrue(store.get(pet).hasAchievement("first-level"));

        store.setCollar(pet, "red");
        store.setGlow(pet, true);
        store.setMode(pet, PetMode.STAY);
        assertEquals("red", store.get(pet).collar());
        assertTrue(store.get(pet).glow());
        assertEquals(PetMode.STAY, store.get(pet).mode());
    }

    @Test
    void diaryIsCapped(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID pet = UUID.randomUUID();
        create(store, pet);
        for (int index = 0; index < 5; index++) {
            store.addDiary(pet, 1000L + index, "kill", "", 3);
        }
        List<String> diary = store.get(pet).diary();
        assertEquals(3, diary.size());
        PetDiary.Entry last = PetDiary.decode(diary.get(2));
        assertNotNull(last);
        assertEquals("kill", last.code());
        assertEquals(1004L, last.at());
    }

    @Test
    void newFieldsSurviveRoundTrip(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID pet = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        store.create(pet, owner, "Steve", PetSpecies.CAT, "Мурка", 1000L);
        store.setCare(pet, 40, 70, 2_000L);
        store.setMode(pet, PetMode.STAY);
        store.addKill(pet);
        store.addFish(pet);
        store.addDeath(pet);
        store.setBestLevel(pet, 6);
        store.addTogetherSeconds(pet, 3_600L);
        store.learnTrick(pet, "spin");
        store.unlockAchievement(pet, "companion");
        store.setCollar(pet, "light_blue");
        store.setGlow(pet, true);
        store.markAction(pet, "called", 9_000L);
        store.addDiary(pet, 9_500L, "called", "", 8);
        store.flush();

        PetStore loaded = new PetStore(dir, LOGGER);
        loaded.load();
        PetStore.PetData data = loaded.get(pet);
        assertNotNull(data);
        assertEquals(40, data.satiety());
        assertEquals(70, data.mood());
        assertEquals(2_000L, data.careAt());
        assertEquals(PetMode.STAY, data.mode());
        assertEquals(1, data.kills());
        assertEquals(1, data.fish());
        assertEquals(1, data.deaths());
        assertEquals(6, data.bestLevel());
        assertEquals(3_600L, data.togetherSeconds());
        assertTrue(data.hasTrick("spin"));
        assertTrue(data.hasAchievement("companion"));
        assertEquals("light_blue", data.collar());
        assertTrue(data.glow());
        assertEquals(9_000L, data.lastCalled());
        assertEquals(1, data.diary().size());
    }

    @Test
    void legacyFileWithoutNewFieldsLoadsWithDefaults(@TempDir Path dir) throws Exception {
        UUID pet = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID owner = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        String json = """
                {"version":1,"pets":{"%s":{"owner":"%s","owner-name":"Steve",
                "species":"wolf","name":"","xp":40,"created":1000,"dead":false}}}
                """.formatted(pet, owner);
        Files.writeString(dir.resolve("pets.json"), json, StandardCharsets.UTF_8);

        PetStore store = new PetStore(dir, LOGGER);
        store.load();
        PetStore.PetData data = store.get(pet);
        assertNotNull(data);
        assertEquals(40L, data.xp());
        assertEquals(PetCare.Settings.DEFAULT.startSatiety(), data.satiety());
        assertEquals(PetCare.Settings.DEFAULT.moodNeutral(), data.mood());
        assertEquals(PetMode.FOLLOW, data.mode());
        assertEquals(0, data.kills());
        assertEquals(0, data.deaths());
        assertTrue(data.tricks().isEmpty());
        assertTrue(data.diary().isEmpty());
        assertEquals("", data.collar());
        assertFalse(data.glow());
    }

    @Test
    void topCanBeFilteredBySpecies(@TempDir Path dir) {
        PetStore store = new PetStore(dir, LOGGER);
        UUID owner = UUID.randomUUID();
        UUID wolf = UUID.randomUUID();
        UUID cat = UUID.randomUUID();
        store.create(wolf, owner, "Steve", PetSpecies.WOLF, "", 1L);
        store.create(cat, owner, "Steve", PetSpecies.CAT, "", 2L);
        store.addXp(cat, 100L);

        assertEquals(cat, store.top(5, PetSpecies.CAT).get(0).id());
        assertEquals(wolf, store.top(5, PetSpecies.WOLF).get(0).id());
        assertEquals(2, store.top(5, null).size());
        assertNull(store.create(null, owner, "Steve", PetSpecies.WOLF, "", 1L));
    }
}
