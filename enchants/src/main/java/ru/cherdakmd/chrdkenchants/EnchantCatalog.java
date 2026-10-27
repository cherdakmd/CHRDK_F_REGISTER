package ru.cherdakmd.chrdkenchants;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Чистая модель справочника: фильтры совместимы, страницы никогда не теряют записи. */
final class EnchantCatalog {
    static final int PAGE_SIZE = 45;
    enum Sort { ID, NAME, RARITY }
    record Query(String search, ItemGroup group, EnchantDef.Rarity rarity, EnchantDef.Polarity polarity,
                 boolean hand, Sort sort) {
        Query {
            search = normalize(search);
            if (search.length() > 64) throw new IllegalArgumentException("search exceeds 64 characters");
            if (sort == null) sort = Sort.NAME;
        }
        static Query all() { return new Query("", null, null, null, false, Sort.NAME); }
        Query group(ItemGroup value) { return new Query(search, value, rarity, polarity, hand, sort); }
        Query rarity(EnchantDef.Rarity value) { return new Query(search, group, value, polarity, hand, sort); }
        Query polarity(EnchantDef.Polarity value) { return new Query(search, group, rarity, value, hand, sort); }
        Query hand(boolean value) { return new Query(search, group, rarity, polarity, value, sort); }
        Query sort(Sort value) { return new Query(search, group, rarity, polarity, hand, value); }
    }
    static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }
    static List<EnchantDef> select(Query query, Map<String, String> names, Set<String> applicable) {
        Comparator<EnchantDef> order = switch (query.sort) {
            case ID -> Comparator.comparing(EnchantDef::id);
            case NAME -> Comparator.comparing(def -> normalize(names.getOrDefault(def.id(), def.id())));
            case RARITY -> Comparator.comparing(EnchantDef::rarity).reversed();
        };
        return EnchantRegistry.all().stream()
                .filter(def -> query.group == null || def.supports().contains(query.group))
                .filter(def -> query.rarity == null || def.rarity() == query.rarity)
                .filter(def -> query.polarity == null || def.polarity() == query.polarity)
                .filter(def -> !query.hand || applicable.contains(def.id()))
                .filter(def -> query.search.isEmpty() || normalize(def.id()).contains(query.search)
                        || normalize(names.getOrDefault(def.id(), def.id())).contains(query.search))
                .sorted(order.thenComparing(EnchantDef::id)).toList();
    }
    static int pages(int size) { return Math.max(1, (Math.max(0, size) - 1) / PAGE_SIZE + 1); }
    static int page(int requested, int size) { return Math.max(0, Math.min(pages(size) - 1, requested)); }
    static List<EnchantDef> slice(List<EnchantDef> items, int requested) {
        int start = page(requested, items.size()) * PAGE_SIZE;
        return List.copyOf(items.subList(start, Math.min(items.size(), start + PAGE_SIZE)));
    }
    static <E extends Enum<E>> E next(E current, E[] values) {
        return current == null ? values[0] : current.ordinal() + 1 == values.length ? null : values[current.ordinal() + 1];
    }
    static List<String> conflicts(EnchantDef def) {
        if (def.conflictGroup() == null) return List.of();
        return EnchantRegistry.all().stream().filter(other -> def.conflictGroup().equals(other.conflictGroup())
                && !def.id().equals(other.id())).map(EnchantDef::id).sorted().toList();
    }
    private EnchantCatalog() { }
}
