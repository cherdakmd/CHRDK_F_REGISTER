package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
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

/** Simple read-only cooking selector; the real items remain in player storage. */
final class FishCookingGui implements Listener {
    private final Plugin plugin;
    private final FishSaleEngine engine;
    private final FishItemCodec codec;
    private Consumer<Player> back;
    private static final class Holder implements InventoryHolder {
        final UUID owner; Inventory inventory; FishCookingPlanner.Plan quote;
        FishCookingPlanner.Recipe recipe = FishCookingPlanner.Recipe.FRIED;
        Holder(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
    FishCookingGui(Plugin plugin, FishSaleEngine engine, FishItemCodec codec) {
        this.plugin = Objects.requireNonNull(plugin); this.engine = Objects.requireNonNull(engine); this.codec = Objects.requireNonNull(codec);
    }
    void setBack(Consumer<Player> back) { this.back = Objects.requireNonNull(back); }
    void open(Player player) throws Exception {
        if (!engine.available(player)) throw new IllegalStateException("player unavailable");
        if (engine.recover(player) != FishSaleJournal.Result.DONE) {
            player.sendMessage("§cПредыдущая операция с рыбой ожидает восстановления. Обратитесь к администрации."); return;
        }
        var holder = new Holder(player.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text("Готовка рыбы"));
        render(holder, player); player.openInventory(holder.inventory);
    }
    private void render(Holder holder, Player player) {
        holder.inventory.clear();
        var storage = player.getInventory().getStorageContents();
        for (int i = 0; i < Math.min(storage.length, 36); i++) {
            var token = codec.read(storage[i]); if (token.isEmpty()) continue;
            var fish = token.get();
            holder.inventory.setItem(i, FishMainGui.icon(Material.PAPER, FishCatalog.byId(fish.fish().speciesId()).name(),
                    "Ячейка " + (i + 1) + ": выбрать для готовки"));
        }
        for (int i = 0; i < FishCookingPlanner.Recipe.values().length; i++) {
            var recipe = FishCookingPlanner.Recipe.values()[i];
            holder.inventory.setItem(39 + i, FishMainGui.icon(recipe.material(), recipe.title(),
                    (recipe == holder.recipe ? "Выбрано. " : "Выбрать. ") + (recipe.needsBowl() ? "Нужна миска." : "Одна рыба.")));
        }
        holder.inventory.setItem(45, FishMainGui.icon(Material.BOOK, "Как готовить", "Выберите рецепт ниже, затем рыбу выше.", "Для ухи нужна обычная миска в инвентаре.", "После выбора подтвердите: рыба будет израсходована."));
        if (holder.quote != null) {
            var item = FishMainGui.icon(holder.quote.recipe().material(), "Подтвердить: " + holder.quote.recipe().title(), "Одна рыба → одно блюдо");
            var meta = item.getItemMeta(); meta.lore(java.util.List.of(
                    Component.text("Выбранная рыба будет израсходована.", NamedTextColor.GRAY),
                    Component.text("Блюдо заменит её в том же слоте.", NamedTextColor.GRAY)));
            item.setItemMeta(meta); holder.inventory.setItem(49, item);
        }
        if (back != null) holder.inventory.setItem(52, FishMainGui.icon(Material.ARROW, "Назад", "Главное меню рыбалки"));
        holder.inventory.setItem(53, FishMainGui.icon(Material.BARRIER, "Закрыть", "Ничего не готовить"));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId()) || !engine.available(player)) return;
        int slot = event.getRawSlot();
        if (slot >= 0 && slot < 36) {
            if (codec.read(player.getInventory().getStorageContents()[slot]).isEmpty()) return;
            holder.quote = null;
            try { holder.quote = engine.quoteCook(player, slot, holder.recipe); render(holder, player); }
            catch (IllegalArgumentException invalid) { render(holder, player); player.sendMessage("§cПроверьте рыбу и ингредиенты; для ухи нужна обычная миска."); }
            catch (Exception error) { report(player, error); }
        } else if (slot >= 39 && slot <= 41) {
            holder.recipe = FishCookingPlanner.Recipe.values()[slot - 39]; holder.quote = null; render(holder, player);
        } else if (slot == 49 && holder.quote != null) {
            var quote = holder.quote; holder.quote = null;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!engine.available(player) || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
                player.closeInventory();
                try {
                    var result = engine.confirmCook(player, quote);
                    if (result == FishSaleJournal.Result.DONE) player.sendMessage("§aРыба приготовлена.");
                    else if (result == FishSaleJournal.Result.WAIT) player.sendMessage("§eГотовка ожидает восстановления. Обратитесь к администрации.");
                    else player.sendMessage("§cГотовка не подтверждена: рыба или инвентарь изменились.");
                } catch (Exception error) { report(player, error); }
            });
        } else if (slot == 52 && back != null) Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == holder) back.accept(player);
        });
        else if (slot == 53) Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == holder) player.closeInventory();
        });
    }
    private void report(Player player, Exception error) {
        plugin.getLogger().log(java.util.logging.Level.SEVERE, "Ошибка готовки; сохраните журнал рыбных операций и данные игрока", error);
        player.sendMessage("§cГотовка остановлена; обратитесь к администрации.");
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
}
