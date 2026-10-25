package ru.cherdakmd.diamondshop;

/**
 * Раскладка витрины: вкладки разделов сверху, товары в середине, управление снизу.
 *
 * <pre>
 * 0..7   — вкладки разделов (динамически, по числу непустых)
 * 8      — закрыть
 * 9..44  — товары (36 на страницу)
 * 45     — назад, 49 — баланс, 53 — далее
 * </pre>
 *
 * <p>Константы вынесены из {@link ShopGui}, чтобы прокликивание и пагинация
 * проверялись юнит-тестами без Bukkit.</p>
 */
public final class ShopLayout {

    public static final int SIZE = 54;

    /** Слот кнопки «Закрыть». */
    public static final int SLOT_CLOSE = 8;
    /** Первый слот товаров. */
    public static final int FIRST_ITEM_SLOT = 9;
    /** Товаров на страницу (четыре ряда по девять). */
    public static final int ITEMS_PER_PAGE = 36;
    /** Последний слот товаров включительно. */
    public static final int LAST_ITEM_SLOT = FIRST_ITEM_SLOT + ITEMS_PER_PAGE - 1;
    public static final int SLOT_PREV = 45;
    public static final int SLOT_BALANCE = 49;
    public static final int SLOT_NEXT = 53;
    /** Сколько вкладок влезает в верхний ряд до кнопки закрытия. */
    public static final int MAX_TABS = SLOT_CLOSE;

    private ShopLayout() {
    }

    /** Число страниц для размера каталога; пустой каталог — одна страница. */
    public static int pages(int itemCount) {
        if (itemCount <= 0) {
            return 1;
        }
        return (itemCount + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
    }

    /** Зажимает номер страницы в допустимые границы. */
    public static int clampPage(int page, int itemCount) {
        return Math.clamp(page, 0, pages(itemCount) - 1);
    }

    /** Слот верхнего ряда — вкладка раздела (0..7)? */
    public static boolean isTabSlot(int slot) {
        return slot >= 0 && slot < MAX_TABS;
    }

    /**
     * Индекс товара в отфильтрованном списке для слота витрины.
     * {@code -1} — слот не товарный.
     */
    public static int itemIndex(int page, int slot) {
        if (slot < FIRST_ITEM_SLOT || slot > LAST_ITEM_SLOT) {
            return -1;
        }
        return Math.max(0, page) * ITEMS_PER_PAGE + (slot - FIRST_ITEM_SLOT);
    }
}
