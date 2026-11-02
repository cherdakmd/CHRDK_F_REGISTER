package ru.cherdakmd.chrdkhunt;

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
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Главное меню охоты и меню ключей (1.4.155).
 *
 * <p>Кнопки ведут в лавку, коллекции, полнолуние, справку, сундуки и обмен, а карточка
 * профиля показывает кошелёк, звание и остаток суточного обмена. Меню только
 * показывает и передаёт нажатие: все правила (цена, лимит, списание) живут в
 * {@link HuntService}, поэтому клик и команда не могут разойтись.</p>
 *
 * <p>Клики по верхнему инвентарю всегда отменяются, драг запрещён: отсюда
 * ничего нельзя унести или переложить.</p>
 */
public final class HuntGui implements Listener {

    /** Слоты главного меню. */
    private static final int INFO_SLOT = 4;
    private static final int SHOP_SLOT = 19;
    private static final int BESTIARY_SLOT = 20;
    private static final int TROPHIES_SLOT = 21;
    private static final int KEYS_SLOT = 23;
    private static final int EXCHANGE_SLOT = 25;
    static final int WILD_SLOT = 30;
    static final int MOON_SLOT = 32;
    static final int GUIDE_SLOT = 40;
    static final int HUD_SLOT = 42;
    private static final int STATS_SLOT = 45;
    private static final int TOP_SLOT = 49;
    private static final int CLOSE_SLOT = 53;
    private static final int MAIN_SIZE = 54;

    /** Сетка семейств в меню ключей: две строки по семь. */
    private static final int[] FAMILY_SLOTS = {10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25};
    private static final int KEYS_INFO_SLOT = 22;
    private static final int KEYS_CLOSE_SLOT = 26;
    private static final int KEYS_SIZE = 27;

    /** Владелец меню: чужой игрок не может нажимать кнопки. */
    static final class MainHolder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        MainHolder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Владелец меню ключей и порядок семейств на экране. */
    static final class KeysHolder implements InventoryHolder {
        private final UUID owner;
        private final List<String> familyIds;
        private Inventory inventory;

        KeysHolder(UUID owner, List<String> familyIds) {
            this.owner = owner;
            this.familyIds = List.copyOf(familyIds);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final HuntPlugin plugin;

    public HuntGui(HuntPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class GuideHolder implements InventoryHolder {
        final UUID owner;
        Inventory inventory;
        GuideHolder(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private boolean eligible(Player player) {
        if (!plugin.config().enabled()) { plugin.messages().send(player, "disabled"); return false; }
        if (!plugin.finance().eligible(player)) { plugin.messages().send(player, "finance-mode"); return false; }
        return true;
    }

    private void openGuide(Player player) {
        if (!eligible(player)) return;
        GuideHolder holder = new GuideHolder(player.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, 27, plugin.messages().get("menu-guide"));
        holder.inventory.setItem(4, plugin.items().icon(Material.BOOK, plugin.messages().get("menu-guide"), plugin.messages().getList("menu-guide-lore")));
        holder.inventory.setItem(11, plugin.items().icon(Material.IRON_SWORD, plugin.messages().get("menu-guide-combat"), plugin.messages().getList("menu-guide-combat-lore")));
        holder.inventory.setItem(13, plugin.items().icon(Material.MOSS_BLOCK, plugin.messages().get("wild-title"), plugin.messages().getList("wild-info-lore")));
        holder.inventory.setItem(15, plugin.items().icon(Material.CLOCK, plugin.messages().get("moon-title"), plugin.messages().getList("moon-rules")));
        holder.inventory.setItem(22, button(Material.ARROW, "menu-main-back", null));
        holder.inventory.setItem(26, button(Material.BARRIER, "menu-close", null));
        player.openInventory(holder.inventory);
    }

    /* -------------------------------- открытие -------------------------------- */

    public void open(Player player) {
        if (player == null) {
            return;
        }
        if (!eligible(player)) return;
        MainHolder holder = new MainHolder(player.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, MAIN_SIZE,
                plugin.messages().get("menu-title"));
        for (int slot = 0; slot < MAIN_SIZE; slot++) {
            if (slot < 9 || slot >= 45 || slot % 9 == 0 || slot % 9 == 8) holder.inventory.setItem(slot,
                    plugin.items().icon(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of()));
        }
        holder.inventory.setItem(INFO_SLOT, infoIcon(player));
        holder.inventory.setItem(WILD_SLOT, button(Material.MOSS_BLOCK, "wild-title", "menu-wild-lore"));
        holder.inventory.setItem(MOON_SLOT, plugin.items().icon(Material.CLOCK, plugin.messages().get("moon-title"), List.of(
                plugin.messages().get("moon-status-" + plugin.fullMoon().status(player.getWorld())),
                plugin.messages().get("moon-limits", "count", plugin.fullMoon().count()), plugin.messages().get("menu-moon-open"))));
        holder.inventory.setItem(GUIDE_SLOT, button(Material.BOOK, "menu-guide", "menu-guide-open"));
        holder.inventory.setItem(HUD_SLOT, plugin.items().icon(Material.SPYGLASS, plugin.messages().get("menu-hud"), List.of(
                plugin.messages().get("menu-hud-state", "state", plugin.store().mobHud(player.getUniqueId()) ? plugin.messages().mini("mob-on") : plugin.messages().mini("mob-off")),
                plugin.messages().get("menu-hud-toggle"))));
        holder.inventory.setItem(SHOP_SLOT, button(Material.EMERALD,
                "menu-shop", "menu-shop-lore"));
        holder.inventory.setItem(BESTIARY_SLOT, plugin.config().bestiary().empty()
                ? button(Material.GRAY_DYE, "menu-bestiary-empty", "menu-bestiary-empty-lore")
                : button(Material.WRITTEN_BOOK, "menu-bestiary", "menu-bestiary-lore",
                        "seen", String.valueOf(plugin.service().bestiarySeen(player)),
                        "total", String.valueOf(plugin.config().bestiary().totalEntries())));
        holder.inventory.setItem(TROPHIES_SLOT, button(Material.BONE,
                "menu-trophies", "menu-trophies-lore"));
        holder.inventory.setItem(KEYS_SLOT, button(Material.TRIAL_KEY,
                "menu-keys", "menu-keys-lore"));
        holder.inventory.setItem(EXCHANGE_SLOT, button(Material.DIAMOND,
                "menu-exchange", "menu-exchange-lore",
                "min", String.valueOf(Math.min(plugin.config().exchangeMin(),
                        Math.max(1, plugin.config().exchangePerDay())))));
        holder.inventory.setItem(STATS_SLOT, button(Material.BOOK,
                "menu-stats", "menu-stats-lore"));
        holder.inventory.setItem(TOP_SLOT, button(Material.NETHER_STAR,
                "menu-top", "menu-top-lore"));
        holder.inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "menu-close", null));
        player.openInventory(holder.inventory);
    }

    /** Меню ключей: семейства, их осколки и сундуки. */
    public void openKeys(Player player) {
        if (player == null) {
            return;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "disabled");
            return;
        }
        List<HuntFamily> families = plugin.config().families();
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < Math.min(FAMILY_SLOTS.length, families.size()); index++) {
            ids.add(families.get(index).id());
        }
        KeysHolder holder = new KeysHolder(player.getUniqueId(), ids);
        holder.inventory = Bukkit.createInventory(holder, KEYS_SIZE,
                plugin.messages().get("keys-title"));
        for (int index = 0; index < ids.size(); index++) {
            holder.inventory.setItem(FAMILY_SLOTS[index], familyIcon(player, plugin.config().family(ids.get(index))));
        }
        holder.inventory.setItem(KEYS_INFO_SLOT, keyInfoIcon(player));
        holder.inventory.setItem(KEYS_CLOSE_SLOT, button(Material.BARRIER, "menu-close", null));
        player.openInventory(holder.inventory);
    }

    /** Обновляет открытое меню на следующем тике (после покупки или отказа). */
    public void reopen(Player player) {
        reopen(player, false);
    }

    public void reopenKeys(Player player) {
        reopen(player, true);
    }

    private void reopen(Player player, boolean keys) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (keys) {
                openKeys(player);
            } else {
                open(player);
            }
        });
    }

    /* --------------------------------- иконки --------------------------------- */

    private ItemStack infoIcon(Player player) {
        long coins = plugin.service().coins(player);
        long earned = plugin.service().earned(player);
        long exchangeLeft = plugin.service().exchangeLeft(player);
        int limit = plugin.config().exchangePerDay();
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("menu-info-lore",
                "coins", String.valueOf(coins),
                "earned", String.valueOf(earned)));
        lore.add(plugin.messages().get("menu-info-rank",
                "rank", Messages.trusted(plugin.service().rankText(earned))));
        long left = plugin.service().coinsToNextRank(earned);
        if (left > 0L) {
            String next = plugin.config().nextRankId(earned);
            lore.add(plugin.messages().get("menu-info-next",
                    "left", String.valueOf(left),
                    "rank", Messages.trusted(plugin.messages().raw("rank." + next))));
        } else {
            lore.add(plugin.messages().get("menu-info-max"));
        }
        lore.add(plugin.messages().get("menu-info-exchange",
                "left", String.valueOf(exchangeLeft),
                "limit", limit <= 0 ? "0" : String.valueOf(limit)));
        return plugin.items().icon(Material.GOLD_NUGGET,
                plugin.messages().get("menu-info-name"), lore);
    }

    private ItemStack familyIcon(Player player, HuntFamily family) {
        if (family == null) {
            return new ItemStack(Material.PAPER);
        }
        long keys = plugin.service().keys(player, family.id());
        long shards = plugin.service().shards(player, family.id());
        long kills = plugin.service().kills(player, family.id());
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("keys-icon-shards",
                "shards", String.valueOf(shards),
                "shard", family.shardName()));
        lore.add(plugin.messages().get("keys-icon-kills",
                "kills", String.valueOf(kills),
                "family", family.displayName()));
        if (family.hasChest()) {
            lore.add(plugin.messages().get("keys-icon-chest"));
        } else {
            lore.add(plugin.messages().get("keys-icon-no-chest"));
        }
        return plugin.items().icon(family.keyItem(),
                plugin.messages().get("keys-icon-name",
                        "keys", String.valueOf(keys),
                        "key", family.keyName(),
                        "family", family.displayName()),
                lore);
    }

    private ItemStack keyInfoIcon(Player player) {
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("keys-info-lore"));
        lore.add(plugin.messages().get("keys-info-total",
                "keys", String.valueOf(plugin.store().totalKeys(player.getUniqueId()))));
        return plugin.items().icon(Material.TRIAL_KEY,
                plugin.messages().get("keys-info-name"), lore);
    }

    private ItemStack button(Material material, String nameKey, String loreKey,
                             Object... placeholders) {
        List<Component> lore = new ArrayList<>();
        if (loreKey != null) {
            lore.addAll(plugin.messages().getList(loreKey, placeholders));
        }
        return plugin.items().icon(material, plugin.messages().get(nameKey), lore);
    }

    /* --------------------------------- клики ---------------------------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder top = event.getView().getTopInventory().getHolder();
        boolean main = top instanceof MainHolder;
        boolean keys = top instanceof KeysHolder;
        boolean guide = top instanceof GuideHolder;
        if (!main && !keys && !guide) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        UUID owner = main ? ((MainHolder) top).owner : guide ? ((GuideHolder) top).owner : ((KeysHolder) top).owner;
        if (!owner.equals(player.getUniqueId())) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
        if (main && !mainAction(slot) || guide && slot != 22 && slot != 26) return;
        if (main || guide) {
            // InventoryClickEvent: смена окна только после завершения транзакции инвентаря.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != top) return;
                if (guide) {
                    if (slot == 26) player.closeInventory();
                    else if (slot == 22) open(player);
                } else handleMain(player, slot);
            });
        } else {
            handleKeys(player, (KeysHolder) top, slot);
        }
    }

    static boolean mainAction(int slot) {
        return switch (slot) {
            case SHOP_SLOT, BESTIARY_SLOT, TROPHIES_SLOT, KEYS_SLOT, EXCHANGE_SLOT,
                    STATS_SLOT, TOP_SLOT, CLOSE_SLOT, WILD_SLOT, MOON_SLOT, GUIDE_SLOT, HUD_SLOT -> true;
            default -> false;
        };
    }

    private void handleMain(Player player, int slot) {
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (!eligible(player)) return;
        if (slot == WILD_SLOT) { plugin.wildGui().open(player); return; }
        if (slot == MOON_SLOT) { plugin.wildGui().openMoon(player); return; }
        if (slot == GUIDE_SLOT) { openGuide(player); return; }
        if (slot == HUD_SLOT) {
            plugin.store().mobHud(player.getUniqueId(), player.getName(), !plugin.store().mobHud(player.getUniqueId()));
            plugin.requestSave();
            open(player);
            return;
        }
        if (slot == SHOP_SLOT) {
            plugin.shopGui().open(player);
            return;
        }
        if (slot == BESTIARY_SLOT) {
            plugin.bestiaryGui().open(player);
            return;
        }
        if (slot == TROPHIES_SLOT) {
            plugin.trophyGui().open(player);
            return;
        }
        if (slot == KEYS_SLOT) {
            openKeys(player);
            return;
        }
        if (slot == EXCHANGE_SLOT) {
            int min = Math.min(plugin.config().exchangeMin(),
                    Math.max(1, plugin.config().exchangePerDay()));
            if (plugin.service().exchange(player, min)) {
                reopen(player);
            }
            return;
        }
        if (slot == STATS_SLOT) {
            player.closeInventory();
            plugin.report().stats(player, player.getName());
            return;
        }
        if (slot == TOP_SLOT) {
            player.closeInventory();
            plugin.report().top(player);
        }
    }

    private void handleKeys(Player player, KeysHolder holder, int slot) {
        if (slot == KEYS_CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        int index = familyIndex(slot);
        if (index < 0 || index >= holder.familyIds.size()) {
            return;
        }
        if (plugin.service().openChest(player, holder.familyIds.get(index))) {
            reopenKeys(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder top = event.getView().getTopInventory().getHolder();
        if (top instanceof MainHolder || top instanceof KeysHolder || top instanceof GuideHolder) {
            event.setCancelled(true);
        }
    }

    private static int familyIndex(int slot) {
        for (int index = 0; index < FAMILY_SLOTS.length; index++) {
            if (FAMILY_SLOTS[index] == slot) {
                return index;
            }
        }
        return -1;
    }

    /** Закрывает все меню охоты — при выключении плагина и перезагрузке. */
    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof MainHolder || holder instanceof KeysHolder || holder instanceof GuideHolder) {
                player.closeInventory();
            }
        }

    }
}
