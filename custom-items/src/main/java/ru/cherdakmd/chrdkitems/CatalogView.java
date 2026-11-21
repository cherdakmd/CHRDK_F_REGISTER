package ru.cherdakmd.chrdkitems;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only catalog queries; invalid input never mutates data or inventory. */
final class CatalogView {
    static final int PAGE_SIZE = 8;
    record Query(ItemCatalog.Category category, int page) { }
    record Page(List<ItemCatalog.Definition> items, int number, int pages, int total) { }

    static Query parse(String[] args) {
        if (args.length > 2) throw new IllegalArgumentException();
        ItemCatalog.Category category = null;
        if (args.length > 0 && !args[0].equalsIgnoreCase("all")) {
            try { category = ItemCatalog.Category.valueOf(args[0].toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException(); }
        }
        int page = 1;
        if (args.length == 2) {
            try {
                if (!args[1].matches("[0-9]{1,7}")) throw new IllegalArgumentException();
                page = Integer.parseInt(args[1]);
                if (page < 1 || page > 1_000_000) throw new IllegalArgumentException();
            } catch (NumberFormatException e) { throw new IllegalArgumentException(); }
        }
        return new Query(category, page);
    }

    static Page search(Map<String, ItemCatalog.Definition> catalog, Query query) {
        var matches = catalog.values().stream()
                .filter(item -> query.category() == null || item.category() == query.category())
                .sorted(Comparator.comparing(ItemCatalog.Definition::id)).toList();
        int pages = Math.max(1, (int) ((matches.size() + (long) PAGE_SIZE - 1) / PAGE_SIZE));
        if (query.page() < 1 || query.page() > pages) return new Page(List.of(), query.page(), pages, matches.size());
        int from = (query.page() - 1) * PAGE_SIZE;
        return new Page(List.copyOf(matches.subList(from, Math.min(matches.size(), from + PAGE_SIZE))),
                query.page(), pages, matches.size());
    }

    static String categoryName(ItemCatalog.Category category) {
        return switch (category) {
            case ARMOR -> "Броня";
            case WEAPON -> "Оружие";
            case TOOL -> "Инструменты";
            case CONSUMABLE -> "Расходники";
        };
    }
}
