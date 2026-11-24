package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Temporary fail-closed guards for common vanilla conversions, not a cooking system.
 * Marked items, including corrupt ones, must not silently turn into generic cod.
 * Other plugin-driven transformations and trades are not covered by this listener.
 */
final class FishVanillaProtection implements Listener {
    private final FishItemCodec codec;
    private final BaitItemCodec bait;
    FishVanillaProtection(FishItemCodec codec) { this(codec, null); }
    FishVanillaProtection(FishItemCodec codec, BaitItemCodec bait) { this.codec = Objects.requireNonNull(codec); this.bait = bait; }
    private boolean marked(ItemStack item) { return codec.marked(item) || (bait != null && bait.marked(item)); }
    private boolean containsMarked(ItemStack[] matrix) {
        for (ItemStack item : matrix) if (marked(item)) return true;
        return false;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void cook(BlockCookEvent event) {
        if (marked(event.getSource())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void prepare(PrepareItemCraftEvent event) {
        if (containsMarked(event.getInventory().getMatrix())) event.getInventory().setResult(null);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void craft(CraftItemEvent event) {
        if (containsMarked(event.getInventory().getMatrix())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void consume(PlayerItemConsumeEvent event) {
        if (codec.marked(event.getItem())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cКастомную рыбу пока нельзя есть или готовить ванильным способом.");
        }
    }
}
