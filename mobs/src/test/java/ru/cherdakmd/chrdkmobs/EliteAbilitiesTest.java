package ru.cherdakmd.chrdkmobs;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Способности элит: случайный набор без повторов, иды для PDC, разбор строки.
 */
class EliteAbilitiesTest {

    @Test
    void pickReturnsDistinctAbilitiesWithinBounds() {
        Random random = new Random(42);
        for (int attempt = 0; attempt < 200; attempt++) {
            List<EliteAbilities> picked = EliteAbilities.pick(random, 2, 3);
            assertTrue(picked.size() >= 2 && picked.size() <= 3, String.valueOf(picked));
            assertEquals(picked.size(), EnumSet.copyOf(picked).size(), "без повторов");
        }
        assertEquals(1, EliteAbilities.pick(new Random(7), 1, 1).size());
        assertEquals(EliteAbilities.values().length,
                EliteAbilities.pick(new Random(7), 20, 30).size(), "пул зажимается к размеру");
    }

    @Test
    void idsRoundTripThroughPdcString() {
        String joined = EliteAbilities.join(List.of(EliteAbilities.VENOM, EliteAbilities.BLINK));
        assertEquals("venom,blink", joined);
        assertEquals(EnumSet.of(EliteAbilities.VENOM, EliteAbilities.BLINK),
                EliteAbilities.parse(joined));
        assertTrue(EliteAbilities.parse(null).isEmpty());
        assertTrue(EliteAbilities.parse("").isEmpty());
        assertTrue(EliteAbilities.parse("неизвестно,venom").contains(EliteAbilities.VENOM));
        assertNull(EliteAbilities.byId("неизвестно"));
        for (EliteAbilities ability : EliteAbilities.values()) {
            assertEquals(ability, EliteAbilities.byId(ability.id()));
            assertEquals(ability.id(), ability.id().toLowerCase(Locale.ROOT));
        }
    }

    @Test
    void poolCoversAllCombatMoments() {
        EnumSet<EliteAbilities.Kind> kinds = EnumSet.noneOf(EliteAbilities.Kind.class);
        for (EliteAbilities ability : EliteAbilities.values()) {
            kinds.add(ability.kind());
        }
        assertEquals(EnumSet.allOf(EliteAbilities.Kind.class), kinds);
        assertTrue(EliteAbilities.values().length >= 10, "пул способностей достаточен");
    }
}
