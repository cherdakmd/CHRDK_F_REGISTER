package ru.cherdakmd.chrdkclaims;

import java.util.ArrayList;
import java.util.List;

/**
 * Постраничная выдача списков: одна чистая функция на все команды со «страницей»
 * ({@code /claim list}, {@code /claim forsale}). Номер страницы — с единицы,
 * слишком большие и отрицательные значения зажимаются.
 */
public final class Pagination {

    /** Сколько строк списка показывать на одной странице. */
    public static final int PAGE_SIZE = 8;

    /** Сколько страниц нужно для {@code total} строк (минимум одна, даже для пустого списка). */
    public static int pageCount(int total, int pageSize) {
        int size = Math.max(1, pageSize);
        if (total <= 0) {
            return 1;
        }
        return (total + size - 1) / size;
    }

    /** Номер страницы (с единицы), зажатый в диапазон {@code 1..pageCount}. */
    public static int clampPage(int requested, int pageCount) {
        int pages = Math.max(1, pageCount);
        return Math.min(pages, Math.max(1, requested));
    }

    /** Строки запрошенной страницы; пустой список, если строк нет. */
    public static <T> List<T> slice(List<T> items, int page, int pageSize) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        int size = Math.max(1, pageSize);
        int safePage = clampPage(page, pageCount(items.size(), size));
        int from = (safePage - 1) * size;
        int to = Math.min(items.size(), from + size);
        return new ArrayList<>(items.subList(from, to));
    }

    private Pagination() {
    }
}
