package ru.cherdakmd.chrdkenchants;

import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Virtual navigation and confirmation icons only, never player items. */
final class EnchantHubVisuals {
    static final Set<String> IDS=Set.of("catalog","library","scribe","inspect","hand","sources","anvil","cleanse","combine","guard","cooldowns","books","workshop","effects","light","curses","search","help","guard-status","admin","guard-auto","guard-briar_guard","guard-faltering_guard","guard-signal_guard","guard-pve_anchor","cleanse-choice","cleanse-effect","combine-effect","confirm","close","back");
    private EnchantHubVisuals() { }
    static NamespacedKey key(String id) {
        return id == null || !IDS.contains(id) ? null : new NamespacedKey("chrdk_enchants", "menu/hub/" + id);
    }
    static void apply(ItemMeta meta,String id,boolean enabled) {
        var key=key(id);
        if(enabled && key!=null && meta!=null && meta.getItemModel()==null && !meta.hasCustomModelData()) meta.setItemModel(key);
    }
}
