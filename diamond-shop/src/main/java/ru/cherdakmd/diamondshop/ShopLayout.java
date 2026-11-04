package ru.cherdakmd.diamondshop;

/**
 * Раскладка витрины: вкладки разделов сверху, кнопки во втором ряду, товары ниже.
 *
 * <pre>
 * 0..9   — вкладки разделов (динамически, по числу непустых), до 10
 * 10..16 — свободный ряд-разделитель
 * 17     — закрыть
 * 18..44 — товары (27 на страницу, три ряда по девять)
 * 45     — назад, 49 — баланс, 53 — далее
 * </pre>
 *
 * <p>Два раздела книг чар увеличили число вкладок до десяти. Ванильный клиент
 * поддерживает максимум 54 слота, поэтому вместо недоступного окна на 72 слота
 * вкладки занимают два ряда, а страница товаров сокращена до 27 позиций. Кнопки
 * управления остались в нижнем ряду. Константы вынесены из {@link ShopGui},
 * чтобы прокликивание и пагинация проверялись юнит-тестами без Bukkit.</p>
 */
public final class ShopLayout {

    public static final int SIZE = 54;

    /** Слот кнопки «Закрыть». */
    public static final int SLOT_CLOSE = 17;
    /** Первый слот товаров. */
    public static final int FIRST_ITEM_SLOT = 18;
    /** Товаров на страницу (три ряда по девять). */
    public static final int ITEMS_PER_PAGE = 27;
    /** Последний слот товаров включительно. */
    public static final int LAST_ITEM_SLOT = FIRST_ITEM_SLOT + ITEMS_PER_PAGE - 1;
    public static final int SLOT_PREV = 45;
    /** RC16: кнопка раздела «Кузня» (/shop forge) в нижнем ряду. */
    public static final int SLOT_FORGE = 46;
    public static final int SLOT_BALANCE = 49;
    public static final int SLOT_NEXT = 53;
    /** Сколько вкладок влезает в два верхних ряда до кнопки закрытия. */
    public static final int MAX_TABS = 10;

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

    /** Слоты верхних рядов — вкладка раздела (0..9)? */
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
