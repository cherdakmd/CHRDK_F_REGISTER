package ru.cherdakmd.chrdkteleports;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeStoreTest {

    private HomeStore newStore(Path dir) {
        return new HomeStore(dir, Logger.getAnonymousLogger());
    }

    @Test
    void nameValidation() {
        assertTrue(HomeStore.isValidName("base"));
        assertTrue(HomeStore.isValidName("дом_2"));
        assertTrue(HomeStore.isValidName("A1-я"));
        assertFalse(HomeStore.isValidName(""));
        assertFalse(HomeStore.isValidName(null));
        assertFalse(HomeStore.isValidName("белый пробел"));
        assertFalse(HomeStore.isValidName("слишкомдлинноеимя17ж"));
        assertEquals("дом", HomeStore.normalize("Дом"));
    }

    @Test
    void putGetRemove(@TempDir Path dir) {
        HomeStore store = newStore(dir);
        UUID id = UUID.randomUUID();
        HomeLocation home = new HomeLocation("Дом", "world", 10, 64, -20, 90f, 0f);
        assertFalse(store.put(id, home));
        assertTrue(store.put(id, new HomeLocation("дом", "world", 1, 2, 3, 0f, 0f)),
                "то же имя в другом регистре — замена");
        assertEquals(1, store.count(id));
        HomeLocation found = store.get(id, HomeStore.normalize("ДОМ"));
        assertNotNull(found);
        assertEquals(3, found.z());
        assertEquals(3, found.y());
        assertTrue(store.remove(id, home.name().toLowerCase(java.util.Locale.ROOT)));
        assertNull(store.get(id, HomeStore.normalize("Дом")));
    }

    @Test
    void listSortedByName(@TempDir Path dir) {
        HomeStore store = newStore(dir);
        UUID id = UUID.randomUUID();
        store.put(id, new HomeLocation("сепа", "world", 0, 0, 0, 0, 0));
        store.put(id, new HomeLocation("авак", "world", 0, 0, 0, 0, 0));
        store.put(id, new HomeLocation("база", "world", 0, 0, 0, 0, 0));
        List<HomeLocation> list = store.list(id);
        assertEquals(List.of("авак", "база", "сепа"),
                list.stream().map(home -> HomeStore.normalize(home.name())).toList());
    }

    @Test
    void persistsAndReloads(@TempDir Path dir) {
        HomeStore store = newStore(dir);
        UUID id = UUID.randomUUID();
        store.put(id, new HomeLocation("rade", "the_nether", -100.5, 70.25, 300.75, 45.5f, -10.5f));
        store.saveNow();
        HomeStore reloaded = newStore(dir);
        reloaded.load();
        HomeLocation home = reloaded.get(id, "rade");
        assertNotNull(home);
        assertEquals("rade", home.name());
        assertEquals("the_nether", home.world());
        assertEquals(-100.5, home.x());
        assertEquals(300.75, home.z());
        assertEquals(45.5f, home.yaw());
    }

    @Test
    void corruptedFileTolerated(@TempDir Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("teleports-data.json"), "{~[");
        HomeStore store = newStore(dir);
        store.load();
        assertEquals(0, store.count(UUID.randomUUID()));
    }
}
