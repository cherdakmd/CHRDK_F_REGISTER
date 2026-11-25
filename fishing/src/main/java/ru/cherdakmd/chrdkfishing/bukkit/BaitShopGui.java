package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import ru.cherdakmd.chrdkfishing.BaitCatalog;
import ru.cherdakmd.chrdkfishing.RodUpgradeJournal;

/** Unregistered until bait consumption is implemented; prices and effects are provisional. */
final class BaitShopGui implements Listener {
    private final Plugin plugin;
    private final BaitPurchaseEngine engine;
    private final Consumer<Player> back;
    private static final class Holder implements InventoryHolder {
        final UUID owner; Inventory inventory; BaitPurchasePlanner.Plan quote;
        Holder(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
    BaitShopGui(Plugin plugin, BaitPurchaseEngine engine, Consumer<Player> back) {
        this.plugin = Objects.requireNonNull(plugin); this.engine = Objects.requireNonNull(engine); this.back = Objects.requireNonNull(back);
    }
    void open(Player player) throws Exception {
        if (!engine.available(player)) throw new IllegalStateException("player unavailable");
        UUID owner = engine.pendingOwner();
        if (owner != null && (!owner.equals(player.getUniqueId()) || engine.recover(player) != RodUpgradeJournal.Result.DONE)) {
            player.sendMessage("§cПредыдущая покупка ожидает восстановления. Обратитесь к администрации."); return;
        }
        var holder = new Holder(player.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, 27, Component.text("Магазин приманок"));
        render(holder); player.openInventory(holder.inventory);
    }
    private void render(Holder holder) {
        holder.inventory.clear();
        for (int i = 0; i < BaitCatalog.all().size(); i++) {
            var bait = BaitCatalog.all().get(i);
            holder.inventory.setItem(10 + i * 2, FishMainGui.icon(Material.PAPER, bait.name(),
                    "Цена: " + amount(bait.priceMinor()) + " алм. со счёта /bal.", FishingText.baitEffect(bait), "Одна приманка на успешную ловлю. Нажмите для выбора."));
        }
        if (holder.quote != null) holder.inventory.setItem(22, FishMainGui.icon(Material.EMERALD_BLOCK,
                "Подтвердить покупку", "Выбрано: " + BaitCatalog.byId(holder.quote.token().kind()).name(), "Списать " + amount(holder.quote.minor()) + " алм. с /bal; нужно свободное место."));
        holder.inventory.setItem(25, FishMainGui.icon(Material.ARROW, "Назад", "Главное меню рыбалки"));
        holder.inventory.setItem(26, FishMainGui.icon(Material.BARRIER, "Закрыть", "Ничего не покупать"));
    }
    private static String amount(long minor) { return minor / 100 + "." + String.format(java.util.Locale.ROOT, "%02d", minor % 100); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId()) || !engine.available(player)) return;
        int slot = event.getRawSlot();
        if (slot == 10 || slot == 12 || slot == 14) {
            try { holder.quote = engine.quote(player, BaitCatalog.all().get((slot - 10) / 2).id()); render(holder); }
            catch (IllegalArgumentException invalid) { holder.quote = null; render(holder); player.sendMessage("§cОсвободите место в инвентаре."); }
            catch (Exception error) { holder.quote = null; render(holder); report(player, error); }
        } else if (slot == 22 && holder.quote != null) {
            var quote = holder.quote; holder.quote = null;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!engine.available(player) || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
                player.closeInventory();
                try {
                    var result = engine.confirm(player, quote);
                    if (result == RodUpgradeJournal.Result.DONE) player.sendMessage("§aПриманка куплена.");
                    else if (result == RodUpgradeJournal.Result.WAIT) player.sendMessage("§eПокупка не завершена; проверьте состояние и обратитесь к администрации.");
                    else player.sendMessage("§cПокупка не подтверждена: инвентарь или банк изменились.");
                } catch (Exception error) { report(player, error); }
            });
        } else if (slot == 25 || slot == 26) Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
            if (slot == 25) back.accept(player); else player.closeInventory();
        });
    }
    private void report(Player player, Exception error) {
        plugin.getLogger().log(java.util.logging.Level.SEVERE, "Ошибка покупки приманки; сохраните данные журнала и банка", error);
        player.sendMessage("§cПокупка остановлена; обратитесь к администрации.");
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
}
