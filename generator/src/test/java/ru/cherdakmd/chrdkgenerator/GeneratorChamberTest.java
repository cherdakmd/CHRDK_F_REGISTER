package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Камера: скан ячеек, причины остановки и порядок кандидатов. */
class GeneratorChamberTest {

    private static final GeneratorChamber.Cell L = GeneratorChamber.Cell.LAVA_SOURCE;
    private static final GeneratorChamber.Cell W = GeneratorChamber.Cell.WATER_SOURCE;
    private static final GeneratorChamber.Cell E = GeneratorChamber.Cell.EMPTY;

    @Test void needsLavaAndWater() {
        GeneratorChamber.Result ok = GeneratorChamber.scan(List.of(L, E, W, E, E));
        assertEquals(GeneratorChamber.State.OK, ok.state());
        assertTrue(ok.working());
        assertEquals(0, ok.lavaIndex());
        assertEquals(2, ok.waterIndex());

        GeneratorChamber.Result noLava = GeneratorChamber.scan(List.of(E, E, W, E, E));
        assertEquals(GeneratorChamber.State.NO_LAVA, noLava.state());
        assertFalse(noLava.working());

        GeneratorChamber.Result noWater = GeneratorChamber.scan(List.of(L, E, E, E, E));
        assertEquals(GeneratorChamber.State.NO_WATER, noWater.state());

        GeneratorChamber.Result none = GeneratorChamber.scan(List.of(E, E, E, E, E));
        assertEquals(GeneratorChamber.State.NO_CELLS, none.state());
    }

    @Test void cellsAreIndependentOfOrder() {
        // Лава под ядром, вода сбоку — тоже работает (кандидаты независимы).
        GeneratorChamber.Result under = GeneratorChamber.scan(List.of(E, E, E, E, L));
        assertEquals(GeneratorChamber.State.NO_WATER, under.state());
        assertEquals(4, under.lavaIndex());

        GeneratorChamber.Result mixed = GeneratorChamber.scan(List.of(E, W, E, E, L));
        assertEquals(GeneratorChamber.State.OK, mixed.state());
        assertEquals(4, mixed.lavaIndex());
        assertEquals(1, mixed.waterIndex());
    }
}
