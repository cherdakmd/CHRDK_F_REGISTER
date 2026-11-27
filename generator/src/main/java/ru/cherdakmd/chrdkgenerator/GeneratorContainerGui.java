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
        final boolean fuel;
        Inventory inventory;
        Holder(UUID owner, UUID core, int slot, boolean fuel) { this.owner = owner; this.core = core; this.slot = slot; this.fuel = fuel; }
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

    void open(Player player, int slot) { open(player, slot, false); }
    private void open(Player player, int slot, boolean fuel) {
        if (!near(player, slot)) return;
        try {
            if (!access.prepare(player, slot)) { plugin.messages().send(player, "container-unavailable"); return; }
            var view = store.view(player.getUniqueId(), slot);
            if (view == null || view.pending()) { plugin.messages().send(player, "container-unavailable"); return; }
            var holder = new Holder(player.getUniqueId(), view.core(), slot, fuel);
            holder.inventory = Bukkit.createInventory(holder, 54, plugin.messages().get(fuel ? "fuel-menu-title" : "container-title"));
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
        int count = holder.fuel ? GeneratorContainerStore.FUEL_SLOTS : GeneratorContainerStore.RESOURCE_SLOTS;
        for (int i = 0; i < count; i++) {
            int index = holder.fuel ? i + GeneratorContainerStore.RESOURCE_SLOTS : i;
            var stack = view.slots().get(index);
            if (stack == null) continue;
            Material material = Material.matchMaterial(stack.material());
            if (material == null || !material.isItem() || material.isAir() || stack.amount() > material.getMaxStackSize())
                throw new IllegalStateException("invalid container material " + stack.material());
            var shown = new ItemStack(material, stack.amount());
            if (holder.fuel) {
                var definition=GeneratorFurnaceFuel.inspect(new ItemStack(material));
                var meta=shown.getItemMeta();
                if (definition.isPresent()) {
                    long one=definition.get().burnMillis();
                    meta.lore(java.util.List.of(plugin.messages().get("fuel-item-time", "one", duration(one),
                            "total", duration(Math.multiplyExact(one, stack.amount())))));
                } else meta.lore(java.util.List.of(plugin.messages().get("fuel-item-remainder")));
                shown.setItemMeta(meta);
            }
            holder.inventory.setItem(i, shown);
        }
        if (holder.fuel) {
            holder.inventory.setItem(13,button(Material.PAPER,"fuel-help"));
            var budget=GeneratorFurnaceBurn.consume(view.slots(),view.burnMillis(),Long.MAX_VALUE,
                    GeneratorContainerGui::fuel,GeneratorContainerGui::stackSize);
            var status = new ItemStack(Material.CLOCK); var meta=status.getItemMeta();
            meta.displayName(plugin.messages().get("fuel-total-title", "time", duration(budget.usedMillis())));
            meta.lore(java.util.List.of(plugin.messages().get("fuel-total-lore", "active", duration(view.burnMillis())),
                    plugin.messages().get("fuel-total-conditions")));
            GeneratorMenuVisuals.apply(meta, GeneratorMenuVisuals.Control.FUEL_TOTAL.model(), plugin.getConfig().getBoolean("textures.menu", false));
            status.setItemMeta(meta); holder.inventory.setItem(22,status);
        }
        holder.inventory.setItem(45, button(Material.HOPPER, "container-refresh"));
        holder.inventory.setItem(47, button(holder.fuel ? Material.CHEST : Material.COAL, holder.fuel ? "fuel-back" : "fuel-open"));
        holder.inventory.setItem(49, button(Material.COMPARATOR, "container-settings"));
        holder.inventory.setItem(53, button(Material.BARRIER, "container-close"));
    }
    static String duration(long millis) {
        if (millis < 0) throw new IllegalArgumentException("negative duration");
        long seconds=millis/1000, hours=seconds/3600, minutes=(seconds%3600)/60, secs=seconds%60, ms=millis%1000;
        String fraction=ms==0 ? "" : String.format(java.util.Locale.ROOT,".%03d",ms).replaceFirst("0+$", "");
        return (hours>0?hours+"ч ":"")+(minutes>0?minutes+"м ":"")+secs+fraction+"с";
    }
    private static Integer stackSize(String name) {
        Material m=Material.matchMaterial(name);
        return m==null || !m.isItem() || m.isAir() ? null : m.getMaxStackSize();
    }
    private static GeneratorFurnaceFuel.Fuel fuel(String name) {
        Material m=Material.matchMaterial(name);
        return m==null || !m.isItem() || m.isAir() ? null : GeneratorFurnaceFuel.inspect(new ItemStack(m)).orElse(null);
    }
    private ItemStack button(Material material, String key) {
        var item = new ItemStack(material); var meta = item.getItemMeta();
        meta.displayName(plugin.messages().get(key));
        var visual = GeneratorMenuVisuals.button(key);
        if (visual != null) GeneratorMenuVisuals.apply(meta, visual.model(), plugin.getConfig().getBoolean("textures.menu", false));
        item.setItemMeta(meta); return item;
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        boolean alreadyCancelled=event.isCancelled();
        event.setCancelled(true);
        if (alreadyCancelled) return;
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
                if (raw == 49) { later(player, holder, () -> access.settings(player, holder.slot)); return; }
                if (raw == 45) { later(player, holder, () -> open(player, holder.slot, holder.fuel)); return; }
                if (raw == 47) { later(player, holder, () -> open(player, holder.slot, !holder.fuel)); return; }
            }
            int inputIndex = holder.fuel ? (raw >= 0 && raw < GeneratorContainerStore.FUEL_SLOTS ? raw + GeneratorContainerStore.RESOURCE_SLOTS : -1)
                    : (raw >= 0 && raw < GeneratorContainerStore.RESOURCE_SLOTS ? raw : -1);
            var beforeClick = store.view(holder.owner, holder.slot);
            if (inputIndex >= 0) {
                var shown = holder.inventory.getItem(raw);
                var actual = beforeClick.slots().get(inputIndex);
                boolean same = actual == null ? shown == null || shown.getType().isAir()
                        : shown != null && shown.getType().name().equals(actual.material()) && shown.getAmount() == actual.amount();
                if (!same) { render(holder, beforeClick); return; }
            }
            InventoryTransfer.Decision result = null;
            if (inputIndex >= 0
                    && (type == ClickType.LEFT || type == ClickType.RIGHT || event.isShiftClick())) {
                if (beforeClick.slots().get(inputIndex) != null)
                    result = transfer.withdraw(player, holder.slot, inputIndex);
            } else if (holder.fuel && raw >= 54 && event.isShiftClick() && event.getSlot() >= 0 && event.getSlot() < 36) {
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

    private void later(Player player, Holder holder, Runnable action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && holder.owner.equals(player.getUniqueId()) && near(player,holder.slot)
                    && player.getOpenInventory().getTopInventory().getHolder() == holder) action.run();
        });
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
}
