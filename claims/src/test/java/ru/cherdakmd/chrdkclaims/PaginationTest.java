package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaginationTest {

    @Test
    void pageCountIsNeverZero() {
        assertEquals(1, Pagination.pageCount(0, 8), "пустой список — одна страница");
        assertEquals(1, Pagination.pageCount(8, 8));
        assertEquals(1, Pagination.pageCount(7, 8));
        assertEquals(2, Pagination.pageCount(9, 8));
        assertEquals(3, Pagination.pageCount(24, 8));
    }

    @Test
    void pageNumberIsClamped() {
        assertEquals(1, Pagination.clampPage(-5, 4));
        assertEquals(1, Pagination.clampPage(0, 4));
        assertEquals(2, Pagination.clampPage(2, 4));
        assertEquals(4, Pagination.clampPage(99, 4));
        assertEquals(1, Pagination.clampPage(3, 0), "ноль страниц трактуется как одна");
    }

    @Test
    void sliceReturnsRequestedPage() {
        List<Integer> items = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertEquals(List.of(1, 2, 3), Pagination.slice(items, 1, 3));
        assertEquals(List.of(4, 5, 6), Pagination.slice(items, 2, 3));
        assertEquals(List.of(7, 8, 9), Pagination.slice(items, 3, 3));
        assertEquals(List.of(7, 8, 9), Pagination.slice(items, 99, 3), "лишняя страница зажимается");
        assertEquals(List.of(1, 2, 3), Pagination.slice(items, -1, 3));
    }

    @Test
    void emptyListHasNoRows() {
        assertTrue(Pagination.slice(List.of(), 1, 8).isEmpty());
        assertTrue(Pagination.slice(null, 1, 8).isEmpty());
    }

    @Test
    void defaultPageSizeKeepsListsShort() {
        assertEquals(1, Pagination.pageCount(Pagination.PAGE_SIZE, Pagination.PAGE_SIZE));
        assertEquals(2, Pagination.pageCount(Pagination.PAGE_SIZE + 1, Pagination.PAGE_SIZE));
    }
}
