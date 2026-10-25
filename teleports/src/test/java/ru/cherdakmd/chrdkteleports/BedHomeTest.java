package ru.cherdakmd.chrdkteleports;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Кровать как точка дома: имя bed, сериализация и подсчёт слотов. */
class BedHomeTest {

    private HomeStore newStore(Path dir) {
        return new HomeStore(dir, Logger.getAnonymousLogger());
    }

    @Test
    void bedSpotSitsOnTopOfTheBed() {
        HomeLocation home = BedHome.at("world", 100, 64, -20, 90f);
        assertEquals(HomeStore.BED, home.name());
        assertEquals("world", home.world());
        assertEquals(100.5, home.x(), 1.0e-9, "центр блока по X");
        assertEquals(-19.5, home.z(), 1.0e-9, "центр блока по Z");
        assertEquals(65.0, home.y(), 1.0e-9, "рост в один блок над кроватью");
        assertEquals(90f, home.yaw(), 1.0e-9);
        assertEquals(0f, home.pitch(), 1.0e-9);
    }

    @Test
    void bedIsRecognisedByName() {
        assertTrue(BedHome.isBed(BedHome.at("world", 0, 64, 0, 0f)));
        assertTrue(BedHome.isBed(new HomeLocation("BED", "world", 0, 0, 0, 0, 0)));
        assertFalse(BedHome.isBed(new HomeLocation("дом", "world", 0, 0, 0, 0, 0)));
        assertFalse(BedHome.isBed(null));
    }

    @Test
    void bedNameIsReservedForTheServer() {
        assertTrue(HomeStore.isBedName("bed"));
        assertTrue(HomeStore.isBedName(" BED "));
        assertFalse(HomeStore.isBedName("bedroom"));
        assertFalse(HomeStore.isBedName("дом"));

        assertFalse(HomeStore.isPlayerNameAllowed("bed"));
        assertFalse(HomeStore.isPlayerNameAllowed("Bed"));
        assertTrue(HomeStore.isPlayerNameAllowed("дом"));
        assertFalse(HomeStore.isPlayerNameAllowed("bed room"));
        assertFalse(HomeStore.isPlayerNameAllowed(null));
    }

    @Test
    void bedDoesNotTakeAHomeSlot(@TempDir Path dir) {
        HomeStore store = newStore(dir);
        UUID id = UUID.randomUUID();
        store.put(id, BedHome.at("world", 0, 64, 0, 0f));
        assertEquals(1, store.count(id));
        assertEquals(0, store.countPlayerHomes(id), "кровать не занимает слот");

        store.put(id, new HomeLocation("база", "world", 100, 70, 100, 0f, 0f));
        assertEquals(2, store.count(id));
        assertEquals(1, store.countPlayerHomes(id));

        store.remove(id, HomeStore.BED);
        assertEquals(1, store.countPlayerHomes(id), "после удаления кровати слот «базы» остался");
        assertEquals(1, store.count(id));
        assertEquals(0, newStore(dir).count(id), "в памяти второго хранилища ничего нет");
    }

    @Test
    void bedSurvivesReload(@TempDir Path dir) {
        HomeStore store = newStore(dir);
        UUID id = UUID.randomUUID();
        store.put(id, BedHome.at("world", 12, 71, -34, 45f));
        store.saveNow();

        HomeStore reloaded = newStore(dir);
        reloaded.load();
        HomeLocation home = reloaded.get(id, HomeStore.BED);
        assertNotNull(home, "кровать переживает перезапуск даже после поломки блока");
        assertEquals(12.5, home.x(), 1.0e-9);
        assertEquals(72.0, home.y(), 1.0e-9);
        assertEquals(-33.5, home.z(), 1.0e-9);
    }

    @Test
    void missingBedPointIsJustNull(@TempDir Path dir) {
        HomeStore store = newStore(dir);
        assertNull(store.get(UUID.randomUUID(), HomeStore.BED));
    }
}
