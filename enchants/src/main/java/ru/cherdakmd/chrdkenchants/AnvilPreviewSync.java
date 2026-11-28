package ru.cherdakmd.chrdkenchants;

import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.view.AnvilView;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Refresh only the currently open anvil after all prepare listeners have run. */
final class AnvilPreviewSync {
    private record Target(Player player, AnvilView view) { }
    private final Map<UUID, Target> pending = new HashMap<>();
    private final Consumer<Runnable> nextTick;

    AnvilPreviewSync(Consumer<Runnable> nextTick) { this.nextTick = nextTick; }

    void queue(AnvilView view) {
        if (view == null || !(view.getPlayer() instanceof Player player)) return;
        UUID id = player.getUniqueId();
        Target old = pending.put(id, new Target(player, view));
        if (old != null) return; // Multiple prepare events in one tick use the last view.
        try {
            nextTick.accept(() -> refresh(id));
        } catch (RuntimeException failure) {
            pending.remove(id);
            throw failure;
        }
    }

    private void refresh(UUID id) {
        Target target = pending.remove(id);
        if (target == null || !target.player().isOnline()) return;
        InventoryView current = target.player().getOpenInventory();
        if (!(current instanceof AnvilView) || !current.getTopInventory().equals(target.view().getTopInventory())) return;
        // Send the real final inventory, including changes made by later listeners;
        // do not rebuild output, alter a slot, charge, grant or consume anything.
        target.player().updateInventory();
    }
}
