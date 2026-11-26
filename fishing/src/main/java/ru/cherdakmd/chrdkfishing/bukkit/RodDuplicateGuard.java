package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Objects;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkfishing.RodToken;

/** A local preflight only: unseen copies cannot be ruled out. Never changes items. */
final class RodDuplicateGuard {
    static boolean hasVisibleDuplicate(RodToken.Token selected, RodItemCodec codec, ItemStack... otherItems) {
        Objects.requireNonNull(codec, "codec");
        if (selected == null) return false; // Vanilla rods have no identity yet.
        for (ItemStack item : otherItems) {
            if (item == null) continue;
            var other = codec.read(item);
            if (other.isPresent() && selected.id().equals(other.get().id())) return true;
        }
        return false;
    }
    private RodDuplicateGuard() { }
}
