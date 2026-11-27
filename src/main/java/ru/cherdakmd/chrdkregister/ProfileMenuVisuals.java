package ru.cherdakmd.chrdkregister;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Only built-in virtual buttons, not player heads or addon-provided sections. */
final class ProfileMenuVisuals {
    enum Button { CHAT, LABELS, FRIEND, GROUP, REFRESH, CLOSE;
        NamespacedKey model() { return new NamespacedKey("chrdk_core", "menu/profile/"+name().toLowerCase(java.util.Locale.ROOT)); }
    }
    private ProfileMenuVisuals() { }
    static Button button(String key) {
        return switch(key) {
            case "profile.to-chat" -> Button.CHAT; case "profile.friend-invite-button" -> Button.FRIEND;
            case "profile.group-invite" -> Button.GROUP; case "profile.refresh" -> Button.REFRESH;
            case "profile.close" -> Button.CLOSE; default -> null;
        };
    }
    static void apply(ItemMeta meta,Button button,boolean enabled) {
        if(enabled&&meta!=null&&button!=null&&meta.getItemModel()==null&&!meta.hasCustomModelData())meta.setItemModel(button.model());
    }
}
