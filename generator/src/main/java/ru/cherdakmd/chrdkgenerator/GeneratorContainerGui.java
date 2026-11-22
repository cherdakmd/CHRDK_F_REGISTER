package ru.cherdakmd.chrdkgenerator;

import java.io.IOException;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.api.InventoryTransfer;

/** Сундуковое окно: все изменения предметов — только через журнал и playerdata witness. */
final class GeneratorContainerGui implements Listener {
    interface Access {
        /** Проверяет связь файлов, восстанавливает незавершённый перенос и миграцию. */
        boolean prepare(Player player, int slot) throws IOException;
        void settings(Player player, int slot);
    }
    private static final class Holder implements InventoryHolder {
        final UUID owner;
        final UUID core;
        final int slot;
        Inventory inventory;
        Holder(UUID owner, UUID core, int slot) { this.owner = owner; this.core = core; this.slot = slot; }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final GeneratorPlugin plugin;
    private final GeneratorContainerStore store;
    private final GeneratorContainerTransfer transfer;
    private final Access access;
    GeneratorContainerGui(GeneratorPlugin plugin, GeneratorContainerStore store,
                          GeneratorContainerTransfer transfer, Access access) {
        this.plugin = plugin; this.store = store; this.transfer = transfer; this.access = access;
    }

    void open(Player player, int slot) {
        if (!near(player, slot)) return;
        try {
            if (!access.prepare(player, slot)) { plugin.messages().send(player, "container-unavailable"); return; }
            var view = store.view(player.getUniqueId(), slot);
            if (view == null || view.pending()) { plugin.messages().send(player, "container-unavailable"); return; }
            var holder = new Holder(player.getUniqueId(), view.core(), slot);
            holder.inventory = Bukkit.createInventory(holder, 54, plugin.messages().get("container-title"));
            render(holder, view);
            player.openInventory(holder.inventory);
        } catch (IOException | RuntimeException error) {
            plugin.getLogger().warning("Бункер ожидает восстановления: " + error.getMessage());
            plugin.messages().send(player, "container-unavailable");
        }
    }

    private boolean near(Player player, int slot) {
        var entry = plugin.store().entry(player.getUniqueId(), slot);
        return entry != null && entry.installed() && plugin.nearCore(player, entry)
                && player.hasPermission("chrdkgenerator.use");
    }

    private void render(Holder holder, GeneratorContainerStore.View view) {
        holder.inventory.clear();
        for (int i = 0; i < GeneratorContainerStore.SLOTS; i++) {
            var stack = view.slots().get(i);
            if (stack == null) continue;
            Material material = Material.matchMaterial(stack.material());
            if (material == null || !material.isItem() || material.isAir() || stack.amount() > material.getMaxStackSize())
                throw new IllegalStateException("invalid container material " + stack.material());
            holder.inventory.setItem(i, new ItemStack(material, stack.amount()));
        }
        holder.inventory.setItem(45, button(Material.HOPPER, "container-refresh"));
        holder.inventory.setItem(49, button(Material.COMPARATOR, "container-settings"));
        holder.inventory.setItem(53, button(Material.BARRIER, "container-close"));
    }
    private ItemStack button(Material material, String key) {
        var item = new ItemStack(material); var meta = item.getItemMeta();
        meta.displayName(plugin.messages().get(key)); item.setItemMeta(meta); return item;
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())
                || !near(player, holder.slot)) { event.getWhoClicked().closeInventory(); return; }
        try {
            var entry = plugin.store().entry(holder.owner, holder.slot);
            if (!holder.core.equals(entry.coreItem()) || !access.prepare(player, holder.slot)) {
                player.closeInventory(); plugin.messages().send(player, "container-unavailable"); return;
            }
            int raw = event.getRawSlot();
            var type = event.getClick();
            if (raw >= 45 && raw < 54 && (type == ClickType.LEFT || type == ClickType.RIGHT)) {
                if (raw == 53) { player.closeInventory(); return; }
                if (raw == 49) { access.settings(player, holder.slot); return; }
                if (raw == 45) { open(player, holder.slot); return; }
            }
            var beforeClick = store.view(holder.owner, holder.slot);
            if (raw >= 0 && raw < GeneratorContainerStore.SLOTS) {
                var shown = holder.inventory.getItem(raw);
                var actual = beforeClick.slots().get(raw);
                boolean same = actual == null ? shown == null || shown.getType().isAir()
                        : shown != null && shown.getType().name().equals(actual.material()) && shown.getAmount() == actual.amount();
                if (!same) { render(holder, beforeClick); return; }
            }
            InventoryTransfer.Decision result = null;
            if (raw >= 0 && raw < GeneratorContainerStore.SLOTS
                    && (type == ClickType.LEFT || type == ClickType.RIGHT || event.isShiftClick())) {
                if (store.view(holder.owner, holder.slot).slots().get(raw) != null)
                    result = transfer.withdraw(player, holder.slot, raw);
            } else if (raw >= 54 && event.isShiftClick() && event.getSlot() >= 0 && event.getSlot() < 36) {
                var fuel = GeneratorFurnaceFuel.inspect(player.getInventory().getItem(event.getSlot()));
                if (fuel.isPresent()) {
                    var view = store.view(holder.owner, holder.slot);
                    int target = -1;
                    for (int i = GeneratorContainerStore.RESOURCE_SLOTS; i < GeneratorContainerStore.SLOTS; i++) {
                        var stack = view.slots().get(i);
                        if (stack != null && stack.material().equals(fuel.get().material())
                                && stack.amount() < player.getInventory().getItem(event.getSlot()).getMaxStackSize()) { target = i; break; }
                    }
                    if (target < 0) for (int i = GeneratorContainerStore.RESOURCE_SLOTS; i < GeneratorContainerStore.SLOTS; i++) {
                        if (view.slots().get(i) == null) { target = i; break; }
                    }
                    if (target >= 0) result = transfer.deposit(player, holder.slot, event.getSlot(), target);
                }
                if (result == null) plugin.messages().send(player, "container-fuel-rejected");
            }
            if (result != null && result != InventoryTransfer.Decision.APPLIED) {
                player.closeInventory(); plugin.messages().send(player, "container-transfer-failed"); return;
            }
            var latest = store.view(holder.owner, holder.slot);
            if (latest == null || latest.pending()) { player.closeInventory(); return; }
            render(holder, latest);
        } catch (IOException | RuntimeException error) {
            player.closeInventory();
            plugin.getLogger().warning("Бункер ожидает восстановления: " + error.getMessage());
            plugin.messages().send(player, "container-unavailable");
        }
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
}
