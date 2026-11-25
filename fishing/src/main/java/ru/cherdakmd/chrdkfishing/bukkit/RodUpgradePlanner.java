package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkfishing.FishToken;
import ru.cherdakmd.chrdkfishing.RodToken;

/**
 * Provisional, side-effect-free upgrade quote. This does not authorize a bank
 * charge or item transfer: a durable journal and native nonce are mandatory.
 */
public final class RodUpgradePlanner {
    public record Ingredient(Material material, int count) {
        public Ingredient {
            if (material == null || count < 1 || count > 2304) throw new IllegalArgumentException("ingredient");
        }
    }
    public record Cost(int targetLevel, long minor, List<Ingredient> ingredients) {
        public Cost {
            if (targetLevel < 2 || targetLevel > 10 || minor < 1 || minor > 1_000_000 || ingredients == null || ingredients.isEmpty())
                throw new IllegalArgumentException("upgrade cost");
            ingredients = List.copyOf(ingredients);
            if (ingredients.stream().map(Ingredient::material).distinct().count() != ingredients.size())
                throw new IllegalArgumentException("duplicate ingredient");
        }
    }
    /** Assumed game values, not approved server prices. */
    private static final List<Cost> COSTS = List.of(
            cost(2, 100, Material.STRING, 8, Material.IRON_INGOT, 2),
            cost(3, 200, Material.STRING, 12, Material.IRON_INGOT, 4),
            cost(4, 350, Material.IRON_INGOT, 8, Material.LAPIS_LAZULI, 8),
            cost(5, 500, Material.GOLD_INGOT, 4, Material.LAPIS_LAZULI, 12),
            cost(6, 750, Material.GOLD_INGOT, 8, Material.AMETHYST_SHARD, 8),
            cost(7, 1000, Material.DIAMOND, 2, Material.AMETHYST_SHARD, 12),
            cost(8, 1500, Material.DIAMOND, 4, Material.PRISMARINE_SHARD, 16),
            cost(9, 2500, Material.DIAMOND, 8, Material.NAUTILUS_SHELL, 2),
            cost(10, 4000, Material.NETHERITE_SCRAP, 2, Material.NAUTILUS_SHELL, 4));
    private static Cost cost(int level, long minor, Material first, int firstCount, Material second, int secondCount) {
        return new Cost(level, minor, List.of(new Ingredient(first, firstCount), new Ingredient(second, secondCount)));
    }
    public static Cost costFor(int targetLevel) {
        if (targetLevel < 2 || targetLevel > 10) throw new IllegalArgumentException("target level");
        return COSTS.get(targetLevel - 2);
    }
    /** Counts the same ordinary, valid stacks that the planner is allowed to consume. */
    static int available(ItemStack[] storage, Material material) {
        if (storage == null || material == null) throw new IllegalArgumentException("material count");
        int count = 0;
        for (ItemStack item : storage) {
            if (item == null || item.getType() != material || item.hasItemMeta()) continue;
            if (item.getAmount() < 1 || item.getAmount() > item.getMaxStackSize()) continue;
            count = Math.addExact(count, item.getAmount());
        }
        return count;
    }
    public static final class Plan {
        private final ItemStack[] before, after;
        private final RodToken.Token oldToken, newToken;
        private final int rodSlot;
        private final Cost cost;
        private Plan(ItemStack[] before, ItemStack[] after, RodToken.Token oldToken, RodToken.Token newToken, int rodSlot, Cost cost) {
            this.before = copy(before); this.after = copy(after); this.oldToken = oldToken;
            this.newToken = newToken; this.rodSlot = rodSlot; this.cost = cost;
        }
        public ItemStack[] before() { return copy(before); }
        public ItemStack[] after() { return copy(after); }
        /** Null only for a previously unmarked level-one rod. */
        public RodToken.Token oldToken() { return oldToken; }
        public RodToken.Token newToken() { return newToken; }
        public int rodSlot() { return rodSlot; }
        public Cost cost() { return cost; }
    }
    private static ItemStack[] copy(ItemStack[] items) {
        return Arrays.stream(items).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
    }
    /**
     * Costs are taken only from plain storage items, never named/PDC items. The
     * resulting rod is a clone with only this plugin's token fields changed.
     * Existing tokens must be validated against a durable identity ledger before
     * purchase; a signature alone cannot distinguish duplicate copies.
     */
    public static Plan plan(ItemStack[] storage, int rodSlot, RodItemCodec codec, byte[] secret, UUID freshId) {
        Objects.requireNonNull(codec, "codec");
        if (secret == null || secret.length != FishToken.KEY_BYTES || storage == null || storage.length != 36
                || rodSlot < 0 || rodSlot >= 36) throw new IllegalArgumentException("upgrade input");
        ItemStack[] before = copy(storage), after = copy(storage);
        ItemStack rod = before[rodSlot];
        if (rod == null || rod.getType() != Material.FISHING_ROD || rod.getAmount() != 1)
            throw new IllegalArgumentException("rod required");
        RodToken.Token old = codec.read(rod).orElse(null);
        if (codec.marked(rod) && old == null) throw new IllegalArgumentException("invalid rod signature");
        int oldLevel = old == null ? 1 : old.level();
        if (oldLevel >= 10) throw new IllegalArgumentException("maximum rod level");
        UUID id = old == null ? Objects.requireNonNull(freshId, "fresh id") : old.id();
        // Detect a visible clone, but this is not a replacement for the ledger.
        if (old != null) for (int slot = 0; slot < before.length; slot++) {
            if (slot == rodSlot) continue;
            var other = codec.read(before[slot]);
            if (other.isPresent() && other.get().id().equals(id)) throw new IllegalArgumentException("duplicate rod identity");
        }
        Cost cost = costFor(oldLevel + 1);
        for (Ingredient ingredient : cost.ingredients()) {
            int remaining = ingredient.count();
            for (int slot = 0; slot < after.length && remaining > 0; slot++) {
                if (slot == rodSlot) continue;
                ItemStack item = after[slot];
                if (item == null || item.getType() != ingredient.material() || item.hasItemMeta()) continue;
                if (item.getAmount() < 1 || item.getAmount() > item.getMaxStackSize()) throw new IllegalArgumentException("invalid stack");
                int take = Math.min(remaining, item.getAmount());
                remaining -= take;
                if (take == item.getAmount()) after[slot] = null;
                else item.setAmount(item.getAmount() - take);
            }
            if (remaining != 0) throw new IllegalArgumentException("missing material: " + ingredient.material());
        }
        RodToken.Token next = RodToken.issue(secret, id, oldLevel + 1);
        after[rodSlot] = codec.stamp(rod, next);
        return new Plan(before, after, old, next, rodSlot, cost);
    }
    private RodUpgradePlanner() { }
}
