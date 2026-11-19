package ru.cherdakmd.chrdkenchants;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Map;

/**
 * Точило: спиливание чар — полное снятие всех чар с предмета.
 *
 * <p>Точило — грубый инструмент: спиливает всё — светлые чары и проклятия,
 * ванильные и наши (наши живут в PDC и без этой обработки переживали бы
 * спиливание). Точечное снятие одного проклятия с сохранением остальных чар —
 * мастерская {@code /enchants cleanse}. Опыт за наши чары не начисляется:
 * у них нет ванильной стоимости чар.</p>
 *
 * <p>Книга после спиливания становится чистой ванильной книгой — без имени,
 * лора и PDC, поэтому стакается с обычными книгами.</p>
 */
public final class GrindstoneListener implements Listener {
    private final EnchantsPlugin plugin;

    public GrindstoneListener(EnchantsPlugin plugin) {
        this.plugin = plugin;
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
        event.setResult(strip(result));
    }

    /** Результат спиливания без ванильной работы точила: одиночный вход. */
    private ItemStack build(ItemStack left, ItemStack right) {
        return left == null || right != null ? null : left.clone();
    }

    /** Полное спиливание: наши и ванильные чары снимаются; книга становится ванильной. */
    private ItemStack strip(ItemStack result) {
        if (result.getType() == Material.BOOK || result.getType() == Material.ENCHANTED_BOOK) {
            return new ItemStack(Material.BOOK, Math.max(1, result.getAmount()));
        }
        plugin.items().write(result, Map.of());
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            for (var enchantment : new ArrayList<>(meta.getEnchants().keySet())) {
                meta.removeEnchant(enchantment);
            }
            result.setItemMeta(meta);
        }
        return result;
    }
}
