package ru.cherdakmd.chrdkgenerator;

import java.io.IOException;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.api.ChargeInventory;
import ru.cherdakmd.chrdkregister.api.InventoryTransfer;

/** Native storage-only переносы: курсор, броня и offhand никогда не изменяются. */
final class GeneratorContainerTransfer {
    private static final NamespacedKey WITNESS = new NamespacedKey("chrdk_generator", "container_transfer");
    private final GeneratorContainerStore store;
    private final java.util.function.Predicate<Player> authenticated;

    GeneratorContainerTransfer(GeneratorContainerStore store, java.util.function.Predicate<Player> authenticated) {
        this.store = java.util.Objects.requireNonNull(store);
        this.authenticated = java.util.Objects.requireNonNull(authenticated);
    }

    /** Переносит один проверенный стак из обычного инвентаря в ячейку топлива. */
    InventoryTransfer.Decision deposit(Player player, int generatorSlot, int playerSlot, int fuelIndex) throws IOException {
        if (!available(player) || playerSlot < 0 || playerSlot >= 36 || fuelIndex < GeneratorContainerStore.RESOURCE_SLOTS
                || fuelIndex >= GeneratorContainerStore.SLOTS || !emptyCursor(player)) return InventoryTransfer.Decision.WAIT;
        var view = store.view(player.getUniqueId(), generatorSlot);
        if (view == null) return InventoryTransfer.Decision.CONFLICT;
        if (view.pending()) return recover(player, generatorSlot);
        var before = ChargeInventory.copy(player.getInventory().getStorageContents());
        ItemStack item = before[playerSlot];
        var fuel = GeneratorFurnaceFuel.inspect(item);
        if (fuel.isEmpty()) return InventoryTransfer.Decision.CONFLICT;
        var previous = view.slots().get(fuelIndex);
        String material = fuel.get().material();
        if (previous != null && !previous.material().equals(material)) return InventoryTransfer.Decision.CONFLICT;
        int max = Math.min(64, item.getMaxStackSize());
        int amount = Math.min(item.getAmount(), max - (previous == null ? 0 : previous.amount()));
        if (amount <= 0) return InventoryTransfer.Decision.CONFLICT;
        var after = ChargeInventory.copy(before);
        if (item.getAmount() == amount) after[playerSlot] = null;
        else after[playerSlot].setAmount(item.getAmount() - amount);
        return transfer(player, generatorSlot, view, fuelIndex, material, amount, before, after);
    }

    /** Переносит содержимое одной ячейки в storage; при нехватке места — только то, что влезло. */
    InventoryTransfer.Decision withdraw(Player player, int generatorSlot, int index) throws IOException {
        if (!available(player) || index < 0 || index >= GeneratorContainerStore.SLOTS || !emptyCursor(player))
            return InventoryTransfer.Decision.WAIT;
        var view = store.view(player.getUniqueId(), generatorSlot);
        if (view == null) return InventoryTransfer.Decision.CONFLICT;
        if (view.pending()) return recover(player, generatorSlot);
        var held = view.slots().get(index);
        if (held == null) return InventoryTransfer.Decision.CONFLICT;
        Material material = Material.matchMaterial(held.material());
        if (material == null || material.isAir() || !material.isItem()) return InventoryTransfer.Decision.CONFLICT;
        ItemStack plain = new ItemStack(material);
        int max = plain.getMaxStackSize();
        var before = ChargeInventory.copy(player.getInventory().getStorageContents());
        var after = ChargeInventory.copy(before);
        int remaining = held.amount();
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            for (int i = 0; i < after.length && remaining > 0; i++) {
                var existing = after[i];
                if (pass == 0 && (existing == null || !existing.isSimilar(plain))) continue;
                if (pass == 1 && existing != null && !existing.getType().isAir()) continue;
                int count = existing == null || existing.getType().isAir() ? 0 : existing.getAmount();
                int added = Math.min(remaining, Math.max(0, max - count));
                if (added == 0) continue;
                if (count == 0) { after[i] = plain.clone(); after[i].setAmount(added); }
                else existing.setAmount(count + added);
                remaining -= added;
            }
        }
        int amount = held.amount() - remaining;
        if (amount == 0) return InventoryTransfer.Decision.CONFLICT;
        return transfer(player, generatorSlot, view, index, held.material(), -amount, before, after);
    }

    InventoryTransfer.Decision recover(Player player, int generatorSlot) throws IOException {
        if (!available(player)) return InventoryTransfer.Decision.WAIT;
        return store.recover(player.getUniqueId(), generatorSlot,
                ChargeInventory.inventory(player, WITNESS, () -> authenticated.test(player)));
    }

    private InventoryTransfer.Decision transfer(Player player, int slot, GeneratorContainerStore.View view,
                                                  int index, String material, int delta,
                                                  ItemStack[] before, ItemStack[] after) throws IOException {
        String oldInventory = ChargeInventory.encode(before);
        String newInventory = ChargeInventory.encode(after);
        if (!store.prepare(player.getUniqueId(), slot, view.core(), view.revision(), UUID.randomUUID(),
                index, material, delta, oldInventory, newInventory)) return InventoryTransfer.Decision.CONFLICT;
        return recover(player, slot);
    }

    private boolean available(Player player) {
        return player != null && org.bukkit.Bukkit.isPrimaryThread() && player.isOnline()
                && !player.hasMetadata("NPC") && authenticated.test(player);
    }

    private static boolean emptyCursor(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        return cursor == null || cursor.getType().isAir() || cursor.getAmount() <= 0;
    }
}
