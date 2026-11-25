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
import ru.cherdakmd.chrdkfishing.BaitToken;
import ru.cherdakmd.chrdkfishing.RodUpgradeJournal;

/** Select a purchased bait from player storage; confirmation equips it in the server journal. */
final class BaitEquipGui implements Listener {
    private final Plugin plugin;
    private final BaitPurchaseEngine engine;
    private final BaitItemCodec codec;
    private final Consumer<Player> back;
    private static final class Holder implements InventoryHolder {
        final UUID owner; Inventory inventory; int selected = -1; BaitToken.Token token;
        Holder(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
    BaitEquipGui(Plugin plugin, BaitPurchaseEngine engine, BaitItemCodec codec, Consumer<Player> back) {
        this.plugin = Objects.requireNonNull(plugin); this.engine = Objects.requireNonNull(engine);
        this.codec = Objects.requireNonNull(codec); this.back = Objects.requireNonNull(back);
    }
    void open(Player player) throws Exception {
        if (!engine.available(player)) throw new IllegalStateException("player unavailable");
        var result = engine.recoverEquip(player);
        if (result != RodUpgradeJournal.Result.DONE) {
            player.sendMessage("§cПриманка ожидает восстановления. Обратитесь к администрации."); return;
        }
        var equipped = engine.equipped(player);
        if (equipped != null) {
            player.sendMessage("§eУже выбрана приманка: " + BaitCatalog.byId(equipped.kind()).name()); return;
        }
        var holder = new Holder(player.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text("Выбор приманки"));
        render(holder, player); player.openInventory(holder.inventory);
    }
    private void render(Holder holder, Player player) {
        holder.inventory.clear(); var storage = player.getInventory().getStorageContents();
        for (int i = 0; i < Math.min(36, storage.length); i++) {
            var token = codec.read(storage[i]); if (token.isEmpty()) continue;
            var bait = BaitCatalog.byId(token.get().kind());
            holder.inventory.setItem(i, FishMainGui.icon(i == holder.selected ? Material.LIME_STAINED_GLASS_PANE : Material.PAPER,
                    bait.name(), FishingText.baitEffect(bait), "Выбрать приманку из ячейки " + (i + 1)));
        }
        holder.inventory.setItem(45, FishMainGui.icon(Material.BOOK, "Как выбрать приманку", "Нажмите на приманку выше, затем подтвердите.", "Можно выбрать только одну; она расходуется при успешной ловле."));
        if (holder.token != null) holder.inventory.setItem(49, FishMainGui.icon(Material.EMERALD_BLOCK,
                "Подтвердить приманку", "Выбрано: " + BaitCatalog.byId(holder.token.kind()).name(), "Предмет будет выбран до успешной ловли."));
        holder.inventory.setItem(52, FishMainGui.icon(Material.ARROW, "Назад", "Главное меню рыбалки"));
        holder.inventory.setItem(53, FishMainGui.icon(Material.BARRIER, "Закрыть", "Ничего не выбирать"));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId()) || !engine.available(player)) return;
        int slot = event.getRawSlot();
        if (slot >= 0 && slot < 36) {
            var token = codec.read(player.getInventory().getStorageContents()[slot]);
            holder.selected = token.isPresent() ? slot : -1; holder.token = token.orElse(null); render(holder, player);
        } else if (slot == 49 && holder.token != null) {
            int chosen = holder.selected; var expected = holder.token; holder.token = null;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!engine.available(player) || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
                player.closeInventory();
                try {
                    if (!codec.read(player.getInventory().getStorageContents()[chosen]).filter(expected::equals).isPresent()) {
                        player.sendMessage("§cПредмет изменился; выберите приманку снова."); return;
                    }
                    var result = engine.equip(player, chosen);
                    if (result == RodUpgradeJournal.Result.DONE) player.sendMessage("§aПриманка выбрана; она сработает при успешной ловле.");
                    else if (result == RodUpgradeJournal.Result.WAIT) player.sendMessage("§eПриманка ожидает восстановления. Обратитесь к администрации.");
                    else player.sendMessage("§cПриманка недоступна или уже использована.");
                } catch (Exception error) { report(player, error); }
            });
        } else if (slot == 52 || slot == 53) Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
            if (slot == 52) back.accept(player); else player.closeInventory();
        });
    }
    private void report(Player player, Exception error) {
        plugin.getLogger().log(java.util.logging.Level.SEVERE, "Ошибка выбора приманки; сохраните журнал", error);
        player.sendMessage("§cПриманка не выбрана; обратитесь к администрации.");
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
}
