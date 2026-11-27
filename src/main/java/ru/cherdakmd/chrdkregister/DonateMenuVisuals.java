package ru.cherdakmd.chrdkregister;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Fixed icons for virtual payment links, configured tiers and private history. */
final class DonateMenuVisuals {
    enum Icon { PAY, ADMIN, TIER_1, TIER_2, TIER_3, TIER_4, TIER_5, TIER_6, TIER_7, EMPTY, PENDING, GRANTED, REJECTED;
        NamespacedKey model() { return new NamespacedKey("chrdk_core", "menu/donate/"+name().toLowerCase(java.util.Locale.ROOT)); }
    }
    private DonateMenuVisuals() { }
    static Icon tier(int index) { return switch(index) { case 0 -> Icon.TIER_1;case 1 -> Icon.TIER_2;case 2 -> Icon.TIER_3;case 3 -> Icon.TIER_4;case 4 -> Icon.TIER_5;case 5 -> Icon.TIER_6;case 6 -> Icon.TIER_7;default -> null; }; }
    static Icon history(RegistrationStore.DonationStatus status) {
        if (status==null) return null;
        return switch(status) { case GRANTED -> Icon.GRANTED;case REJECTED -> Icon.REJECTED;default -> Icon.PENDING; };
    }
    static void apply(ItemMeta meta,Icon icon,boolean enabled) {
        if (enabled && meta!=null && icon!=null && meta.getItemModel()==null && !meta.hasCustomModelData())meta.setItemModel(icon.model());
    }
}
