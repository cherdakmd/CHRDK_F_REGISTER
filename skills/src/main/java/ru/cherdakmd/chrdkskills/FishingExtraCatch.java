package ru.cherdakmd.chrdkskills;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Prevents a Skills perk from cloning an authenticated fish's one-use ID. */
final class FishingExtraCatch {
    private static final NamespacedKey KIND = key("kind"), ID = key("fish_id"), SPECIES = key("species"),
            SIZE = key("size"), LENGTH = key("length_cm"), PRICE = key("quoted_points"), MAC = key("signature");
    private FishingExtraCatch() { }
    private static NamespacedKey key(String name) { return new NamespacedKey("chrdk_fishing", name); }

    static boolean marked(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(KIND) || pdc.has(ID) || pdc.has(SPECIES) || pdc.has(SIZE)
                || pdc.has(LENGTH) || pdc.has(PRICE) || pdc.has(MAC);
    }
    /** Plain items keep vanilla clone behavior; tagged items need a trusted provider and a distinct ID. */
    static ItemStack extra(Player player, ItemStack original, FishingCatchBonus provider) {
        if (original == null) return null;
        if (!marked(original)) return original.clone();
        if (provider == null || original.getAmount() != 1) return null;
        var source = original.getItemMeta().getPersistentDataContainer();
        String sourceId = canonicalId(source);
        if (sourceId == null || !"fish-v1".equals(source.get(KIND, PersistentDataType.STRING))) return null;
        try {
            ItemStack bonus = provider.createExtra(player, original.clone());
            if (bonus == null || bonus == original || bonus.getType() != original.getType()
                    || bonus.getAmount() != 1 || !marked(bonus)) return null;
            var target = bonus.getItemMeta().getPersistentDataContainer();
            String targetId = canonicalId(target);
            if (targetId == null || targetId.equals(sourceId)
                    || !"fish-v1".equals(target.get(KIND, PersistentDataType.STRING))
                    || !same(source, target, SPECIES, PersistentDataType.STRING)
                    || !same(source, target, SIZE, PersistentDataType.STRING)
                    || !same(source, target, LENGTH, PersistentDataType.INTEGER)
                    || !same(source, target, PRICE, PersistentDataType.LONG)
                    || target.get(MAC, PersistentDataType.STRING) == null) return null;
            return bonus;
        } catch (RuntimeException | LinkageError error) {
            // Existing XP and the original catch remain untouched; never fall back to a raw clone.
            return null;
        }
    }
    private static String canonicalId(PersistentDataContainer pdc) {
        String raw = pdc.get(ID, PersistentDataType.STRING);
        if (raw == null) return null;
        try { return UUID.fromString(raw).toString().equals(raw) ? raw : null; }
        catch (IllegalArgumentException error) { return null; }
    }
    private static <T, Z> boolean same(PersistentDataContainer first, PersistentDataContainer second,
                                        NamespacedKey key, PersistentDataType<T, Z> type) {
        Z value = first.get(key, type);
        return value != null && Objects.equals(value, second.get(key, type));
    }
}
