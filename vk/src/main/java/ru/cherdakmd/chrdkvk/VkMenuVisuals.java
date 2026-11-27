package ru.cherdakmd.chrdkvk;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Virtual icons only; no account identifiers, secrets or player items enter model keys. */
final class VkMenuVisuals {
    enum Icon {
        LINK_UNLINKED, LINK_LINKED, NOTIFY_UNLINKED, NOTIFY_OFF, NOTIFY_ON,
        CHAT_HIDDEN, CHAT_VISIBLE, BADGE, SECURITY_ACTIVE, SECURITY_FROZEN, UNLINK_CONFIRM, CLOSE;
        NamespacedKey model() {
            return new NamespacedKey("chrdk_vk", "menu/" + name().toLowerCase(java.util.Locale.ROOT));
        }
    }
    static Icon notify(boolean linked, boolean enabled) {
        return !linked ? Icon.NOTIFY_UNLINKED : enabled ? Icon.NOTIFY_ON : Icon.NOTIFY_OFF;
    }
    static void apply(ItemMeta meta, Icon icon, boolean enabled) {
        if (!enabled || meta == null || icon == null || meta.getItemModel() != null || meta.hasCustomModelData()) return;
        meta.setItemModel(icon.model());
    }
}
