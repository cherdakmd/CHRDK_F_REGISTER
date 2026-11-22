package ru.cherdakmd.chrdkregister;

import java.util.OptionalInt;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Настройка видимости касается только TAB и только собственного числа. */
final class TabRebirth {
    private static final NamespacedKey HIDDEN = new NamespacedKey("chrdk_core", "tab_rebirth_hidden");
    private TabRebirth() { }
    static boolean eligible(String paidRankId) {
        return "patron".equals(paidRankId) || "founder".equals(paidRankId);
    }
    static boolean hidden(Player player) {
        return Byte.valueOf((byte) 1).equals(player.getPersistentDataContainer().get(HIDDEN, PersistentDataType.BYTE));
    }
    static void setHidden(Player player, boolean hidden) {
        if (hidden) player.getPersistentDataContainer().set(HIDDEN, PersistentDataType.BYTE, (byte) 1);
        else player.getPersistentDataContainer().remove(HIDDEN);
        player.saveData();
    }
    static Component append(Component base, OptionalInt count, boolean entitled, boolean hideRequested) {
        if (count.isEmpty() || count.getAsInt() < 0 || entitled && hideRequested) return base;
        return base.append(Component.text(" ⟳" + count.getAsInt(), NamedTextColor.GOLD));
    }
}
