package ru.cherdakmd.chrdkenchants;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Точило: кастомные чары спиливаются наравне с ванильными.
 *
 * <p>Ванильное точило само снимает ванильные чары и оставляет проклятия; наши чары
 * живут в PDC и без этой обработки переживали бы спиливание. Здесь наши обычные
 * чары снимаются, а наши проклятия остаются — ровно как ванильные проклятия.
 * Опыт за наши чары не начисляется: у них нет ванильной стоимости чар.</p>
 */
public final class GrindstoneListener implements Listener {
    private final EnchantsPlugin plugin;

    public GrindstoneListener(EnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Какие наши чары переживают точило: только проклятия — паритет с ванильными
     * проклятиями, которые точило не снимает.
     */
    static Map<String, Integer> survivors(Map<String, Integer> enchants) {
        Map<String, Integer> kept = new LinkedHashMap<>();
        if (enchants == null) return kept;
        for (var entry : enchants.entrySet()) {
            EnchantDef def = EnchantRegistry.byId(entry.getKey());
            if (def != null && def.curse()) kept.put(entry.getKey(), entry.getValue());
        }
        return kept;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareGrindstone(PrepareGrindstoneEvent event) {
        ItemStack left = event.getInventory().getItem(0);
        ItemStack right = event.getInventory().getItem(1);
        if (!plugin.items().marked(left) && !plugin.items().marked(right)) return;
        ItemStack result = event.getResult();
        if (result == null) {
            // Ванила не видит работы (в слотах только наши чары): результат спиливания строим сами.
            result = build(left, right);
            if (result == null) return;
        }
        Map<String, Integer> kept = survivors(plugin.items().read(result));
        if (kept.equals(plugin.items().read(result))) return;
        plugin.items().write(result, kept);
        event.setResult(result);
    }

    /** Результат спиливания без ванильной работы точила: одиночный вход, чары сняты. */
    private ItemStack build(ItemStack left, ItemStack right) {
        if (left == null || right != null) return null;
        Map<String, Integer> kept = survivors(plugin.items().read(left));
        boolean plainBook = left.getType() == Material.ENCHANTED_BOOK
                && !(left.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta storage
                     && storage.hasStoredEnchants());
        ItemStack result = plainBook ? new ItemStack(Material.BOOK) : left.clone();
        if (plainBook && left.hasItemMeta() && left.getItemMeta().hasDisplayName()) {
            var meta = result.getItemMeta();
            meta.displayName(left.getItemMeta().displayName());
            result.setItemMeta(meta);
        }
        plugin.items().write(result, kept);
        return result;
    }
}
