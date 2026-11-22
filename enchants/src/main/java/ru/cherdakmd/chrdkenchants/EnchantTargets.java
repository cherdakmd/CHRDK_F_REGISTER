package ru.cherdakmd.chrdkenchants;

import java.util.List;
import java.util.Locale;

/** Единые ключи названий разрешённых групп для книги и каталога. */
final class EnchantTargets {
    private EnchantTargets() { }
    static List<String> labelKeys(EnchantDef def) {
        if (def.supports().size() == ItemGroup.values().length) return List.of("book-items-all");
        return def.supports().stream().sorted()
                .map(group -> "catalog-group-" + group.name().toLowerCase(Locale.ROOT)).toList();
    }
}
