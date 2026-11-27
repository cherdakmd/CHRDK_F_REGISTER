package ru.cherdakmd.chrdkguilds;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Visual models only for virtual main-menu controls; member heads and frames remain untouched. */
final class GuildMainVisuals {
    enum Control {
        RECRUIT_LIST("recruit_list"), RECRUIT_OPEN("recruit_open"), RECRUIT_CLOSED("recruit_closed"),
        CLAIMS("claims"), MODE_PEACEFUL("mode_peaceful"), MODE_BATTLE("mode_battle"), PERSONAL("personal"), DUES("dues"),
        STYLE("style"), BACK("back"), CLOSE("close"), INFO_NONE("info_none"), INFO_PEACEFUL("info_peaceful"), INFO_BATTLE("info_battle"),
        INVITE("invite"), TREASURY("treasury"), UPGRADE_AVAILABLE("upgrade_available"), UPGRADE_MAX("upgrade_max"),
        WAR_NONE("war_none"), WAR_COOLDOWN("war_cooldown"), WAR_PENDING("war_pending"), WAR_ACTIVE("war_active");
        private final String id;
        Control(String id) { this.id=id; }
        NamespacedKey model() { return new NamespacedKey("chrdk_guilds", "menu/main/"+id); }
    }
    private GuildMainVisuals() { }
    static Control button(String key) {
        return switch(key) {
            case "recruit-list-button" -> Control.RECRUIT_LIST; case "recruit-open-button" -> Control.RECRUIT_OPEN;
            case "recruit-closed-button" -> Control.RECRUIT_CLOSED; case "menu-claims-name" -> Control.CLAIMS;
            case "menu-mode-peaceful" -> Control.MODE_PEACEFUL; case "menu-mode-battle" -> Control.MODE_BATTLE;
            case "menu-personal-name" -> Control.PERSONAL; case "dues-menu-name" -> Control.DUES;
            case "style-menu-name" -> Control.STYLE; case "menu-back" -> Control.BACK; case "menu-close" -> Control.CLOSE;
            case "menu-info-no-guild" -> Control.INFO_NONE; case "menu-invite-name" -> Control.INVITE;
            case "menu-treasury-name" -> Control.TREASURY; default -> null;
        };
    }
    static Control info(boolean peaceful) { return peaceful?Control.INFO_PEACEFUL:Control.INFO_BATTLE; }
    static Control upgrade(int level) { return GuildMath.canUpgrade(level)?Control.UPGRADE_AVAILABLE:Control.UPGRADE_MAX; }
    static Control war(GuildWar war,boolean cooldown) { return war==null?(cooldown?Control.WAR_COOLDOWN:Control.WAR_NONE):war.pending()?Control.WAR_PENDING:Control.WAR_ACTIVE; }
    static void apply(ItemMeta meta,Control control,boolean enabled) {
        if (!enabled || meta==null || control==null || meta.getItemModel()!=null || meta.hasCustomModelData()) return;
        meta.setItemModel(control.model());
    }
}
