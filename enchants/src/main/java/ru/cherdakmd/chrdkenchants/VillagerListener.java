package ru.cherdakmd.chrdkenchants;

import org.bukkit.Material;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

/**
 * Источник: жители-библиотекари. С шансом книга в новом трейде заменяется
 * на книгу кастомной эпической чары.
 */
public final class VillagerListener implements Listener {

    private final EnchantsPlugin plugin;

    public VillagerListener(EnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onTradeAcquire(VillagerAcquireTradeEvent event) {
        if (!plugin.config().villagerEnabled()) return;
        AbstractVillager villager = event.getEntity();
        if (!(villager instanceof Villager natural)
                || natural.getProfession() != Villager.Profession.LIBRARIAN) {
            return;
        }
        MerchantRecipe recipe = event.getRecipe();
        if (recipe.getResult().getType() != Material.ENCHANTED_BOOK) {
            return;
        }
        double chance = plugin.config().villagerConvertChancePercent() / 100.0;
        if (plugin.service().random().nextDouble() >= chance) {
            return;
        }
        EnchantService.RollResult roll = plugin.service().rollBook(3);
        if (roll == null) {
            return;
        }
        ItemStack book = plugin.items().makeEnchantBook(roll.def(), roll.level());
        MerchantRecipe custom = new MerchantRecipe(book, recipe.getUses(), recipe.getMaxUses());
        recipe.getIngredients().forEach(custom::addIngredient);
        custom.setExperienceReward(true);
        custom.setVillagerExperience(recipe.getVillagerExperience());
        custom.setPriceMultiplier(recipe.getPriceMultiplier());
        event.setRecipe(custom);
    }
}
