package ru.cherdakmd.chrdkfishing.bukkit;

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
import ru.cherdakmd.chrdkfishing.RodUpgradeJournal;

/** Read-only selector and two-step confirmation, never stores real items. */
final class RodUpgradeGui implements Listener {
    private final Plugin plugin;
    private final RodUpgradeEngine engine;
    private final RodItemCodec codec;
    private java.util.function.Consumer<Player> back;
    void setBack(java.util.function.Consumer<Player> back) { this.back = java.util.Objects.requireNonNull(back); }
    RodUpgradeGui(Plugin plugin, RodUpgradeEngine engine, RodItemCodec codec) {
        this.plugin = java.util.Objects.requireNonNull(plugin);
        this.engine = java.util.Objects.requireNonNull(engine);
        this.codec = java.util.Objects.requireNonNull(codec);
    }
    private static final class Holder implements InventoryHolder {
        final UUID owner;
        Inventory inventory;
        RodUpgradePlanner.Plan quote;
        Holder(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
    void open(Player player) throws Exception {
        if (!engine.available(player)) throw new IllegalStateException("player unavailable");
        UUID owner = engine.pendingOwner();
        if (owner != null) {
            if (!owner.equals(player.getUniqueId()) || engine.recover(player) != RodUpgradeJournal.Result.DONE) {
                player.sendMessage("§cОжидается восстановление предыдущего улучшения. Обратитесь к администрации."); return;
            }
        }
        Holder holder = new Holder(player.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text("Улучшение удочки"));
        render(holder, player); player.openInventory(holder.inventory);
    }
    private void render(Holder holder, Player player) {
        holder.inventory.clear();
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int i = 0; i < Math.min(36, storage.length); i++) {
            var item = storage[i];
            if (item == null || item.getType() != Material.FISHING_ROD || item.getAmount() != 1) continue;
            var token = codec.read(item);
            String level = token.map(t -> Integer.toString(t.level())).orElse(codec.marked(item) ? "повреждена" : "1");
            holder.inventory.setItem(i, icon(Material.PAPER, "Удочка: ячейка " + (i + 1) + ", уровень " + level));
        }
        if (holder.quote != null) {
            var cost = holder.quote.cost();
            var button = icon(Material.EMERALD_BLOCK, "Подтвердить уровень " + cost.targetLevel());
            var meta = button.getItemMeta();
            var lore = new java.util.ArrayList<Component>();
            lore.add(Component.text("Цена: " + cost.minor() / 100 + "." + String.format(java.util.Locale.ROOT, "%02d", cost.minor() % 100) + " алм.", NamedTextColor.GRAY));
            for (var ingredient : cost.ingredients()) lore.add(Component.text(ingredient.material().name() + " × " + ingredient.count(), NamedTextColor.GRAY));
            lore.add(Component.text("Удочка и ресурсы остаются в инвентаре до подтверждения.", NamedTextColor.YELLOW));
            meta.lore(lore); button.setItemMeta(meta); holder.inventory.setItem(49, button);
        }
        if (back != null) holder.inventory.setItem(52, icon(Material.ARROW, "Назад"));
        holder.inventory.setItem(53, icon(Material.BARRIER, "Закрыть"));
    }
    private static ItemStack icon(Material material, String label) {
        var item = new ItemStack(material); var meta = item.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.YELLOW)); item.setItemMeta(meta); return item;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId()) || !engine.available(player)) return;
        int slot = event.getRawSlot();
        if (slot >= 0 && slot < 36) {
            try { holder.quote = engine.quote(player, slot); render(holder, player); }
            catch (IllegalArgumentException error) { holder.quote = null; render(holder, player); player.sendMessage("§cНе удалось подготовить улучшение: проверьте удочку и материалы."); }
            catch (Exception error) { report(player, error); }
        } else if (slot == 49 && holder.quote != null) {
            var plan = holder.quote; holder.quote = null;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!engine.available(player) || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
                player.closeInventory();
                try {
                    var result = engine.confirm(player, plan);
                    if (result == RodUpgradeJournal.Result.DONE) player.sendMessage("§aУдочка улучшена до уровня " + plan.newToken().level() + ".");
                    else if (result == RodUpgradeJournal.Result.WAIT) player.sendMessage("§eУлучшение ожидает восстановления. Не меняйте инвентарь; обратитесь к администрации.");
                    else player.sendMessage("§cУлучшение не подтверждено: проверьте инвентарь и состояние банка.");
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
        plugin.getLogger().log(java.util.logging.Level.SEVERE, "Ошибка улучшения удочки; сохраните журнал и банковые данные", error);
        player.sendMessage("§cУлучшение остановлено; обратитесь к администрации.");
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
}
