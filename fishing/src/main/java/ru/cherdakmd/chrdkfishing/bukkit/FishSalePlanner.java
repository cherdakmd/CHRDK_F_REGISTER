package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkfishing.FishSaleJournal;
import ru.cherdakmd.chrdkfishing.FishToken;

/** Side-effect-free sale quote; only a journaled native transfer can remove items or pay. */
public final class FishSalePlanner {
    private static final long MAX_AMOUNT = 1_000_000;
    public record Entry(int slot, FishToken.Token token, FishSaleJournal.VanillaFish vanilla) {
        public Entry {
            if (slot < 0 || slot >= 36 || (token == null) == (vanilla == null) || (vanilla != null && vanilla.slot() != slot))
                throw new IllegalArgumentException("sale entry");
        }
        public long price() { return token != null ? token.quotedPrice() : Math.multiplyExact(vanilla.quantity(), FishSaleJournal.VANILLA_PRICE_MINOR); }
    }
    public static final class Plan {
        private final ItemStack[] before, after;
        private final List<Entry> entries;
        private final long price;
        private Plan(ItemStack[] before, ItemStack[] after, List<Entry> entries, long price) {
            this.before = copy(before); this.after = copy(after); this.entries = List.copyOf(entries); this.price = price;
        }
        public ItemStack[] before() { return copy(before); }
        public ItemStack[] after() { return copy(after); }
        public List<Entry> entries() { return entries; }
        public List<FishToken.Token> fish() { return entries.stream().map(Entry::token).filter(Objects::nonNull).toList(); }
        public List<FishSaleJournal.VanillaFish> vanilla() { return entries.stream().map(Entry::vanilla).filter(Objects::nonNull).toList(); }
        public long price() { return price; }
    }
    public static final class DuplicateFish extends IllegalArgumentException {
        DuplicateFish() { super("duplicate fish identity in storage"); }
    }
    private FishSalePlanner() { }
    private static ItemStack[] copy(ItemStack[] source) {
        var copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) copy[i] = source[i] == null ? null : source[i].clone();
        return copy;
    }
    /** Never regard any item with metadata as vanilla, including broken CHRDK fish or other plugins' items. */
    public static Optional<Entry> inspect(int slot, ItemStack item, FishItemCodec codec) {
        Objects.requireNonNull(codec, "fish codec");
        if (item == null) return Optional.empty();
        var token = codec.read(item);
        if (token.isPresent()) return Optional.of(new Entry(slot, token.get(), null));
        if (item.hasItemMeta()) return Optional.empty();
        Material material = item.getType();
        if (material != Material.COD && material != Material.SALMON && material != Material.TROPICAL_FISH && material != Material.PUFFERFISH)
            return Optional.empty();
        int quantity = item.getAmount();
        if (quantity < 1 || quantity > 64 || quantity > item.getMaxStackSize()) return Optional.empty();
        return Optional.of(new Entry(slot, null, new FishSaleJournal.VanillaFish(slot, material.name(), quantity)));
    }
    public static Plan plan(ItemStack[] storage, List<Integer> slots, FishItemCodec codec) {
        Objects.requireNonNull(codec, "fish codec");
        if (storage == null || storage.length != 36 || slots == null || slots.isEmpty() || slots.size() > 36)
            throw new IllegalArgumentException("sale selection");
        var before = copy(storage); var after = copy(before); Set<Integer> selected = new HashSet<>();
        Set<UUID> identities = new HashSet<>(); List<Entry> entries = new ArrayList<>(); long total = 0;
        for (Integer slot : slots) {
            if (slot == null || slot < 0 || slot >= 36 || !selected.add(slot)) throw new IllegalArgumentException("sale slot");
            var entry = inspect(slot, before[slot], codec).orElseThrow(() -> new IllegalArgumentException("invalid fish"));
            if (entry.token() != null && !identities.add(entry.token().id())) throw new DuplicateFish();
            total = Math.addExact(total, entry.price());
            if (total > MAX_AMOUNT) throw new IllegalArgumentException("sale maximum");
            entries.add(entry); after[slot] = null;
        }
        // Copies elsewhere cannot be detected by this storage-only check.
        for (int slot = 0; slot < before.length; slot++) {
            if (selected.contains(slot) || before[slot] == null) continue;
            var other = codec.read(before[slot]);
            if (other.isPresent() && identities.contains(other.get().id())) throw new DuplicateFish();
        }
        return new Plan(before, after, entries, total);
    }
}
