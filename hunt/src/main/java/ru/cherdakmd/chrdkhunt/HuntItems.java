package ru.cherdakmd.chrdkhunt;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Предметы охоты: трофеи и иконки меню (1.4.155).
 *
 * <p>Трофей — единственный предмет, который модуль выдаёт в мир. Он помечен в
 * {@link PersistentDataContainer}, поэтому его можно опознать даже после
 * переименования в наковальне: повторно выдать коллекционный предмет можно, а
 * спутать его с обычным черепом — нет.</p>
 */
public final class HuntItems {

    private final HuntPlugin plugin;
    private final NamespacedKey trophyKey;

    HuntItems(HuntPlugin plugin) {
        this.plugin = plugin;
        this.trophyKey = new NamespacedKey(plugin, "trophy");
    }

    /** Предмет-трофей с названием и описанием из конфига. */
    public ItemStack trophy(HuntTrophy trophy) {
        if (trophy == null) {
            return new ItemStack(Material.PAPER);
        }
        ItemStack item = new ItemStack(trophy.material());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(plugin.messages().renderTemplate(trophy.displayName()));
        List<Component> lore = new ArrayList<>();
        for (String line : trophy.lore()) {
            lore.add(plugin.messages().renderTemplate(line));
        }
        lore.add(plugin.messages().get("trophy-item-tag"));
        meta.lore(lore);
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(trophyKey, PersistentDataType.STRING, trophy.id());
        item.setItemMeta(meta);
        return item;
    }

    /** Ид трофея на предмете или пустая строка, если это обычная вещь. */
    public String trophyId(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return "";
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return "";
        }
        String id = meta.getPersistentDataContainer().get(trophyKey, PersistentDataType.STRING);
        return id == null ? "" : id;
    }

    /** Предмет с именем и описанием — для кнопок меню. */
    public ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material == null ? Material.PAPER : material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(name);
        if (lore != null && !lore.isEmpty()) {
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }
}
