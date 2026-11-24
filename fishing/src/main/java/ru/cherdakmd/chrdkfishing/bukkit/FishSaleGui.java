package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import ru.cherdakmd.chrdkfishing.FishCatalog;
import ru.cherdakmd.chrdkfishing.FishSaleJournal;

/** Read-only views of inventory slots; real items never enter the menu. */
final class FishSaleGui implements Listener {
    private final Plugin plugin;
    private final FishSaleEngine engine;
    private final FishItemCodec codec;
    private java.util.function.Consumer<Player> back;
    void setBack(java.util.function.Consumer<Player> back) { this.back = java.util.Objects.requireNonNull(back); }
    FishSaleGui(Plugin plugin, FishSaleEngine engine, FishItemCodec codec) {
        this.plugin = java.util.Objects.requireNonNull(plugin);
        this.engine = java.util.Objects.requireNonNull(engine);
        this.codec = java.util.Objects.requireNonNull(codec);
    }
    private static final class Holder implements InventoryHolder {
        final UUID owner;
        final Set<Integer> selected = new LinkedHashSet<>();
        Inventory inventory;
        FishSalePlanner.Plan quote;
        Holder(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
    void open(Player player) throws Exception {
        if (!engine.available(player)) throw new IllegalStateException("player unavailable");
        var pending = engine.recover(player);
        if (pending != FishSaleJournal.Result.DONE) {
            player.sendMessage("§cНезавершённая операция с рыбой: обратитесь к администрации, не меняйте инвентарь.");
            return;
        }
        Holder holder = new Holder(player.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text("Продажа рыбы"));
        render(holder, player);
        player.openInventory(holder.inventory);
    }
    private void render(Holder holder, Player player) {
        holder.inventory.clear();
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int slot = 0; slot < Math.min(36, storage.length); slot++) {
            var token = codec.read(storage[slot]);
            if (token.isEmpty()) continue;
            var fish = token.get();
            var item = new ItemStack(holder.selected.contains(slot) ? Material.LIME_STAINED_GLASS_PANE : Material.PAPER);
            var meta = item.getItemMeta();
            meta.displayName(Component.text(FishCatalog.byId(fish.fish().speciesId()).name(), NamedTextColor.AQUA));
            meta.lore(java.util.List.of(Component.text("Ячейка: " + (slot + 1), NamedTextColor.GRAY),
                    Component.text("Размер: " + sizeName(fish.fish().size()) + ", " + fish.fish().lengthCm() + " см", NamedTextColor.GRAY),
                    Component.text("Цена: " + amount(fish.quotedPrice()) + " алм.", NamedTextColor.GRAY),
                    Component.text(holder.selected.contains(slot) ? "Выбрано" : "Нажмите, чтобы выбрать", NamedTextColor.YELLOW)));
            item.setItemMeta(meta); holder.inventory.setItem(slot, item);
        }
        holder.inventory.setItem(45, icon(Material.BOOK, "Проверить продажу"));
        if (holder.quote != null) holder.inventory.setItem(49, icon(Material.EMERALD_BLOCK,
                "Подтвердить: " + amount(holder.quote.price()) + " алм."));
        if (back != null) holder.inventory.setItem(52, icon(Material.ARROW, "Назад"));
        holder.inventory.setItem(53, icon(Material.BARRIER, "Закрыть"));
    }
    private static ItemStack icon(Material material, String label) {
        var item = new ItemStack(material); var meta = item.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.YELLOW)); item.setItemMeta(meta); return item;
    }
    private static String sizeName(ru.cherdakmd.chrdkfishing.FishingModel.Size size) {
        return switch (size) { case SMALL -> "маленькая"; case NORMAL -> "обычная"; case LARGE -> "крупная"; case TROPHY -> "трофейная"; };
    }
    private static String amount(long minor) { return (minor / 100) + "." + String.format(java.util.Locale.ROOT, "%02d", minor % 100); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())
                || !engine.available(player)) return;
        int slot = event.getRawSlot();
        try {
            if (slot >= 0 && slot < 36) {
                if (codec.read(player.getInventory().getStorageContents()[slot]).isEmpty()) return;
                if (!holder.selected.add(slot)) holder.selected.remove(slot);
                holder.quote = null; render(holder, player);
            } else if (slot == 45) {
                if (holder.selected.isEmpty()) { player.sendMessage("§eСначала выберите рыбу."); return; }
                holder.quote = engine.quote(player, new ArrayList<>(holder.selected));
                render(holder, player);
                player.sendMessage("§eПроверьте сумму и нажмите подтверждение. Рыба пока остаётся в инвентаре.");
            } else if (slot == 49 && holder.quote != null) {
                var quote = holder.quote; holder.quote = null;
                // Bukkit forbids opening/closing inventories inside InventoryClickEvent.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!engine.available(player) || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
                    player.closeInventory();
                    try {
                        var result = engine.confirm(player, quote);
                        if (result == FishSaleJournal.Result.DONE) player.sendMessage("§aРыба продана за " + amount(quote.price()) + " алм.");
                        else if (result == FishSaleJournal.Result.WAIT) player.sendMessage("§eПродажа ожидает восстановления; обратитесь к администрации.");
                        else player.sendMessage("§cПродажа не подтверждена: инвентарь или банковая история изменились.");
                    } catch (Exception error) { report(player, error); }
                });
            } else if (slot == 52 && back != null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == holder) back.accept(player);
                });
            } else if (slot == 53) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == holder) player.closeInventory();
                });
            }
        } catch (Exception error) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == holder) player.closeInventory();
            });
            report(player, error);
        }
    }
    private void report(Player player, Exception error) {
        plugin.getLogger().log(java.util.logging.Level.SEVERE, "Ошибка продажи рыбы; требуется проверка журнала и банка", error);
        player.sendMessage("§cПродажа остановлена; обратитесь к администрации и не удаляйте данные.");
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
}
