package ru.cherdakmd.chrdkenchants;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.inventory.ItemStack;

/** Prepares a copy for the server's BOOK -> ENCHANTED_BOOK operation, without touching the input. */
final class NewTableBookVisualListener implements Listener {
    private final BooleanSupplier enabled;
    NewTableBookVisualListener(EnchantsPlugin plugin) {
        this(() -> plugin.getConfig().getBoolean("textures.books", false)
                && plugin.getConfig().getBoolean("textures.new-table-books", false));
    }
    NewTableBookVisualListener(BooleanSupplier enabled) { this.enabled = Objects.requireNonNull(enabled); }

    static NamespacedKey model(Map<Enchantment, Integer> enchants) {
        if (enchants == null || enchants.isEmpty() || enchants.size() > BookVisualPolicy.VANILLA.size()) return null;
        Map<String, Integer> vanilla = new LinkedHashMap<>();
        for (var entry : enchants.entrySet()) {
            var key = entry.getKey() == null ? null : entry.getKey().getKey();
            if (key == null || entry.getValue() == null || vanilla.put(key.toString(), entry.getValue()) != null) return null;
        }
        return BookVisualPolicy.select(Map.of(), vanilla);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (!enabled.getAsBoolean()) return;
        ItemStack original = event.getItem();
        if (original == null || original.getType() != Material.BOOK || original.getAmount() != 1) return;
        var meta = original.getItemMeta();
        if (meta == null || meta.getItemModel() != null || meta.hasCustomModelData() || meta.hasDisplayName()
                || meta.hasLore() || meta.hasEnchants() || !meta.getPersistentDataContainer().getKeys().isEmpty()) return;
        NamespacedKey model = model(event.getEnchantsToAdd());
        if (model == null) return;
        ItemStack copy = original.clone();
        var copyMeta = copy.getItemMeta();
        if (copyMeta == null) return;
        copyMeta.setItemModel(model);
        if (copy.setItemMeta(copyMeta)) event.setItem(copy);
    }
}
