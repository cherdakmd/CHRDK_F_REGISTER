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
import ru.cherdakmd.chrdkfishing.FishCatalog;
import ru.cherdakmd.chrdkfishing.FishSaleJournal;
import ru.cherdakmd.chrdkfishing.FishingOrders;

/** Read-only daily orders and explicit delivery confirmation. */
final class FishOrdersGui implements Listener {
    private final Plugin plugin;
    private final FishSaleEngine engine;
    private final FishItemCodec codec;
    private final Consumer<Player> back;
    private static final class Holder implements InventoryHolder {
        final UUID owner; final long day; Inventory inventory;
        FishingOrders.Order selected; FishSalePlanner.Plan quote;
        Holder(UUID owner, long day) { this.owner = owner; this.day = day; }
        @Override public Inventory getInventory() { return inventory; }
    }
    FishOrdersGui(Plugin plugin, FishSaleEngine engine, FishItemCodec codec, Consumer<Player> back) {
        this.plugin = Objects.requireNonNull(plugin); this.engine = Objects.requireNonNull(engine);
        this.codec = Objects.requireNonNull(codec); this.back = Objects.requireNonNull(back);
    }
    void open(Player player) throws Exception {
        if (!engine.available(player)) throw new IllegalStateException("player unavailable");
        if (engine.recover(player) != FishSaleJournal.Result.DONE) {
            player.sendMessage("§cПредыдущая операция ожидает восстановления. Обратитесь к администрации."); return;
        }
        var holder = new Holder(player.getUniqueId(), FishingOrders.utcDay());
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text("Заказы рыбаку"));
        render(holder, player); player.openInventory(holder.inventory);
    }
    private void render(Holder holder, Player player) throws Exception {
        holder.inventory.clear(); var orders = FishingOrders.forDay(holder.day);
        for (int i = 0; i < orders.size(); i++) {
            var order = orders.get(i); var name = FishCatalog.byId(order.speciesId()).name();
            boolean done = engine.orderClaimed(player, order);
            holder.inventory.setItem(36 + i * 2, FishMainGui.icon(done ? Material.GRAY_DYE : Material.BOOK,
                    name + " — " + size(order), done ? "Уже сдано" : "Награда: " + amount(order.rewardMinor()) + " алм."));
        }
        if (holder.selected != null) {
            var contents = player.getInventory().getStorageContents();
            for (int i = 0; i < Math.min(36, contents.length); i++) {
                var token = codec.read(contents[i]);
                if (token.isPresent() && holder.selected.matches(token.get())) holder.inventory.setItem(i,
                        FishMainGui.icon(Material.PAPER, FishCatalog.byId(holder.selected.speciesId()).name(), "Сдать рыбу из ячейки " + (i + 1)));
            }
        }
        holder.inventory.setItem(45, FishMainGui.icon(Material.BOOK, "Как выполнить заказ", "Выберите заказ ниже, затем подходящую рыбу выше.", "Подтверждение расходует рыбу и зачисляет награду в банк.", "Заказы обновляются по UTC; каждый сдаётся один раз."));
        if (holder.quote != null) holder.inventory.setItem(49, FishMainGui.icon(Material.EMERALD_BLOCK,
                "Подтвердить заказ", "Сдать рыбу и получить " + amount(holder.selected.rewardMinor()) + " алм. на /bal."));
        holder.inventory.setItem(52, FishMainGui.icon(Material.ARROW, "Назад", "Главное меню рыбалки"));
        holder.inventory.setItem(53, FishMainGui.icon(Material.BARRIER, "Закрыть", "Ничего не сдавать"));
    }
    private static String amount(long minor) { return minor / 100 + "." + String.format(java.util.Locale.ROOT, "%02d", minor % 100); }
    private static String size(FishingOrders.Order o) {
        return switch (o.size()) { case SMALL -> "маленькая"; case NORMAL -> "обычная"; case LARGE -> "крупная"; case TROPHY -> "трофейная"; };
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId()) || !engine.available(player)) return;
        int slot = event.getRawSlot();
        try {
            if (slot == 36 || slot == 38 || slot == 40) {
                if (holder.day != FishingOrders.utcDay()) { player.sendMessage("§eЗаказы обновились; откройте меню заново."); return; }
                var order = FishingOrders.forDay(holder.day).get((slot - 36) / 2);
                if (engine.orderClaimed(player, order)) { player.sendMessage("§eЭтот заказ уже сдан."); return; }
                holder.selected = order; holder.quote = null; render(holder, player);
            } else if (slot >= 0 && slot < 36 && holder.selected != null) {
                if (codec.read(player.getInventory().getStorageContents()[slot]).filter(holder.selected::matches).isEmpty()) return;
                holder.quote = null;
                holder.quote = engine.quoteOrder(player, slot, holder.selected); render(holder, player);
            } else if (slot == 49 && holder.selected != null && holder.quote != null) {
                var order = holder.selected; var quote = holder.quote; holder.quote = null;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!engine.available(player) || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
                    player.closeInventory();
                    try {
                        var result = engine.confirmOrder(player, quote, order);
                        if (result == FishSaleJournal.Result.DONE) player.sendMessage("§aЗаказ сдан, награда: " + amount(order.rewardMinor()) + " алм.");
                        else if (result == FishSaleJournal.Result.WAIT) player.sendMessage("§eЗаказ ожидает восстановления. Обратитесь к администрации.");
                        else player.sendMessage("§cЗаказ не подтверждён; проверьте рыбу, инвентарь и дату.");
                    } catch (Exception error) { report(player, error); }
                });
            } else if (slot == 52 || slot == 53) Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
                if (slot == 52) back.accept(player); else player.closeInventory();
            });
        } catch (FishSalePlanner.DuplicateFish duplicate) {
            holder.quote = null; holder.inventory.setItem(49, null);
            player.sendMessage("§cОбнаружены копии одной рыбы в инвентаре. Обратитесь к администрации.");
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            holder.quote = null; holder.inventory.setItem(49, null);
            player.sendMessage("§cРыба не подходит или заказ сейчас недоступен.");
        } catch (Exception error) { holder.quote = null; holder.inventory.setItem(49, null); report(player, error); }
    }
    private void report(Player player, Exception error) {
        plugin.getLogger().log(java.util.logging.Level.SEVERE, "Ошибка рыбного заказа; сохраните журнал и банк", error);
        player.sendMessage("§cЗаказ остановлен; обратитесь к администрации.");
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
}
