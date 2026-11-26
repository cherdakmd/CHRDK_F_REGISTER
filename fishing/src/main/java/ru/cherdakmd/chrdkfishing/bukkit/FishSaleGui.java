package ru.cherdakmd.chrdkfishing.bukkit;

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
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import ru.cherdakmd.chrdkfishing.FishCatalog;
import ru.cherdakmd.chrdkfishing.FishSaleJournal;
import ru.cherdakmd.chrdkfishing.FishToken;

/** Chest-like basket of display icons; real fish stay in storage until journaled confirmation. */
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
    static final class Holder implements InventoryHolder {
        final UUID owner;
        final FishSaleBasket basket = new FishSaleBasket();
        Inventory inventory;
        boolean submitting;
        Holder(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
    void open(Player player) throws Exception { open(player, false); }
    void openAll(Player player) throws Exception { open(player, true); }
    private void open(Player player, boolean selectAll) throws Exception {
        if (!engine.available(player)) throw new IllegalStateException("player unavailable");
        var pending = engine.recover(player);
        if (pending != FishSaleJournal.Result.DONE) {
            player.sendMessage("§cНезавершённая операция с рыбой: обратитесь к администрации, не меняйте инвентарь.");
            return;
        }
        Holder holder = new Holder(player.getUniqueId());
        if (selectAll) {
            var storage = player.getInventory().getStorageContents();
            if (storage.length != 36) throw new IllegalStateException("unexpected storage size");
            if (!selectAll(holder.basket, storage, codec))
                player.sendMessage("§cНе удалось выбрать всю рыбу: есть копии или превышен лимит продажи. Выберите вручную.");
            if (holder.basket.isEmpty()) player.sendMessage("§eПодходящей рыбы не найдено или автоматический выбор отменён.");
            else player.sendMessage("§eПроверьте выбранную рыбу и сумму. Для продажи подтвердите в меню.");
        }
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text("Сундук продажи рыбы"));
        render(holder);
        player.openInventory(holder.inventory);
    }
    static boolean selectAll(FishSaleBasket basket, ItemStack[] storage, FishItemCodec codec) {
        if (storage == null || storage.length != 36 || !basket.isEmpty()) throw new IllegalArgumentException("sale storage");
        for (int slot = 0; slot < storage.length; slot++) {
            var entry = FishSalePlanner.inspect(slot, storage[slot], codec);
            if (entry.isEmpty()) continue;
            if (basket.toggle(entry.get()) != FishSaleBasket.Change.ADDED) { basket.clear(); return false; }
        }
        return true;
    }
    void render(Holder holder) {
        holder.inventory.clear();
        boolean visuals = plugin.getConfig().getBoolean("textures.sale-menu", false);
        var entries = holder.basket.entries();
        for (int slot = 0; slot < entries.size(); slot++) {
            var entry = entries.get(slot);
            holder.inventory.setItem(slot, entry.token() != null ? fishIcon(entry.token(), visuals) : vanillaIcon(entry.vanilla()));
        }
        holder.inventory.setItem(45, FishSaleVisuals.buttonIcon(icon(Material.BOOK, "Как продать рыбу", List.of(
                "Нажмите на рыбу в своём инвентаре снизу.",
                "Она появится в сундуке; нажмите на неё здесь, чтобы убрать.",
                "До продажи настоящая рыба остаётся в инвентаре.",
                "Обычная сырая ванильная рыба: 0.10 алм. за штуку.",
                "Рыба с чужими метками или именем не принимается.")), FishSaleVisuals.Icon.HELP, visuals));
        holder.inventory.setItem(47, FishSaleVisuals.buttonIcon(icon(Material.HOPPER, "Очистить сундук", List.of("Рыба останется в инвентаре.")), FishSaleVisuals.Icon.CLEAR, visuals));
        holder.inventory.setItem(49, FishSaleVisuals.buttonIcon(icon(holder.basket.isEmpty() ? Material.GRAY_DYE : Material.EMERALD_BLOCK,
                holder.basket.isEmpty() ? "Сундук пуст" : "Продать за " + amount(holder.basket.total()) + " алм.",
                List.of("Выбрано ячеек: " + entries.size(), "Нажмите для продажи и зачисления в банк.")),
                holder.basket.isEmpty() ? FishSaleVisuals.Icon.EMPTY : FishSaleVisuals.Icon.SELL, visuals));
        if (back != null) holder.inventory.setItem(52, FishSaleVisuals.buttonIcon(icon(Material.ARROW, "Назад", List.of("Вернуться к меню рыбалки.")), FishSaleVisuals.Icon.BACK, visuals));
        holder.inventory.setItem(53, FishSaleVisuals.buttonIcon(icon(Material.BARRIER, "Закрыть", List.of("Без продажи; рыба останется у вас.")), FishSaleVisuals.Icon.CLOSE, visuals));
    }
    private static ItemStack fishIcon(FishToken.Token fish, boolean visuals) {
        // Never put a signed/duplicable fish item into a virtual menu.
        return FishSaleVisuals.fishIcon(icon(Material.COD, FishCatalog.byId(fish.fish().speciesId()).name(), List.of(
                "Размер: " + sizeName(fish.fish().size()) + ", " + fish.fish().lengthCm() + " см",
                "Цена: " + amount(fish.quotedPrice()) + " алм.",
                "Нажмите, чтобы убрать из продажи.")), fish.fish().speciesId(), visuals);
    }
    private static ItemStack vanillaIcon(FishSaleJournal.VanillaFish fish) {
        String name = switch (fish.kind()) {
            case "COD" -> "Треска"; case "SALMON" -> "Лосось";
            case "TROPICAL_FISH" -> "Тропическая рыба"; case "PUFFERFISH" -> "Иглобрюх";
            default -> throw new IllegalArgumentException("vanilla fish");
        };
        return icon(Material.valueOf(fish.kind()), name, List.of("Количество: " + fish.quantity(),
                "Цена: " + amount(fish.quantity() * FishSaleJournal.VANILLA_PRICE_MINOR) + " алм.",
                "Нажмите, чтобы убрать из продажи."));
    }
    private static ItemStack icon(Material material, String label, List<String> lines) {
        var item = new ItemStack(material); var meta = item.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(lines.stream().map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta); return item;
    }
    private static String sizeName(ru.cherdakmd.chrdkfishing.FishingModel.Size size) {
        return switch (size) { case SMALL -> "маленькая"; case NORMAL -> "обычная"; case LARGE -> "крупная"; case TROPHY -> "трофейная"; };
    }
    private static String amount(long minor) { return (minor / 100) + "." + String.format(java.util.Locale.ROOT, "%02d", minor % 100); }
    private static boolean simpleClick(ClickType click) {
        return click == ClickType.LEFT || click == ClickType.RIGHT || click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
    }
    private static boolean emptyCursor(ItemStack cursor) {
        if (cursor == null) return true;
        Material type = cursor.getType();
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        boolean wasCancelled = event.isCancelled();
        // Freeze both inventories: shift-click, number keys and double-click must not move real items or menu icons.
        event.setCancelled(true);
        if (wasCancelled || !(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())
                || !engine.available(player) || holder.submitting || !simpleClick(event.getClick())) return;
        int rawSlot = event.getRawSlot();
        if (rawSlot == 52 && back != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == holder) back.accept(player);
            });
            return;
        }
        if (rawSlot == 53) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == holder) player.closeInventory();
            });
            return;
        }
        if (!emptyCursor(event.getCursor())) {
            player.sendMessage("§eУберите предмет с курсора и нажмите на рыбу в инвентаре снизу.");
            return;
        }
        try {
            if (event.getClickedInventory() == event.getView().getBottomInventory()) {
                int storageSlot = event.getSlot();
                var storage = player.getInventory().getStorageContents();
                if (storageSlot < 0 || storageSlot >= 36 || storage.length != 36) return;
                var fish = FishSalePlanner.inspect(storageSlot, storage[storageSlot], codec);
                if (fish.isEmpty()) {
                    player.sendMessage("§eПодходит рыба CHRDK или обычная сырая ванильная рыба без меток."); return;
                }
                switch (holder.basket.toggle(fish.get())) {
                    case DUPLICATE -> player.sendMessage("§cЭта рыба уже добавлена: одинаковые идентификаторы не продаются вместе.");
                    case TOO_EXPENSIVE -> player.sendMessage("§cПревышен лимит одной продажи. Продайте рыбу частями.");
                    case FULL -> player.sendMessage("§eСундук заполнен.");
                    case CHANGED -> player.sendMessage("§eРыба в ячейке изменилась; выберите её заново.");
                    default -> { }
                }
                render(holder);
            } else if (rawSlot >= 0 && rawSlot < FishSaleBasket.CAPACITY) {
                holder.basket.removeAt(rawSlot); render(holder);
            } else if (rawSlot == 47) {
                holder.basket.clear(); render(holder);
            } else if (rawSlot == 49) {
                if (holder.basket.isEmpty()) { player.sendMessage("§eНажмите на рыбу в своём инвентаре снизу, чтобы добавить её."); return; }
                FishSalePlanner.Plan quote;
                try { quote = engine.quote(player, holder.basket.slots()); }
                catch (FishSalePlanner.DuplicateFish duplicate) {
                    holder.basket.clear(); render(holder);
                    player.sendMessage("§cОбнаружены копии одной рыбы в инвентаре. Обратитесь к администрации."); return;
                }
                catch (IllegalArgumentException changed) {
                    holder.basket.clear(); render(holder);
                    player.sendMessage("§cИнвентарь изменился. Выберите рыбу заново."); return;
                }
                if (!holder.basket.matches(quote)) {
                    holder.basket.clear(); render(holder);
                    player.sendMessage("§cРыба или цена изменились. Выберите рыбу заново."); return;
                }
                holder.submitting = true;
                // Bukkit forbids closing inventories inside InventoryClickEvent.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!engine.available(player)) {
                        holder.submitting = false;
                        if (player.isOnline()) player.sendMessage("§cПродажа сейчас недоступна. Попробуйте позже.");
                        return;
                    }
                    if (player.getOpenInventory().getTopInventory().getHolder() != holder) return;
                    player.closeInventory();
                    try {
                        var result = engine.confirm(player, quote);
                        if (result == FishSaleJournal.Result.DONE) player.sendMessage("§aРыба продана за " + amount(quote.price()) + " алм.");
                        else if (result == FishSaleJournal.Result.WAIT) player.sendMessage("§eПродажа ожидает восстановления; обратитесь к администрации.");
                        else player.sendMessage("§cПродажа не подтверждена: инвентарь или банковая история изменились.");
                    } catch (Exception error) { report(player, error); }
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
