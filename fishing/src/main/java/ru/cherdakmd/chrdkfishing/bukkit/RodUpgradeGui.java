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
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import ru.cherdakmd.chrdkfishing.FishingModel;
import ru.cherdakmd.chrdkfishing.RodUpgradeJournal;

/** Read-only selector and two-step confirmation, never stores real items. */
final class RodUpgradeGui implements Listener {
    static final int WINDOW = 13, INFO = 18, CONFIRM = 22, BACK = 25, CLOSE = 26;
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
        ItemStack selectedRod; // In-memory reference snapshot, never put a real rod into this menu.
        boolean submitting;
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
        holder.inventory = Bukkit.createInventory(holder, 27, Component.text("Улучшение удочки"));
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
        if (holder.selected >= 0 && (holder.selected >= storage.length || holder.selectedRod == null
                || !holder.selectedRod.equals(storage[holder.selected]))) clearSelection(holder);
        holder.inventory.setItem(INFO, icon(Material.BOOK, "Как улучшить", List.of(
                "Нажмите на удочку в своём инвентаре снизу.",
                "Её значок появится в окошке; оригинал останется у вас.",
                "Затем подготовьте и подтвердите улучшение.",
                "Материалы должны быть обычными предметами в инвентаре.",
                "Деньги списываются с банкового счёта /bal.")));
        holder.inventory.setItem(WINDOW, icon(Material.GRAY_STAINED_GLASS_PANE, "Окошко удочки", List.of(
                "Нажмите удочку в своём инвентаре снизу, не беря её на курсор.",
                "Здесь будет её значок; оригинал останется в инвентаре.")));
        if (holder.selected >= 0 && holder.selected < storage.length) {
            var item = storage[holder.selected];
            var token = codec.read(item);
            if (item == null || item.getType() != Material.FISHING_ROD || item.getAmount() != 1
                    || (codec.marked(item) && token.isEmpty())) {
                clearSelection(holder);
            } else {
                int level = token.map(t -> t.level()).orElse(1);
                if (level < 10) {
                    var cost = RodUpgradePlanner.costFor(level + 1);
                    var current = FishingModel.rod(level); var next = FishingModel.rod(level + 1);
                    var lore = new ArrayList<String>();
                    lore.add("Выбрана удочка из ячейки " + (holder.selected + 1) + ".");
                    lore.add("Это значок; оригинал остаётся в инвентаре.");
                    lore.add("Нажмите на значок, чтобы убрать выбор.");
                    lore.add("Уровень " + level + " → " + next.level() + " · " + next.name());
                    lore.add("Цена: " + FishingText.amount(cost.minor()) + " алм. с /bal.");
                    for (var ingredient : cost.ingredients()) lore.add(FishingText.material(ingredient.material()) + ": "
                            + RodUpgradePlanner.available(storage, ingredient.material()) + "/" + ingredient.count());
                    lore.add("Опыт навыка: +" + current.xpBonusPercent() + "% → +" + next.xpBonusPercent() + "% (с лимитом).");
                    lore.add("Вес крупного размера: +" + current.largeBonusPoints() + " → +" + next.largeBonusPoints() + " вместо маленького.");
                    lore.add("Проверка материалов, банка и подлинности — перед оплатой.");
                    holder.inventory.setItem(WINDOW, icon(Material.FISHING_ROD, "Окошко удочки · выбрана", lore));
                    holder.inventory.setItem(CONFIRM, icon(holder.quote == null ? Material.GOLD_INGOT : Material.EMERALD_BLOCK,
                            holder.quote == null ? "Подготовить улучшение" : "Подтвердить улучшение", List.of(
                                    holder.quote == null ? "Проверить материалы и подготовить сделку." : "Списать алмазы и ресурсы, улучшить удочку.",
                                    "До подтверждения ничего не списывается.")));
                } else {
                    holder.quote = null;
                    holder.inventory.setItem(WINDOW, icon(Material.NETHER_STAR, "Максимальный уровень", List.of("Эта удочка уже достигла 10 уровня.")));
                }
            }
        }
        if (back != null) holder.inventory.setItem(BACK, icon(Material.ARROW, "Назад", List.of("Главное меню рыбалки.")));
        holder.inventory.setItem(CLOSE, icon(Material.BARRIER, "Закрыть", List.of("Без улучшения.")));
    }
    private static ItemStack icon(Material material, String label, List<String> lines) {
        var item = new ItemStack(material); var meta = item.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(lines.stream().map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta); return item;
    }
    private static boolean simpleClick(ClickType click) {
        return click == ClickType.LEFT || click == ClickType.RIGHT || click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
    }
    private static boolean emptyCursor(ItemStack cursor) {
        if (cursor == null) return true;
        return cursor.getType() == Material.AIR || cursor.getType() == Material.CAVE_AIR || cursor.getType() == Material.VOID_AIR;
    }
    private static void clearSelection(Holder holder) { holder.selected = -1; holder.selectedRod = null; holder.quote = null; }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        boolean wasCancelled = event.isCancelled();
        event.setCancelled(true);
        if (wasCancelled || !(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())
                || !engine.available(player) || holder.submitting || !simpleClick(event.getClick())) return;
        int slot = event.getRawSlot();
        if (!emptyCursor(event.getCursor())) {
            player.sendMessage("§eУберите предмет с курсора и нажмите удочку в инвентаре снизу."); return;
        }
        boolean bottom = event.getClickedInventory() != null && event.getClickedInventory() == event.getView().getBottomInventory();
        if (bottom) {
            int storageSlot = event.getSlot();
            var storage = player.getInventory().getStorageContents();
            if (storageSlot < 0 || storageSlot >= Math.min(36, storage.length)) return;
            var rod = storage[storageSlot];
            if (rod == null || rod.getType() != Material.FISHING_ROD || rod.getAmount() != 1) return;
            if (codec.marked(rod) && codec.read(rod).isEmpty()) {
                player.sendMessage("§cУдочка с повреждённой меткой. Обратитесь к администрации."); return;
            }
            holder.selected = storageSlot; holder.selectedRod = rod.clone(); holder.quote = null; render(holder, player);
        } else if (slot == WINDOW) {
            if (holder.selected >= 0) { clearSelection(holder); render(holder, player); }
            else player.sendMessage("§eНажмите удочку в своём инвентаре снизу.");
        } else if (slot == CONFIRM && holder.selected >= 0 && holder.quote == null && holder.inventory.getItem(CONFIRM) != null) {
            var storage = player.getInventory().getStorageContents();
            if (holder.selected >= storage.length) { clearSelection(holder); render(holder, player); return; }
            var rod = storage[holder.selected];
            if (rod == null || holder.selectedRod == null || !holder.selectedRod.equals(rod)
                    || rod.getType() != Material.FISHING_ROD || rod.getAmount() != 1 || (codec.marked(rod) && codec.read(rod).isEmpty())) {
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
            } catch (RodUpgradeEngine.InsufficientFunds error) {
                holder.quote = null; render(holder, player);
                player.sendMessage("§cНедостаточно доступных средств на /bal. Алмазы в инвентаре не списываются.");
            } catch (RodUpgradeEngine.BalanceUnavailable error) {
                holder.quote = null; render(holder, player);
                player.sendMessage("§cБанк недоступен; улучшение не подготовлено. Попробуйте позже.");
            } catch (RodUpgradeEngine.DuplicateRod error) {
                holder.quote = null; render(holder, player);
                player.sendMessage("§cНайдена удочка с той же меткой во второй руке или на курсоре. Улучшение не запущено.");
            } catch (IllegalArgumentException error) {
                holder.quote = null; render(holder, player);
                player.sendMessage("§cУлучшение недоступно: проверьте количество обычных материалов и состояние удочки.");
            } catch (Exception error) { holder.quote = null; render(holder, player); report(player, error); }
        } else if (slot == CONFIRM && holder.quote != null) {
            var plan = holder.quote; holder.quote = null; holder.submitting = true;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!engine.available(player) || player.getOpenInventory().getTopInventory().getHolder() != holder) {
                    holder.submitting = false; return;
                }
                player.closeInventory();
                try {
                    var result = engine.confirm(player, plan);
                    if (result == RodUpgradeJournal.Result.DONE) player.sendMessage("§aУдочка улучшена до уровня " + plan.newToken().level() + ".");
                    else if (result == RodUpgradeJournal.Result.WAIT) player.sendMessage("§eУлучшение ожидает восстановления. Не меняйте инвентарь; обратитесь к администрации.");
                    else player.sendMessage("§cУлучшение не подтверждено: проверьте инвентарь и состояние банка.");
                } catch (RodUpgradeEngine.InsufficientFunds error) {
                    player.sendMessage("§cНедостаточно доступных средств на /bal. Улучшение не запущено.");
                } catch (RodUpgradeEngine.BalanceUnavailable error) {
                    player.sendMessage("§cБанк недоступен. Улучшение не запущено.");
                } catch (Exception error) { report(player, error); }
            });
        } else if (slot == BACK && back != null) Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == holder) back.accept(player);
        });
        else if (slot == CLOSE) Bukkit.getScheduler().runTask(plugin, () -> {
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
