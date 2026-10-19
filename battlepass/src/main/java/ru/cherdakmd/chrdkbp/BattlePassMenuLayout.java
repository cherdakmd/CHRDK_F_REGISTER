package ru.cherdakmd.chrdkbp;

/**
 * Раскладка меню батлпасса: первые пять рядов — задания, нижний ряд — кнопки.
 * Чистая логика без Bukkit, поэтому страницы и клики проверяются тестами.
 */
final class BattlePassMenuLayout {

    /** Размер инвентаря: 6 рядов по 9 слотов. */
    static final int SIZE = 54;
    /** Слотов под задания: пять верхних рядов. */
    static final int ITEMS_PER_PAGE = 45;
    static final int SLOT_PREV = 45;
    static final int SLOT_SUMMARY = 48;
    static final int SLOT_CLOSE = 49;
    static final int SLOT_NEXT = 53;

    private BattlePassMenuLayout() {
    }

    enum Action {
        NONE, MISSION, PREV, NEXT, CLOSE, SUMMARY
    }

    /** Сколько страниц нужно для такого числа заданий (минимум одна). */
    static int pageCount(int items) {
        if (items <= 0) {
            return 1;
        }
        return (items + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
    }

    /** Безопасная страница: не меньше нуля и не больше последней. */
    static int clampPage(int page, int items) {
        int last = pageCount(items) - 1;
        return Math.max(0, Math.min(last, page));
    }

    /** Индекс задания в общем списке для слота страницы; {@code -1} — слот пуст. */
    static int missionIndex(int slot, int page, int items) {
        if (slot < 0 || slot >= ITEMS_PER_PAGE) {
            return -1;
        }
        int index = clampPage(page, items) * ITEMS_PER_PAGE + slot;
        return index < items ? index : -1;
    }

    /** Что означает клик по слоту. */
    static Action actionFor(int slot, int page, int pages, int items) {
        if (missionIndex(slot, page, items) >= 0) {
            return Action.MISSION;
        }
        if (slot == SLOT_PREV) {
            return clampPage(page, items) > 0 ? Action.PREV : Action.NONE;
        }
        if (slot == SLOT_NEXT) {
            return clampPage(page, items) + 1 < Math.max(1, pages) ? Action.NEXT : Action.NONE;
        }
        if (slot == SLOT_CLOSE) {
            return Action.CLOSE;
        }
        if (slot == SLOT_SUMMARY) {
            return Action.SUMMARY;
        }
        return Action.NONE;
    }
}
