package ru.cherdakmd.chrdkfishing.bukkit;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkfishing.FishAlbum;
import ru.cherdakmd.chrdkfishing.FishCatalog;

/** Read-only album: rendered items are never real fish and cannot be taken. */
final class FishAlbumGui implements Listener {
    private final FishAlbum album;
    private org.bukkit.plugin.Plugin plugin;
    private AlbumRewards rewards;
    private java.util.function.Consumer<Player> back;
    FishAlbumGui(FishAlbum album) { this.album = java.util.Objects.requireNonNull(album); }
    void setBack(org.bukkit.plugin.Plugin plugin, java.util.function.Consumer<Player> back) {
        this.plugin = java.util.Objects.requireNonNull(plugin); this.back = java.util.Objects.requireNonNull(back);
    }
    void setRewards(AlbumRewards rewards) { this.rewards = java.util.Objects.requireNonNull(rewards); }
    static final class Holder implements InventoryHolder {
        private final java.util.UUID owner;
        private Inventory inventory;
        Holder(java.util.UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
    void open(Player player) throws SQLException {
        var records = album.records(player.getUniqueId());
        var holder = new Holder(player.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text("Альбом рыбака"));
        int slot = 0;
        for (var species : FishCatalog.all()) {
            var entry = records.get(species.id());
            var icon = new ItemStack(entry == null ? Material.PAPER : Material.COD);
            var meta = icon.getItemMeta();
            meta.displayName(Component.text(entry == null ? "Не поймано" : species.name(),
                    entry == null ? NamedTextColor.GRAY : NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            if (entry == null) lore.add(Component.text("Поймайте рыбу, чтобы открыть запись.", NamedTextColor.GRAY));
            else {
                lore.add(Component.text("Поймано: " + entry.count(), NamedTextColor.GRAY));
                lore.add(Component.text("Рекорд: " + entry.longestCm() + " см", NamedTextColor.GRAY));
                lore.add(Component.text("Лучший размер: " + size(entry.bestSize()), NamedTextColor.GRAY));
            }
            meta.lore(lore); icon.setItemMeta(meta); holder.inventory.setItem(slot++, icon);
        }
        if (rewards != null) {
            try {
                var progress = rewards.progress(player);
                int eligible = ru.cherdakmd.chrdkfishing.AlbumRewardJournal.eligibleStage(records.size());
                String text = "Открыто " + records.size() + "/30; этап " + progress.paidStage() + "/4";
                holder.inventory.setItem(48, FishMainGui.icon(Material.GOLD_INGOT, "Награды альбома",
                        eligible > progress.paidStage() ? text + ". Нажмите, чтобы получить." : text));
            } catch (Exception error) { throw new SQLException("album reward state unavailable", error); }
        }
        if (back != null) holder.inventory.setItem(52, FishMainGui.icon(Material.ARROW, "Назад", "Главное меню рыбалки"));
        player.openInventory(holder.inventory);
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())) return;
        if (event.getRawSlot() == 48 && rewards != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
                try {
                    var before = rewards.progress(player);
                    var result = rewards.claim(player);
                    var after = rewards.progress(player);
                    if (result == ru.cherdakmd.chrdkfishing.AlbumRewardJournal.Result.DONE) {
                        player.sendMessage(after.paidStage() > before.paidStage() ? "§aНаграда альбома зачислена в банк." : "§eПока нет новой награды альбома.");
                        open(player);
                    } else player.sendMessage("§cНаграда ожидает сверки или восстановления. Обратитесь к администрации.");
                } catch (Exception error) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "Ошибка награды альбома; сохраните журнал и банк", error);
                    player.sendMessage("§cНаграда недоступна. Обратитесь к администрации.");
                }
            });
        }
        if (event.getRawSlot() == 52 && back != null)
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == holder) back.accept(player);
            });
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        var top = event.getView().getTopInventory();
        if (top.getHolder() instanceof Holder && event.getRawSlots().stream().anyMatch(slot -> slot < top.getSize())) event.setCancelled(true);
    }
    private static String size(ru.cherdakmd.chrdkfishing.FishingModel.Size size) {
        return switch (size) { case SMALL -> "маленькая"; case NORMAL -> "обычная"; case LARGE -> "крупная"; case TROPHY -> "трофейная"; };
    }
}
