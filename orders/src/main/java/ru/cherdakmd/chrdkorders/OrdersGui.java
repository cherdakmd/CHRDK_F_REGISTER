package ru.cherdakmd.chrdkorders;

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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Меню доски заказов (1.4.154).
 *
 * <p>54 слота: два ряда по семь заказов, внизу профиль игрока и кнопка
 * закрытия. Иконка заказа — сам запрошенный предмет, поэтому цена и количество
 * читаются с одного взгляда. Клик по заказу пытается сдать его тем же путём,
 * что и {@code /orders deliver}: правила живут в {@link OrdersService}, а меню
 * только показывает и передаёт нажатие.</p>
 *
 * <p>Клики по верхнему инвентарю всегда отменяются, драг запрещён: отсюда
 * ничего нельзя унести или переложить.</p>
 */
public final class OrdersGui implements Listener {

    /** Сетка заказов: две строки по семь слотов. */
    private static final int[] ORDER_SLOTS = {10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25};
    private static final int INFO_SLOT = 45;
    private static final int CLOSE_SLOT = 53;
    private static final int EMPTY_SLOT = 22;
    private static final int INVENTORY_SIZE = 54;

    /** Владелец меню и номера заказов, которые были показаны при открытии. */
    static final class Holder implements InventoryHolder {
        private final UUID owner;
        private final List<String> orderIds;
        private Inventory inventory;

        Holder(UUID owner, List<String> orderIds) {
            this.owner = owner;
            this.orderIds = List.copyOf(orderIds);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final OrdersPlugin plugin;

    public OrdersGui(OrdersPlugin plugin) {
        this.plugin = plugin;
    }

    /* -------------------------------- открытие -------------------------------- */

    public void open(Player player) {
        if (player == null) {
            return;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "disabled");
            return;
        }
        List<BotOrder> orders = plugin.service().board();
        List<String> ids = new ArrayList<>(orders.size());
        for (BotOrder order : orders) {
            ids.add(order.id());
        }
        Holder holder = new Holder(player.getUniqueId(), ids);
        holder.inventory = Bukkit.createInventory(holder, INVENTORY_SIZE,
                plugin.messages().get("menu-title",
                        "count", String.valueOf(plugin.service().availableFor(player))));
        if (orders.isEmpty()) {
            holder.inventory.setItem(EMPTY_SLOT, button(Material.BARRIER,
                    plugin.messages().get("menu-empty-name"),
                    List.of(plugin.messages().get("menu-empty-lore"))));
        } else {
            int reputation = plugin.service().reputation(player);
            for (int index = 0; index < Math.min(ORDER_SLOTS.length, orders.size()); index++) {
                holder.inventory.setItem(ORDER_SLOTS[index],
                        orderIcon(player, orders.get(index), reputation));
            }
        }
        holder.inventory.setItem(INFO_SLOT, infoIcon(player));
        holder.inventory.setItem(CLOSE_SLOT, button(Material.BARRIER,
                plugin.messages().get("menu-close"), List.of()));
        player.openInventory(holder.inventory);
    }

    /** Обновляет открытое меню на следующем тике (после сдачи или отказа). */
    public void reopen(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> open(player));
    }

    /* --------------------------------- иконки --------------------------------- */

    private ItemStack orderIcon(Player player, BotOrder order, int reputation) {
        Material material = order.materialOrNull();
        ItemStack item = new ItemStack(material == null ? Material.PAPER : material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        if (material != null) {
            meta.displayName(plugin.service().itemComponent(material));
        }
        List<Component> lore = new ArrayList<>();
        boolean locked = order.minReputation() > reputation;
        lore.add(plugin.messages().get("menu-order-bot", "bot", order.botName()));
        String quote = quote(order);
        if (!quote.isEmpty()) {
            lore.add(plugin.messages().get("menu-order-quote", "line", quote));
        }
        if (material != null) {
            lore.add(plugin.messages().get("menu-order-need",
                    "material", OrdersService.itemName(material),
                    "amount", String.valueOf(order.amount())));
            lore.add(plugin.messages().get("menu-order-have",
                    "have", String.valueOf(OrdersService.countPlain(player, material)),
                    "need", String.valueOf(order.amount())));
        }
        lore.add(plugin.messages().get("menu-order-reward",
                "reward", String.valueOf(order.rewardDiamonds())));
        if (order.hasPassReward()) {
            lore.add(plugin.messages().get("menu-order-pass",
                    "days", String.valueOf(order.rewardPassDays())));
        }
        String left = OrderBoard.formatDuration(order.millisLeft(System.currentTimeMillis()));
        lore.add(plugin.messages().get("menu-order-time",
                "time", left.isEmpty() ? "—" : left));
        lore.add(plugin.messages().get("menu-order-reputation",
                "reputation", String.valueOf(order.reputation())));
        if (locked) {
            lore.add(plugin.messages().get("menu-order-locked-reputation",
                    "need", String.valueOf(order.minReputation()),
                    "have", String.valueOf(reputation)));
            lore.add(plugin.messages().get("menu-order-click-locked"));
        } else {
            lore.add(plugin.messages().get("menu-order-click"));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoIcon(Player player) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(plugin.messages().get("menu-info-name"));
        int reputation = plugin.service().reputation(player);
        String rank = plugin.service().rankText(reputation);
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("menu-info-lore",
                "reputation", String.valueOf(reputation),
                "rank", Messages.trusted(rank)));
        lore.add(plugin.messages().get("menu-info-done",
                "completed", String.valueOf(plugin.store().completed(player.getUniqueId()))));
        lore.add(plugin.messages().get("menu-info-today",
                "today", String.valueOf(plugin.service().today(player)),
                "limit", limitText()));
        int left = plugin.service().reputationToNext(reputation);
        if (left > 0) {
            String next = plugin.config().nextRankId(reputation);
            lore.add(plugin.messages().get("menu-info-next",
                    "rank", plugin.messages().mini("rank." + next),
                    "left", String.valueOf(left)));
        } else {
            lore.add(plugin.messages().get("menu-info-max"));
        }
        long cooldown = plugin.service().cooldownLeft(player);
        if (cooldown > 0L) {
            lore.add(plugin.messages().get("menu-info-cooldown",
                    "seconds", String.valueOf((cooldown + 999L) / 1000L)));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(name);
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private String limitText() {
        int limit = plugin.config().maxPerDay();
        return limit <= 0 ? "∞" : String.valueOf(limit);
    }

    private String quote(BotOrder order) {
        for (OrderBot bot : plugin.config().bots()) {
            if (bot.id().equals(order.botId()) && !bot.thanks().isEmpty()) {
                return bot.thanks().get(0);
            }
        }
        return "";
    }

    /* --------------------------------- клики ---------------------------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.owner.equals(player.getUniqueId())) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        int index = orderIndex(slot);
        if (index < 0 || index >= holder.orderIds.size()) {
            return;
        }
        String orderId = holder.orderIds.get(index);
        BotOrder order = plugin.service().order(orderId);
        if (order == null) {
            plugin.messages().send(player, "menu-order-expired");
            reopen(player);
            return;
        }
        if (plugin.service().deliver(player, order)) {
            player.closeInventory();
            player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        } else {
            reopen(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private static int orderIndex(int slot) {
        for (int index = 0; index < ORDER_SLOTS.length; index++) {
            if (ORDER_SLOTS[index] == slot) {
                return index;
            }
        }
        return -1;
    }

    /** Закрывает все меню доски — при выключении плагина. */
    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) {
                player.closeInventory();
            }
        }
    }
}
