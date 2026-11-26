package ru.cherdakmd.chrdkenchants;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Visual-only normalization. Never changes a book's PDC, name, lore or enchantments. */
final class BookVisuals {
    private static final String NAMESPACE = "chrdk_enchants";
    private static final Set<String> MIXED = Set.of("book/mixed/custom", "book/mixed/vanilla", "book/mixed/both");

    /** Only exact models shipped by us are ours, not arbitrary paths in our namespace. */
    static boolean owned(NamespacedKey model) {
        if (model == null || !NAMESPACE.equals(model.getNamespace())) return false;
        String path = model.getKey();
        if (MIXED.contains(path)) return true;
        if (path.startsWith("book/custom/")) {
            String id = path.substring("book/custom/".length());
            EnchantDef def = EnchantRegistry.byId(id);
            return def != null && def.id().equals(id);
        }
        return path.startsWith("book/vanilla/") && BookVisualPolicy.VANILLA.contains(path.substring("book/vanilla/".length()));
    }

    /** Returns true only if a component was changed. Invalid data falls back to default if ours. */
    static boolean apply(ItemMeta meta, NamespacedKey customKey, NamespacedKey sourceKey, boolean enabled) {
        if (!(meta instanceof EnchantmentStorageMeta storage) || customKey == null || sourceKey == null) return false;
        NamespacedKey current = meta.getItemModel();
        boolean ours = owned(current);
        if (current != null && !ours) return false;
        if (!enabled && !ours) return false;
        NamespacedKey selected = null;
        var data = meta.getPersistentDataContainer();
        if (!meta.hasCustomModelData() && !meta.hasEnchants()
                && data.getKeys().stream().allMatch(key -> key.equals(customKey) || key.equals(sourceKey))) {
            Map<String, Integer> custom = Map.of();
            if (data.has(customKey)) {
                custom = data.has(customKey, PersistentDataType.STRING)
                        ? EnchantBookValidation.read(data.get(customKey, PersistentDataType.STRING)) : null;
            }
            Map<String, Integer> vanilla = new LinkedHashMap<>();
            boolean valid = true;
            for (Map.Entry<Enchantment, Integer> entry : storage.getStoredEnchants().entrySet()) {
                if (entry.getKey() == null || entry.getKey().getKey() == null
                        || vanilla.put(entry.getKey().getKey().toString(), entry.getValue()) != null) { valid = false; break; }
            }
            if (valid) selected = BookVisualPolicy.select(custom, vanilla);
        }
        if (Objects.equals(current, selected)) return false;
        // Disabled means never adopt an unmodelled item. Already adopted books still
        // follow their contents (or lose our stale model) when touched by an operation.
        if (!ours && !enabled) return false;
        meta.setItemModel(selected);
        return true;
    }

    static boolean update(ItemStack item, NamespacedKey customKey, NamespacedKey sourceKey, boolean enabled) {
        if (item == null || item.getType() != Material.ENCHANTED_BOOK || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return apply(meta, customKey, sourceKey, enabled) && item.setItemMeta(meta);
    }

    /** Ignore only our appearance for the workshop's identical-book comparison. */
    static boolean equivalentForCombine(ItemStack first, ItemStack second) {
        if (first == null || second == null) return false;
        ItemStack a = withoutOwnedModel(first), b = withoutOwnedModel(second);
        return a.isSimilar(b);
    }

    private static ItemStack withoutOwnedModel(ItemStack item) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta != null && owned(meta.getItemModel())) {
            meta.setItemModel(null);
            copy.setItemMeta(meta);
        }
        return copy;
    }

    private BookVisuals() { }
}
