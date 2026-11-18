package ru.cherdakmd.chrdkenchants;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Уровни ванильных чар на вещах и книгах (2.0.16).
 *
 * <p>Ванильная наковальня при совмещении зажимает уровни потолком чары
 * ({@code getMaxLevel}): предмет с «Защитой X» терял уровни прямо в слиянии.
 * Здесь ванильные чары поддерживают значения выше ванильных максимумов:
 * равные уровни сливаются в +1 вплоть до потолка
 * {@code rules.vanilla-level-cap}, входные уровни не режутся.</p>
 */
final class VanillaLevels {

    private VanillaLevels() {
    }

    /** Карта уровней ванильных чар: хранёные для книг, обычные для остального. */
    static Map<Enchantment, Integer> levels(ItemStack item) {
        if (item == null) return Map.of();
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storage) {
            return new LinkedHashMap<>(storage.getStoredEnchants());
        }
        return meta == null ? Map.of() : new LinkedHashMap<>(meta.getEnchants());
    }

    /** Записывает уровни; ограничения платформы сняты — потолок уже применён в merge. */
    static void applyLevels(ItemStack item, Map<Enchantment, Integer> levels) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        if (meta instanceof EnchantmentStorageMeta storage) {
            for (var entry : levels.entrySet()) {
                storage.addStoredEnchant(entry.getKey(), entry.getValue(), true);
            }
        } else {
            for (var entry : levels.entrySet()) {
                meta.addEnchant(entry.getKey(), entry.getValue(), true);
            }
        }
        item.setItemMeta(meta);
    }

    /**
     * Возвращает зажатые ванильным слиянием уровни результата к значениям
     * нашего слияния. Только вверх: чужие решения ванильной наковальни (ремонт,
     * имя, отброшенные конфликтные чары) не трогаются.
     */
    static void restore(ItemStack result, ItemStack left, ItemStack right, int levelCap) {
        if (result == null) return;
        Map<Enchantment, Integer> resultMap = levels(result);
        Map<Enchantment, Integer> merged = AnvilPrice.merge(levels(left), levels(right),
                enchantment -> Math.max(levelCap, enchantment.getMaxLevel()));
        Map<Enchantment, Integer> patched = new LinkedHashMap<>();
        for (var entry : merged.entrySet()) {
            int now = resultMap.getOrDefault(entry.getKey(), 0);
            if (now > 0 && now < entry.getValue()) {
                patched.put(entry.getKey(), entry.getValue());
            }
        }
        if (patched.isEmpty()) return;
        applyLevels(result, patched);
    }
}
