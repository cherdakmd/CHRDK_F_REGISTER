package ru.cherdakmd.chrdkclaims;

import java.util.Locale;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Fixed icons for newly constructed menu tiles, never for player inventories. */
final class ClaimMenuVisuals {
    enum Icon {
        INFO, PREV, NEXT, CLAN, CLOSE, EMPTY_MINE, EMPTY_SALE,
        ENTRY_MINE, ENTRY_OTHER_WORLD, ENTRY_SALE, ENTRY_ACCESS;
        NamespacedKey model() { return new NamespacedKey("chrdk_claims", "menu/" + name().toLowerCase(Locale.ROOT)); }
    }
    static NamespacedKey tab(ClaimMenuModel.Tab tab, boolean selected) {
        String name = switch (tab) { case MINE -> "tab_mine"; case FOR_SALE -> "tab_sale"; case ACCESSIBLE -> "tab_access"; };
        return new NamespacedKey("chrdk_claims", "menu/" + name + (selected ? "_selected" : "_normal"));
    }
    static Icon entry(ClaimMenuModel.Entry entry, String world) {
        if (entry.mine()) return entry.world().equals(world) ? Icon.ENTRY_MINE : Icon.ENTRY_OTHER_WORLD;
        return entry.salePrice() > 0 ? Icon.ENTRY_SALE : Icon.ENTRY_ACCESS;
    }
    static void apply(ItemMeta meta, NamespacedKey key, boolean enabled) {
        if (!enabled || meta == null || key == null || meta.getItemModel() != null || meta.hasCustomModelData()) return;
        meta.setItemModel(key);
    }
}
