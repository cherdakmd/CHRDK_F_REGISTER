package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Virtual cooking controls only; input fish and delivered dishes are never modified here. */
final class FishCookingVisuals {
    enum Icon {
        HELP("help"), CONFIRM("confirm"), BACK("back"), CLOSE("close");
        final String id;
        Icon(String id) { this.id = id; }
        NamespacedKey model() { return new NamespacedKey("chrdk_fishing", "menu/cooking/" + id); }
    }
    static NamespacedKey recipe(FishCookingPlanner.Recipe recipe, boolean selected) {
        if (recipe == null) throw new IllegalArgumentException("recipe");
        return new NamespacedKey("chrdk_fishing", "menu/cooking/" + recipe.name().toLowerCase(Locale.ROOT)
                + (selected ? "_selected" : ""));
    }
    private static void apply(ItemMeta meta, NamespacedKey model, boolean enabled) {
        if (enabled && meta != null && model != null && meta.getItemModel() == null && !meta.hasCustomModelData())
            meta.setItemModel(model);
    }
    static void fish(ItemMeta meta, String species, boolean enabled) {
        if (enabled) apply(meta, FishingVisuals.fish(species), true);
    }
    static void recipe(ItemMeta meta, FishCookingPlanner.Recipe recipe, boolean selected, boolean enabled) {
        if (enabled) apply(meta, recipe(recipe, selected), true);
    }
    static void button(ItemMeta meta, Icon icon, boolean enabled) {
        if (enabled && icon != null) apply(meta, icon.model(), true);
    }
    static ItemStack buttonIcon(Icon icon, boolean enabled, Material material, String name, String... lore) {
        var item = FishMainGui.icon(material, name, lore);
        if (enabled) { var meta = item.getItemMeta(); button(meta, icon, true); if (meta != null) item.setItemMeta(meta); }
        return item;
    }
    static ItemStack recipeIcon(FishCookingPlanner.Recipe recipe, boolean selected, boolean enabled, String... lore) {
        var item = FishMainGui.icon(recipe.material(), recipe.title(), lore);
        if (enabled) { var meta = item.getItemMeta(); recipe(meta, recipe, selected, true); if (meta != null) item.setItemMeta(meta); }
        return item;
    }
    static ItemStack fishIcon(String species, boolean enabled, String name, String... lore) {
        var item = FishMainGui.icon(Material.PAPER, name, lore);
        if (enabled) { var meta = item.getItemMeta(); fish(meta, species, true); if (meta != null) item.setItemMeta(meta); }
        return item;
    }
    private FishCookingVisuals() { }
}
