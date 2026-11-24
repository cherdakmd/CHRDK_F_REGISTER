package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkfishing.BaitCatalog;
import ru.cherdakmd.chrdkfishing.BaitToken;

/** Builds a frozen delivery snapshot; no money or items are changed by planning. */
final class BaitPurchasePlanner {
    static final class Plan {
        private final ItemStack[] before, after;
        private final BaitToken.Token token;
        private final long minor;
        Plan(ItemStack[] before, ItemStack[] after, BaitToken.Token token, long minor) {
            this.before = copy(before); this.after = copy(after); this.token = token; this.minor = minor;
        }
        ItemStack[] before() { return copy(before); }
        ItemStack[] after() { return copy(after); }
        BaitToken.Token token() { return token; }
        long minor() { return minor; }
    }
    private static ItemStack[] copy(ItemStack[] items) {
        return Arrays.stream(items).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
    }
    static Plan plan(ItemStack[] storage, String kind, BaitItemCodec codec, byte[] key, UUID id) {
        Objects.requireNonNull(codec); Objects.requireNonNull(id);
        if (storage == null || storage.length != 36) throw new IllegalArgumentException("storage size");
        var bait = BaitCatalog.byId(kind);
        if (bait == null) throw new IllegalArgumentException("bait kind");
        ItemStack[] before = copy(storage), after = copy(storage);
        int empty = -1;
        for (int i = 0; i < after.length; i++) {
            ItemStack item = after[i];
            if (item == null || item.getType() == Material.AIR) { empty = i; break; }
        }
        if (empty < 0) throw new IllegalArgumentException("inventory full");
        BaitToken.Token token = BaitToken.issue(key, id, kind);
        after[empty] = codec.build(token);
        return new Plan(before, after, token, bait.priceMinor());
    }
    private BaitPurchasePlanner() { }
}
