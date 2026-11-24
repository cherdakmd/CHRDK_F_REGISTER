package ru.cherdakmd.chrdkskills;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Optional ServicesManager contract for the future fishing module. The provider
 * must authenticate the original and mint a newly signed, unique fish ID for an
 * actual Skills extra-catch event. Returning null means no extra catch. This
 * service must never be exposed as a general-purpose duplication command.
 */
public interface FishingCatchBonus {
    ItemStack createExtra(Player player, ItemStack original);
    /** Optional validated rod multiplier, in whole percent; Skills clamps it and awards once. */
    default int rodXpBonusPercent(org.bukkit.event.player.PlayerFishEvent event) { return 0; }
}
