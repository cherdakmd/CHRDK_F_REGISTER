package ru.cherdakmd.diamondshop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Операции над каталогом: фильтр по разделу, вкладки витрины и поиск по ключу
 * товара или русскому названию чары. Класс чистый — вся логика проверяется
 * юнит-тестами без Bukkit.
 */
public final class ShopCatalog {

    /** Сколько товаров показывать в результатах {@code /shop find}. */
    public static final int SEARCH_LIMIT = 10;

    private ShopCatalog() {
    }

    /** Товары раздела; {@link ShopCategory#ALL} возвращает весь каталог. */
    public static List<ShopConfig.ShopItem> filter(List<ShopConfig.ShopItem> items,
                                                   ShopCategory category) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        if (category == null || category == ShopCategory.ALL) {
            return items;
        }
        return items.stream()
                .filter(item -> item.category().equals(category.id()))
                .toList();
    }

    /**
     * Вкладки витрины: «Все» всегда первая, дальше — только непустые разделы
     * в порядке объявления. Пустые вкладки не отнимают слоты у товаров.
     */
    public static List<ShopCategory> tabs(List<ShopConfig.ShopItem> items) {
        List<ShopCategory> tabs = new ArrayList<>();
        tabs.add(ShopCategory.ALL);
        if (items == null) {
            return List.copyOf(tabs);
        }
        for (ShopCategory category : ShopCategory.values()) {
            if (category == ShopCategory.ALL) {
                continue;
            }
            boolean present = items.stream().anyMatch(item -> item.category().equals(category.id()));
            if (present) {
                tabs.add(category);
            }
        }
        return List.copyOf(tabs);
    }

    /**
     * Поиск по фрагменту названия материала без учёта регистра. Пустой запрос
     * даёт пустой результат, а не весь каталог — так `/shop find` не спамит.
     */
    public static List<ShopConfig.ShopItem> search(List<ShopConfig.ShopItem> items, String query) {
        if (items == null || items.isEmpty() || query == null) {
            return List.of();
        }
        String needle = normalize(query);
        if (needle.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .filter(item -> normalize(haystack(item)).contains(needle))
                .toList();
    }

    /**
     * Ключ товара плюс русское название кастомной чары: {@code /shop find жилодробитель}
     * находит {@code BOOK_CUSTOM_VEINMINER_3}, а не только латинский ключ.
     */
    private static String haystack(ru.cherdakmd.diamondshop.ShopConfig.ShopItem item) {
        var spec = item.spec();
        String name = ShopBookInfo.searchName(spec);
        return name == null ? item.material() : item.material() + " " + name;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }
}
