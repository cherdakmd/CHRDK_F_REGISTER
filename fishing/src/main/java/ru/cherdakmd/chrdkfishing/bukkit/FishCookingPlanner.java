package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Arrays;
import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkfishing.FishToken;

/**
 * Provisional simple recipes for one valid custom fish; soup also needs a bowl.
 * Planning is side-effect-free and does NOT authorize cooking. A shared durable
 * redemption ledger with sale and a native inventory witness are required first.
 */
public final class FishCookingPlanner {
    public enum Recipe {
        FRIED("Жареная рыба", Material.COOKED_COD, false),
        SMOKED("Копчёная рыба", Material.COOKED_SALMON, false),
        SOUP("Рыбацкая уха", Material.MUSHROOM_STEW, true);
        private final String title;
        private final Material material;
        private final boolean bowl;
        Recipe(String title, Material material, boolean bowl) { this.title = title; this.material = material; this.bowl = bowl; }
        public String title() { return title; }
        public Material material() { return material; }
        public boolean needsBowl() { return bowl; }
    }
    public static final class Plan {
        private final ItemStack[] before, after;
        private final FishToken.Token fish;
        private final int slot;
        private final Recipe recipe;
        private Plan(ItemStack[] before, ItemStack[] after, FishToken.Token fish, int slot, Recipe recipe) {
            this.before = copy(before); this.after = copy(after); this.fish = fish; this.slot = slot; this.recipe = recipe;
        }
        public ItemStack[] before() { return copy(before); }
        public ItemStack[] after() { return copy(after); }
        public FishToken.Token fish() { return fish; }
        public int slot() { return slot; }
        public Recipe recipe() { return recipe; }
    }
    private static ItemStack[] copy(ItemStack[] items) {
        return Arrays.stream(items).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
    }
    public static Plan plan(ItemStack[] storage, int slot, FishItemCodec codec) {
        return plan(storage, slot, codec, Recipe.FRIED);
    }
    public static Plan plan(ItemStack[] storage, int slot, FishItemCodec codec, Recipe recipe) {
        Objects.requireNonNull(codec, "codec"); Objects.requireNonNull(recipe, "recipe");
        if (storage == null || storage.length != 36 || slot < 0 || slot >= 36)
            throw new IllegalArgumentException("cooking input");
        ItemStack[] before = copy(storage), after = copy(storage);
        FishToken.Token token = codec.read(before[slot]).orElseThrow(() -> new IllegalArgumentException("invalid fish"));
        // Identical IDs in visible storage are never consumed as separate catches.
        for (int i = 0; i < before.length; i++) {
            if (i == slot) continue;
            var other = codec.read(before[i]);
            if (other.isPresent() && other.get().id().equals(token.id())) throw new IllegalArgumentException("duplicate fish identity");
        }
        if (recipe.needsBowl()) {
            boolean found = false;
            for (int i = 0; i < after.length; i++) {
                ItemStack item = after[i];
                if (i == slot || item == null || item.getType() != Material.BOWL || item.hasItemMeta()) continue;
                if (item.getAmount() < 1 || item.getAmount() > item.getMaxStackSize()) throw new IllegalArgumentException("invalid bowl stack");
                if (item.getAmount() == 1) after[i] = null; else item.setAmount(item.getAmount() - 1);
                found = true; break;
            }
            if (!found) throw new IllegalArgumentException("bowl required");
        }
        var dish = new ItemStack(recipe.material(), 1);
        var meta = dish.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text(recipe.title(), net.kyori.adventure.text.format.NamedTextColor.GOLD)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        dish.setItemMeta(meta); after[slot] = dish;
        return new Plan(before, after, token, slot, recipe);
    }
    private FishCookingPlanner() { }
}
