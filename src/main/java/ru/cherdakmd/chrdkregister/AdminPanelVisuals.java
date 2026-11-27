package ru.cherdakmd.chrdkregister;

import java.util.Locale;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Models for virtual moderation controls; player heads and actual items are not altered. */
final class AdminPanelVisuals {
    enum Icon { MODERATORS, JOURNAL, MUTES, HONORS, FILTER, REFRESH, PREV, NEXT, CLOSE, BACK, INFO,
        WARN, BAN, UNBAN, ROLE_ADD, ROLE_REMOVE, PUNISH, WARN_ACTIVE, WARN_REMOVED, MODERATOR_ENTRY,
        JOURNAL_ENTRY, MUTE_ENTRY, PUNISH_INFO, MUTE_PRESET, MUTE_CUSTOM, UNMUTE, KICK, IP_BAN,
        IP_UNBAN, CHAT_HISTORY, CHAT_ENTRY, PAGES, EMPTY }
    private AdminPanelVisuals() { }
    static NamespacedKey key(Icon icon) {
        return icon == null ? null : new NamespacedKey("chrdk_core", "menu/admin_panel/" + icon.name().toLowerCase(Locale.ROOT));
    }
    static void apply(ItemMeta meta, Icon icon, boolean enabled) {
        if (enabled && meta != null && icon != null && meta.getItemModel() == null && !meta.hasCustomModelData())
            meta.setItemModel(key(icon));
    }
}
