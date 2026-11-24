package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkfishing.FishToken;

/**
 * Builds a sale snapshot exclusively from verified items actually in storage.
 * Does not move items, touch a cursor, open an inventory, or authorize a payout.
 * The native transaction must recheck/compare BEFORE and checkpoint a nonce.
 */
public final class FishSalePlanner {
    private static final long MAX_AMOUNT = 1_000_000;
    public static final class Plan {
        private final ItemStack[] before, after;
        private final List<FishToken.Token> fish;
        private final long price;
        private Plan(ItemStack[] before, ItemStack[] after, List<FishToken.Token> fish, long price) {
            this.before = copy(before); this.after = copy(after); this.fish = List.copyOf(fish); this.price = price;
        }
        public ItemStack[] before() { return copy(before); }
        public ItemStack[] after() { return copy(after); }
        public List<FishToken.Token> fish() { return fish; }
        public long price() { return price; }
    }
    private FishSalePlanner() { }
    private static ItemStack[] copy(ItemStack[] source) {
        var copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) copy[i] = source[i] == null ? null : source[i].clone();
        return copy;
    }
    /** Selection is zero-based storage slots, not GUI slots; all selected items must be valid unique fish. */
    public static Plan plan(ItemStack[] storage, List<Integer> slots, FishItemCodec codec) {
        Objects.requireNonNull(codec, "fish codec");
        if (storage == null || storage.length != 36 || slots == null || slots.isEmpty() || slots.size() > 36)
            throw new IllegalArgumentException("sale selection");
        var before = copy(storage); var after = copy(before); Set<Integer> selected = new HashSet<>();
        Set<UUID> identities = new HashSet<>(); List<FishToken.Token> fish = new ArrayList<>(); long total = 0;
        for (Integer slot : slots) {
            if (slot == null || slot < 0 || slot >= 36 || !selected.add(slot)) throw new IllegalArgumentException("sale slot");
            var token = codec.read(before[slot]).orElseThrow(() -> new IllegalArgumentException("invalid fish"));
            if (!identities.add(token.id())) throw new IllegalArgumentException("duplicate fish identity");
            total = Math.addExact(total, token.quotedPrice());
            if (total > MAX_AMOUNT) throw new IllegalArgumentException("sale maximum");
            fish.add(token); after[slot] = null;
        }
        return new Plan(before, after, fish, total);
    }
}
