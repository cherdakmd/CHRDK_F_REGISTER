package ru.cherdakmd.chrdkgenerator;

import java.io.IOException;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.api.ChargeInventory;
import ru.cherdakmd.chrdkregister.api.InventoryTransfer;

/** Снятие ядра через сохранённое намерение и nonce игрока; без выбрасывания предмета в мир. */
final class GeneratorCoreTake {
    private static final NamespacedKey WITNESS = new NamespacedKey("chrdk_generator", "core_take");
    private final GeneratorPlugin plugin;
    GeneratorCoreTake(GeneratorPlugin plugin) { this.plugin = plugin; }

    boolean start(Player player, int slot) throws IOException {
        if (!plugin.store().payoutCheckpoint() || !plugin.coreApi().isAuthenticated(player)) return false;
        var cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir()) return false;
        var entry = plugin.store().entry(player.getUniqueId(), slot);
        if (entry == null || !entry.installed()) return false;
        var before = ChargeInventory.copy(player.getInventory().getStorageContents());
        var after = ChargeInventory.copy(before);
        int empty = -1;
        for (int i = 0; i < after.length; i++) if (after[i] == null || after[i].getType() == Material.AIR) { empty = i; break; }
        if (empty < 0) return false;
        after[empty] = plugin.coreItem().buildRestored(entry.coreItem(), entry.coreTextured());
        var intent = new GeneratorStore.CoreTake(UUID.randomUUID(), entry.coreItem(),
                ChargeInventory.encode(before), ChargeInventory.encode(after));
        if (!plugin.store().prepareCoreTake(player.getUniqueId(), slot, intent)) return false;
        return recover(player, slot) == InventoryTransfer.Decision.APPLIED;
    }

    InventoryTransfer.Decision recover(Player player, int slot) throws IOException {
        if (!plugin.store().payoutCheckpoint()) return InventoryTransfer.Decision.WAIT;
        var intent = plugin.store().coreTake(player.getUniqueId(), slot);
        if (intent == null) return InventoryTransfer.Decision.APPLIED;
        var entry = plugin.store().entry(player.getUniqueId(), slot);
        var transfer = new InventoryTransfer.Intent(intent.transaction(), player.getUniqueId(), intent.before(), intent.after());
        var result = InventoryTransfer.recover(transfer, ChargeInventory.inventory(player, WITNESS,
                () -> plugin.coreApi() != null && plugin.coreApi().isAuthenticated(player)));
        if (result != InventoryTransfer.Decision.APPLIED) return result;
        boolean saved = plugin.store().finishCoreTake(player.getUniqueId(), slot, intent.transaction(), System.currentTimeMillis());
        // Предмет уже устойчиво сохранён с nonce: старый блок более не является ядром.
        plugin.worldListener().clearCore(player, entry);
        return saved ? InventoryTransfer.Decision.APPLIED : InventoryTransfer.Decision.WAIT;
    }
}
