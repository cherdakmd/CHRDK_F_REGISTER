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
        private int page;
        Holder(java.util.UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
    void open(Player player) throws SQLException {
        var holder = new Holder(player.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text("Альбом рыбака"));
        render(holder, player); player.openInventory(holder.inventory);
    }
    static final int PAGE_SIZE = 45;
    static int pageCount() { return Math.max(1, (FishCatalog.all().size() + PAGE_SIZE - 1) / PAGE_SIZE); }
    static List<ru.cherdakmd.chrdkfishing.FishingModel.Species> page(int page) {
        if (page < 0 || page >= pageCount()) throw new IllegalArgumentException("album page");
        var all = FishCatalog.all(); int start = page * PAGE_SIZE;
        return all.subList(start, Math.min(start + PAGE_SIZE, all.size()));
    }
    private void render(Holder holder, Player player) throws SQLException {
        var records = album.records(player.getUniqueId());
        boolean visuals = plugin != null && plugin.getConfig().getBoolean("textures.album-menu", false);
        holder.inventory.clear(); int slot = 0;
        for (var species : page(holder.page)) {
            var entry = records.get(species.id());
            var icon = new ItemStack(entry == null ? Material.PAPER : Material.COD);
            var meta = icon.getItemMeta();
            FishAlbumVisuals.entry(meta, species.id(), entry != null, visuals);
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
                String text = "Открыто " + records.size() + "/" + FishCatalog.all().size() + "; этап " + progress.paidStage() + "/4";
                boolean available = eligible > progress.paidStage();
                holder.inventory.setItem(48, button(available ? FishAlbumVisuals.Icon.REWARD_AVAILABLE : FishAlbumVisuals.Icon.REWARD_WAITING,
                        visuals, Material.GOLD_INGOT, "Награды альбома",
                        text, "Этапы: 5, 10, 20 и 30 видов.", "Суммарно: 0.50 / 1.50 / 3.50 / 8.50 алм. на /bal.",
                        available ? "Нажмите, чтобы получить доступную награду." : "Новой награды пока нет."));
            } catch (Exception error) { throw new SQLException("album reward state unavailable", error); }
        }
        if (holder.page > 0) holder.inventory.setItem(45, button(FishAlbumVisuals.Icon.PREVIOUS, visuals, Material.ARROW, "Предыдущая страница", "Страница " + holder.page + "/" + pageCount()));
        if (holder.page + 1 < pageCount()) holder.inventory.setItem(50, button(FishAlbumVisuals.Icon.NEXT, visuals, Material.ARROW, "Следующая страница", "Страница " + (holder.page + 2) + "/" + pageCount()));
        holder.inventory.setItem(49, button(FishAlbumVisuals.Icon.PAGE, visuals, Material.BOOK, "Альбом · страница " + (holder.page + 1) + "/" + pageCount(), "Записи личных уловов. Нажмите стрелки для перехода."));
        if (back != null) holder.inventory.setItem(52, button(FishAlbumVisuals.Icon.BACK, visuals, Material.ARROW, "Назад", "Главное меню рыбалки"));
    }
    private static ItemStack button(FishAlbumVisuals.Icon visual, boolean enabled, Material material, String name, String... lore) {
        var item = FishMainGui.icon(material, name, lore);
        if (enabled) {
            var meta = item.getItemMeta();
            FishAlbumVisuals.button(meta, visual, true);
            if (meta != null) item.setItemMeta(meta);
        }
        return item;
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())) return;
        int slot = event.getRawSlot();
        if ((slot == 45 && holder.page > 0) || (slot == 50 && holder.page + 1 < pageCount())) {
            try { holder.page += slot == 45 ? -1 : 1; render(holder, player); }
            catch (SQLException error) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Ошибка чтения альбома", error);
                player.sendMessage("§cАльбом недоступен. Обратитесь к администрации.");
            }
            return;
        }
        if (slot == 48 && rewards != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
                try {
                    var before = rewards.progress(player);
                    var result = rewards.claim(player);
                    var after = rewards.progress(player);
                    if (result == ru.cherdakmd.chrdkfishing.AlbumRewardJournal.Result.DONE) {
                        player.sendMessage(after.paidStage() > before.paidStage() ? "§aНаграда альбома зачислена в банк." : "§eПока нет новой награды альбома.");
                        render(holder, player);
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
