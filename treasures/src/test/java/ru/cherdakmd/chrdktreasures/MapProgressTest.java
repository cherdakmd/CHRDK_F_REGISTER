package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Полоса раскопок: границы, десять сегментов и разметка (1.4.180). */
class MapProgressTest {

    @Test
    void percentHasHardEdges() {
        assertEquals(0, MapProgress.percent(0, 20));
        assertEquals(50, MapProgress.percent(10, 20));
        assertEquals(100, MapProgress.percent(20, 20));
        assertEquals(100, MapProgress.percent(99, 20), "битый остаток не даёт больше ста");
        assertEquals(0, MapProgress.percent(5, 0), "без счётчика делить не на что");
        assertEquals(0, MapProgress.percent(-3, 20));
    }

    @Test
    void barFillsInTenths() {
        assertEquals("░░░░░░░░░░", MapProgress.bar(0));
        assertEquals("███░░░░░░░", MapProgress.bar(30));
        assertEquals("█████░░░░░", MapProgress.bar(50));
        assertEquals("██████████", MapProgress.bar(100));
        assertEquals("░░░░░░░░░░", MapProgress.bar(-40));
        assertEquals(10, MapProgress.bar(150).length());
    }

    @Test
    void coloredBarCarriesMarkup() {
        assertTrue(MapProgress.colored(50).contains("<gold>█████</gold>"));
        assertTrue(MapProgress.colored(50).contains("<dark_gray>░░░░░</dark_gray>"));
        assertEquals(MapProgress.colored(40), MapProgress.colored(8, 20), "полоса по счётчикам");
    }
}
