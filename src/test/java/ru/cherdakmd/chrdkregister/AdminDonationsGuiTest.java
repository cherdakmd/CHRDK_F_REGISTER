package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Админское GUI журнала донатов (1.4.49): пагинация и цикл фильтра.
 */
class AdminDonationsGuiTest {

    @Test
    void pageCountRoundsUpWithMinimumOne() {
        assertEquals(1, AdminDonationsGuiService.pageCount(0));
        assertEquals(1, AdminDonationsGuiService.pageCount(1));
        assertEquals(1, AdminDonationsGuiService.pageCount(AdminDonationsGuiService.PAGE_SIZE));
        assertEquals(2, AdminDonationsGuiService.pageCount(AdminDonationsGuiService.PAGE_SIZE + 1));
        assertEquals(3, AdminDonationsGuiService.pageCount(AdminDonationsGuiService.PAGE_SIZE * 2 + 1));
    }

    @Test
    void filterCyclesThroughAllStates() {
        AdminDonationsGuiService.Filter filter = AdminDonationsGuiService.Filter.ALL;
        assertEquals(AdminDonationsGuiService.Filter.PENDING, filter.next());
        assertEquals(AdminDonationsGuiService.Filter.GRANTED, filter.next().next());
        assertEquals(AdminDonationsGuiService.Filter.REJECTED, filter.next().next().next());
        assertEquals(AdminDonationsGuiService.Filter.ALL, filter.next().next().next().next());
    }

    @Test
    void waitSecondsRoundsUpWithZeroFloor() {
        assertEquals(0, AccessManager.waitSeconds(0));
        assertEquals(0, AccessManager.waitSeconds(-5));
        assertEquals(1, AccessManager.waitSeconds(1));
        assertEquals(1, AccessManager.waitSeconds(999));
        assertEquals(1, AccessManager.waitSeconds(1000));
        assertEquals(2, AccessManager.waitSeconds(1001));
        assertEquals(21, AccessManager.waitSeconds(20_500));
    }

    @Test
    void shortenFlattensAndTruncates() {
        assertEquals("", AdminDonationsGuiService.shorten(null, 10));
        assertEquals("abc", AdminDonationsGuiService.shorten("abc", 10));
        assertEquals("a b", AdminDonationsGuiService.shorten("a\nb\r", 10));
        String longText = "0123456789abcdefghij";
        String shortened = AdminDonationsGuiService.shorten(longText, 10);
        assertEquals(10, shortened.length());
        assertTrue(shortened.endsWith("…"));
        assertEquals(longText.substring(0, 9), shortened.substring(0, 9));
    }
}
