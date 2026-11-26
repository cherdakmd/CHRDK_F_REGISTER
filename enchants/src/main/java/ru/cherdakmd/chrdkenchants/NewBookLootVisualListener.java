package ru.cherdakmd.chrdkenchants;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;

/** Only freshly generated vanilla container loot, never inventory scans or plugin-supplied loot. */
final class NewBookLootVisualListener implements Listener {
    private final BooleanSupplier enabled;
    private final Predicate<ItemStack> decorate;

    NewBookLootVisualListener(EnchantsPlugin plugin) {
        this(() -> plugin.getConfig().getBoolean("textures.books", false),
             item -> plugin.items().refreshBookVisual(item));
    }
    NewBookLootVisualListener(BooleanSupplier enabled, Predicate<ItemStack> decorate) {
        this.enabled = Objects.requireNonNull(enabled); this.decorate = Objects.requireNonNull(decorate);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLoot(LootGenerateEvent event) {
        if (!enabled.getAsBoolean() || event.isPlugin() || event.getLootTable() == null
                || event.getLootTable().getKey() == null
                || !"minecraft".equals(event.getLootTable().getKey().getNamespace())) return;
        var iterator = event.getLoot().listIterator();
        while (iterator.hasNext()) {
            ItemStack replacement = decorateNew(iterator.next());
            if (replacement != null) iterator.set(replacement);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFishing(org.bukkit.event.player.PlayerFishEvent event) {
        if (!enabled.getAsBoolean() || event.getState() != org.bukkit.event.player.PlayerFishEvent.State.CAUGHT_FISH
                || !(event.getCaught() instanceof org.bukkit.entity.Item entity)) return;
        ItemStack replacement = decorateNew(entity.getItemStack());
        if (replacement != null) entity.setItemStack(replacement);
    }
    private ItemStack decorateNew(ItemStack original) {
        if (original == null || original.getType() != Material.ENCHANTED_BOOK || !original.hasItemMeta()) return null;
        var meta = original.getItemMeta();
        if (meta == null || meta.getItemModel() != null || meta.hasCustomModelData()) return null;
        ItemStack copy = original.clone();
        return decorate.test(copy) ? copy : null;
    }
}
