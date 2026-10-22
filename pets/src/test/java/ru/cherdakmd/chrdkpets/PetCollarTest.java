package ru.cherdakmd.chrdkpets;

import org.bukkit.DyeColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetCollarTest {

    @Test
    void parsesAllSixteenColors() {
        assertEquals(16, PetCollar.ids().size());
        for (String id : PetCollar.ids()) {
            assertEquals(id, PetCollar.idOf(PetCollar.of(id)));
        }
        assertEquals(DyeColor.LIGHT_BLUE, PetCollar.of("light-blue"));
        assertEquals(DyeColor.LIGHT_BLUE, PetCollar.of(" LIGHT_BLUE "));
        assertNull(PetCollar.of("rainbow"));
        assertNull(PetCollar.of(null));
    }

    @Test
    void matchingFiltersByPrefix() {
        assertTrue(PetCollar.matching("li").contains("lime"));
        assertTrue(PetCollar.matching("li").contains("light_blue"));
        assertTrue(PetCollar.matching("").size() == 16);
        assertTrue(PetCollar.matching("zz").isEmpty());
    }

    @Test
    void defaultColorIsEmptyId() {
        assertEquals("", PetCollar.idOf(null));
    }
}
