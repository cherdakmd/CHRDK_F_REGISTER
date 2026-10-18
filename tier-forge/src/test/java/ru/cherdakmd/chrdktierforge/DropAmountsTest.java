package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Разбиение удвоенного дропа по стопкам (1.4.92). */
class DropAmountsTest {

    @Test
    void smallStacksStayWhole() {
        assertEquals(List.of(5), DropAmounts.split(5, 64));
        assertEquals(List.of(1), DropAmounts.split(1, 64));
    }

    @Test
    void overflowBecomesExtraStacks() {
        assertEquals(List.of(64, 64, 2), DropAmounts.split(130, 64));
        assertEquals(List.of(64, 6), DropAmounts.split(70, 64));
    }

    @Test
    void exactMultipleIsSingleOrEvenParts() {
        assertEquals(List.of(64), DropAmounts.split(64, 64));
        assertEquals(List.of(16, 16), DropAmounts.split(32, 16));
    }

    @Test
    void degenerateInputsAreSafe() {
        assertTrue(DropAmounts.split(0, 64).isEmpty());
        assertTrue(DropAmounts.split(-7, 64).isEmpty());
        assertEquals(List.of(1, 1, 1), DropAmounts.split(3, 0), "нулевой лимит не зацикливает разбиение");
    }
}
