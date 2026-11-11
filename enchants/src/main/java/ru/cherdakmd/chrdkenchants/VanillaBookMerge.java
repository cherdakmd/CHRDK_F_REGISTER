package ru.cherdakmd.chrdkenchants;

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

    static Result merge(ItemStack source, EnchantmentStorageMeta book) {
        if (source == null || book == null || !book.hasStoredEnchants()) {
            return null;
        }
        ItemStack result = source.clone();
        Map<Enchantment, Integer> merged = new LinkedHashMap<>(result.getEnchants());
        int nativeCost = 0;
        for (Map.Entry<Enchantment, Integer> entry : book.getStoredEnchants().entrySet()) {
            Enchantment enchantment = entry.getKey();
            int incoming = entry.getValue();
            if (enchantment == null || incoming < 1 || !enchantment.canEnchantItem(result)) {
                return null;
            }
            for (Enchantment existing : merged.keySet()) {
                if (!existing.equals(enchantment)
                        && (existing.conflictsWith(enchantment) || enchantment.conflictsWith(existing))) {
                    return null;
                }
            }
            int before = merged.getOrDefault(enchantment, 0);
            int after = AnvilPrice.merge(before, incoming, enchantment.getMaxLevel());
            if (after > before) {
                nativeCost += after;
            }
            merged.put(enchantment, after);
        }
        ItemMeta meta = result.getItemMeta();
        if (meta == null) {
            return null;
        }
        for (Enchantment enchantment : book.getStoredEnchants().keySet()) {
            if (!meta.addEnchant(enchantment, merged.get(enchantment), false)) {
                return null;
            }
        }
        return result.setItemMeta(meta) ? new Result(result, nativeCost) : null;
    }
}
