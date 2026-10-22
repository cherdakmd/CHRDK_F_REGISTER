package ru.cherdakmd.chrdkpets;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetNicknamesTest {

    private static final List<String> POOL = List.of("Серый", "Клык", "Ветер");

    @Test
    void nameIsDeterministicForUuid() {
        UUID id = UUID.fromString("f0e1d2c3-b4a5-4697-8879-6a5b4c3d2e1f");
        String first = PetNicknames.suggest(POOL, id);
        String second = PetNicknames.suggest(POOL, id);
        assertEquals(first, second);
        assertTrue(POOL.contains(first));
    }

    @Test
    void emptyPoolGivesEmptyName() {
        assertEquals("", PetNicknames.suggest(List.of(), UUID.randomUUID()));
        assertEquals("", PetNicknames.suggest(null, UUID.randomUUID()));
        assertEquals("", PetNicknames.suggest(List.of("  "), UUID.randomUUID()));
    }

    @Test
    void everyUuidStaysInsidePool() {
        for (int index = 0; index < 50; index++) {
            String name = PetNicknames.suggest(POOL, new UUID(index, index * 31L));
            assertTrue(POOL.contains(name));
        }
    }
}
