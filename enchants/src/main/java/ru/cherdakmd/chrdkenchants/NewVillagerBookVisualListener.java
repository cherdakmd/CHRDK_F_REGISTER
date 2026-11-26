package ru.cherdakmd.chrdkenchants;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

/** Styles only newly acquired villager offers, never existing offers or inventories. */
final class NewVillagerBookVisualListener implements Listener {
    private final BooleanSupplier enabled;
    private final Predicate<ItemStack> decorate;

    NewVillagerBookVisualListener(EnchantsPlugin plugin) {
        this(() -> plugin.getConfig().getBoolean("textures.books", false)
                        && plugin.getConfig().getBoolean("textures.new-villager-books", false),
                item -> plugin.items().refreshBookVisual(item));
    }

    NewVillagerBookVisualListener(BooleanSupplier enabled, Predicate<ItemStack> decorate) {
        this.enabled = Objects.requireNonNull(enabled);
        this.decorate = Objects.requireNonNull(decorate);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAcquire(VillagerAcquireTradeEvent event) {
        if (!enabled.getAsBoolean() || !(event.getEntity() instanceof Villager)) return;
        MerchantRecipe original = event.getRecipe();
        if (original == null) return;
        ItemStack result = original.getResult(); // Paper returns a clone, not the offer's stored result.
        if (result == null || result.getType() != Material.ENCHANTED_BOOK || !result.hasItemMeta()) return;
        var meta = result.getItemMeta();
        if (meta == null || meta.getItemModel() != null || meta.hasCustomModelData()
                || meta.hasDisplayName() || meta.hasLore()) return;
        if (!decorate.test(result)) return;
        // MerchantRecipe has no result setter. Copy every public pricing/usage field,
        // including Paper's ignoreDiscounts flag; do not touch the original offer.
        var replacement = new MerchantRecipe(result, original.getUses(), original.getMaxUses(),
                original.hasExperienceReward(), original.getVillagerExperience(), original.getPriceMultiplier(),
                original.getDemand(), original.getSpecialPrice(), original.shouldIgnoreDiscounts());
        replacement.setIngredients(original.getIngredients());
        event.setRecipe(replacement);
    }
}
