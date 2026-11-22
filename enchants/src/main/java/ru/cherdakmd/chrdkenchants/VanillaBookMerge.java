package ru.cherdakmd.chrdkenchants;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fallback для смешанной книги, если native preview отсутствует, но это не
 * «Too Expensive». Перенос атомарный: любой конфликт отклоняет всю операцию,
 * часть vanilla-чар не исчезает вместе со сгоревшей книгой.
 */
final class VanillaBookMerge {

    record Result(ItemStack item, int nativeCost) {
    }

    private VanillaBookMerge() {
    }

    /** Проверяет, что preview не теряет ни одной чары или уровня ожидаемого набора. */
    static <K> boolean preserves(Map<K, Integer> expected, Map<K, Integer> actual) {
        return expected.entrySet().stream()
                .allMatch(entry -> actual.getOrDefault(entry.getKey(), 0) >= entry.getValue());
    }

    /**
     * @param levelCap потолок уровней ванильных чар ({@code rules.vanilla-level-cap}):
     *                 равные уровни сливаются в +1 вплоть до него, входные уровни не режутся
     */
    static Result merge(ItemStack source, EnchantmentStorageMeta book, int levelCap) {
        if (source == null || book == null || !book.hasStoredEnchants()) {
            return null;
        }
        // Книга-цель держит хранёные чары в EnchantmentStorageMeta: переносим их
        // туда и не требуем применимости к материалу (ванильное слияние книг).
        boolean bookTarget = source.getType() == Material.ENCHANTED_BOOK;
        ItemStack result = source.clone();
        ItemMeta meta = result.getItemMeta();
        if (meta == null) {
            return null;
        }
        EnchantmentStorageMeta storage = bookTarget && meta instanceof EnchantmentStorageMeta
                ? (EnchantmentStorageMeta) meta : null;
        Map<Enchantment, Integer> merged = new LinkedHashMap<>(storage != null
                ? storage.getStoredEnchants() : result.getEnchants());
        int nativeCost = 0;
        for (Map.Entry<Enchantment, Integer> entry : book.getStoredEnchants().entrySet()) {
            Enchantment enchantment = entry.getKey();
            int incoming = entry.getValue();
            if (enchantment == null || incoming < 1
                    || (!bookTarget && !enchantment.canEnchantItem(result))) {
                return null;
            }
            for (Enchantment existing : merged.keySet()) {
                if (!existing.equals(enchantment)
                        && (existing.conflictsWith(enchantment) || enchantment.conflictsWith(existing))) {
                    return null;
                }
            }
            int before = merged.getOrDefault(enchantment, 0);
            int after = AnvilPrice.merge(before, incoming,
                    Math.max(levelCap, enchantment.getMaxLevel()));
            if (after > before) {
                nativeCost += after;
            }
            merged.put(enchantment, after);
        }
        for (Enchantment enchantment : book.getStoredEnchants().keySet()) {
            // Bukkit возвращает false, если уровень не изменился; это не ошибка.
            int existing = storage != null ? storage.getStoredEnchantLevel(enchantment) : meta.getEnchantLevel(enchantment);
            if (existing == merged.get(enchantment)) continue;
            // true — уровни выше ванильного максимума допустимы: потолок уже
            // применён в merge, ограничение платформы только мешало бы.
            boolean added = storage != null
                    ? storage.addStoredEnchant(enchantment, merged.get(enchantment), true)
                    : meta.addEnchant(enchantment, merged.get(enchantment), true);
            if (!added) {
                return null;
            }
        }
        return result.setItemMeta(meta) ? new Result(result, nativeCost) : null;
    }
}
