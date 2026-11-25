package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
import ru.cherdakmd.chrdkfishing.FishingModel;
import ru.cherdakmd.chrdkfishing.RodUpgradeJournal;

/** Read-only selector and two-step confirmation, never stores real items. */
final class RodUpgradeGui implements Listener {
    private final Plugin plugin;
    private final RodUpgradeEngine engine;
    private final RodItemCodec codec;
    private java.util.function.Consumer<Player> back;
    @FunctionalInterface interface RefreshGuard { boolean safe(Player player) throws Exception; }
    private RefreshGuard refreshGuard = player -> false;
    void setRefreshGuard(RefreshGuard guard) { refreshGuard = java.util.Objects.requireNonNull(guard); }
    void setBack(java.util.function.Consumer<Player> back) { this.back = java.util.Objects.requireNonNull(back); }
    RodUpgradeGui(Plugin plugin, RodUpgradeEngine engine, RodItemCodec codec) {
        this.plugin = java.util.Objects.requireNonNull(plugin);
        this.engine = java.util.Objects.requireNonNull(engine);
        this.codec = java.util.Objects.requireNonNull(codec);
    }
    static final class Holder implements InventoryHolder {
        final UUID owner;
        Inventory inventory;
        int selected = -1;
        RodUpgradePlanner.Plan quote;
        Holder(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
    void open(Player player) throws Exception {
        if (!engine.available(player)) throw new IllegalStateException("player unavailable");
        UUID owner = engine.pendingOwner();
        if (owner != null && (!owner.equals(player.getUniqueId()) || engine.recover(player) != RodUpgradeJournal.Result.DONE)) {
            player.sendMessage("§cОжидается восстановление предыдущего улучшения. Обратитесь к администрации."); return;
        }
        // Legacy signed rods predate the visible lore. Refresh only after all known
        // inventory-changing Fishing operations have finished and the ledger verifies them.
        if (refreshGuard.safe(player)) refreshLegacyLore(player);
        Holder holder = new Holder(player.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text("Улучшение удочки"));
        render(holder, player); player.openInventory(holder.inventory);
    }
    void refreshLegacyLore(Player player) throws Exception {
        var inventory = player.getInventory(); var storage = inventory.getStorageContents();
        for (int slot = 0; slot < Math.min(storage.length, 36); slot++) {
            ItemStack item = storage[slot]; var token = codec.read(item).orElse(null);
            if (token == null || token.level() < 2 || engine.rodLevel(item) != token.level()) continue;
            ItemStack updated = codec.stamp(item, token);
            if (!updated.equals(item)) inventory.setItem(slot, updated);
        }
    }
    void render(Holder holder, Player player) {
        holder.inventory.clear();
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int i = 0; i < Math.min(36, storage.length); i++) {
            var item = storage[i];
            if (item == null || item.getType() != Material.FISHING_ROD || item.getAmount() != 1) continue;
            var token = codec.read(item);
            if (codec.marked(item) && token.isEmpty()) {
                holder.inventory.setItem(i, icon(Material.BARRIER, "Удочка: повреждённая метка", List.of("Обратитесь к администрации.")));
                continue;
            }
            int level = token.map(t -> t.level()).orElse(1);
            var info = new ArrayList<String>();
            info.add("Уровень " + level + " · " + FishingModel.rod(level).name());
            info.add("Опыт навыка: +" + FishingModel.rod(level).xpBonusPercent() + "% (с учётом лимита).");
            if (level < 10) {
                var cost = RodUpgradePlanner.costFor(level + 1);
                info.add("Следующий уровень: " + FishingText.amount(cost.minor()) + " алм. со счёта /bal.");
                for (var ingredient : cost.ingredients()) info.add(FishingText.material(ingredient.material()) + ": "
                        + RodUpgradePlanner.available(storage, ingredient.material()) + "/" + ingredient.count());
                info.add("Нажмите, чтобы узнать детали.");
            } else info.add("Достигнут максимальный уровень.");
            holder.inventory.setItem(i, icon(Material.FISHING_ROD, (i == holder.selected ? "✓ " : "") + "Удочка · ячейка " + (i + 1), info));
        }
        holder.inventory.setItem(45, icon(Material.BOOK, "Как улучшить", List.of(
                "Наведите на удочку выше, чтобы увидеть цену и материалы.",
                "Выберите удочку, затем подготовьте и подтвердите улучшение.",
                "Материалы должны быть обычными предметами в инвентаре.",
                "Деньги списываются с банкового счёта /bal.")));
        if (holder.selected >= 0 && holder.selected < storage.length) {
            var item = storage[holder.selected];
            var token = codec.read(item);
            if (item == null || item.getType() != Material.FISHING_ROD || item.getAmount() != 1
                    || (codec.marked(item) && token.isEmpty())) {
                holder.selected = -1; holder.quote = null;
            } else {
                int level = token.map(t -> t.level()).orElse(1);
                if (level < 10) {
                    var cost = RodUpgradePlanner.costFor(level + 1);
                    var current = FishingModel.rod(level); var next = FishingModel.rod(level + 1);
                    var lore = new ArrayList<String>();
                    lore.add("Уровень " + level + " → " + next.level() + " · " + next.name());
                    lore.add("Цена: " + FishingText.amount(cost.minor()) + " алм. с /bal.");
                    for (var ingredient : cost.ingredients()) lore.add(FishingText.material(ingredient.material()) + ": "
                            + RodUpgradePlanner.available(storage, ingredient.material()) + "/" + ingredient.count());
                    lore.add("Опыт навыка: +" + current.xpBonusPercent() + "% → +" + next.xpBonusPercent() + "% (с лимитом).");
                    lore.add("Вес крупного размера: +" + current.largeBonusPoints() + " → +" + next.largeBonusPoints() + " вместо маленького.");
                    lore.add("Проверка материалов, банка и подлинности — перед оплатой.");
                    holder.inventory.setItem(40, icon(Material.FISHING_ROD, "Следующее улучшение", lore));
                    holder.inventory.setItem(49, icon(holder.quote == null ? Material.GOLD_INGOT : Material.EMERALD_BLOCK,
                            holder.quote == null ? "Подготовить улучшение" : "Подтвердить улучшение", List.of(
                                    holder.quote == null ? "Проверить материалы и подготовить сделку." : "Списать алмазы и ресурсы, улучшить удочку.",
                                    "До подтверждения ничего не списывается.")));
                } else {
                    holder.quote = null;
                    holder.inventory.setItem(40, icon(Material.NETHER_STAR, "Максимальный уровень", List.of("Эта удочка уже достигла 10 уровня.")));
                }
            }
        }
        if (back != null) holder.inventory.setItem(52, icon(Material.ARROW, "Назад", List.of("Главное меню рыбалки.")));
        holder.inventory.setItem(53, icon(Material.BARRIER, "Закрыть", List.of("Без улучшения.")));
    }
    private static ItemStack icon(Material material, String label, List<String> lines) {
        var item = new ItemStack(material); var meta = item.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(lines.stream().map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta); return item;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId()) || !engine.available(player)) return;
        int slot = event.getRawSlot();
        if (slot >= 0 && slot < 36) {
            var storage = player.getInventory().getStorageContents();
            if (slot >= storage.length || storage[slot] == null || storage[slot].getType() != Material.FISHING_ROD) return;
            holder.selected = slot; holder.quote = null; render(holder, player);
        } else if (slot == 49 && holder.selected >= 0 && holder.quote == null && holder.inventory.getItem(49) != null) {
            var storage = player.getInventory().getStorageContents();
            var rod = storage[holder.selected];
            if (rod == null || rod.getType() != Material.FISHING_ROD || rod.getAmount() != 1 || (codec.marked(rod) && codec.read(rod).isEmpty())) {
                render(holder, player); player.sendMessage("§cУдочка изменилась или её метка повреждена."); return;
            }
            int level = codec.read(rod).map(t -> t.level()).orElse(1);
            if (level >= 10) { render(holder, player); return; }
            var missing = RodUpgradePlanner.costFor(level + 1).ingredients().stream()
                    .filter(part -> RodUpgradePlanner.available(storage, part.material()) < part.count())
                    .map(part -> FishingText.material(part.material()) + ": " + RodUpgradePlanner.available(storage, part.material()) + "/" + part.count())
                    .toList();
            if (!missing.isEmpty()) { render(holder, player); player.sendMessage("§cНе хватает обычных материалов: " + String.join(", ", missing)); return; }
            try {
                holder.quote = engine.quote(player, holder.selected); render(holder, player);
                player.sendMessage("§eПроверьте стоимость и нажмите подтверждение.");
            } catch (IllegalArgumentException error) {
                holder.quote = null; render(holder, player);
                player.sendMessage("§cУлучшение недоступно: проверьте количество обычных материалов и состояние удочки.");
            } catch (Exception error) { holder.quote = null; render(holder, player); report(player, error); }
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
