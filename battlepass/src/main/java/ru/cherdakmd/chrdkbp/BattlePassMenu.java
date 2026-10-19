package ru.cherdakmd.chrdkbp;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Меню батлпасса: задания иконками, клик по готовому заданию забирает награду.
 * Раскладка и действия вынесены в {@link BattlePassMenuLayout} и покрыты тестами.
 */
final class BattlePassMenu implements Listener {

    private final BattlePassPlugin plugin;

    BattlePassMenu(BattlePassPlugin plugin) {
        this.plugin = plugin;
    }

    /** Строка меню: номер волны и состояние задания. */
    private record Entry(int week, BattlePassView.Mission view) {
    }

    static final class Holder implements InventoryHolder {
        private final UUID owner;
        private final int page;
        private Inventory inventory;
        private List<String> missionIds = List.of();
        private int itemCount;

        Holder(UUID owner, int page) {
            this.owner = owner;
            this.page = page;
        }

        void bind(Inventory inventory, List<String> missionIds, int itemCount) {
            this.inventory = inventory;
            this.missionIds = List.copyOf(missionIds);
            this.itemCount = itemCount;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    /** Открыть меню на указанной странице (страница зажимается в допустимые границы). */
    void open(Player player, int page) {
        BattlePassConfig.Season season = plugin.season();
        if (season == null || season.empty()) {
            plugin.messages().send(player, "no-season");
            return;
        }
        List<Entry> entries = entries(player, season);
        List<String> ids = entries.stream().map(entry -> entry.view().def().id()).toList();
        int safePage = BattlePassMenuLayout.clampPage(page, entries.size());
        Holder holder = new Holder(player.getUniqueId(), safePage);
        Inventory inventory = Bukkit.createInventory(holder, BattlePassMenuLayout.SIZE,
                plugin.messages().get("menu-title",
                        "season", BattlePassScreen.described(season.name())));
        holder.bind(inventory, ids, entries.size());

        int from = safePage * BattlePassMenuLayout.ITEMS_PER_PAGE;
        for (int slot = 0; slot < BattlePassMenuLayout.ITEMS_PER_PAGE; slot++) {
            int index = from + slot;
            if (index >= entries.size()) {
                break;
            }
            inventory.setItem(slot, icon(entries.get(index)));
        }
        if (safePage > 0) {
            inventory.setItem(BattlePassMenuLayout.SLOT_PREV, button(Material.ARROW, "menu-prev"));
        }
        if (safePage + 1 < BattlePassMenuLayout.pageCount(entries.size())) {
            inventory.setItem(BattlePassMenuLayout.SLOT_NEXT, button(Material.ARROW, "menu-next"));
        }
        inventory.setItem(BattlePassMenuLayout.SLOT_SUMMARY, summary(player, season, entries));
        inventory.setItem(BattlePassMenuLayout.SLOT_CLOSE, button(Material.BARRIER, "menu-close"));
        player.openInventory(inventory);
    }

    /** Все задания уже открытых волн в порядке сезона. */
    private List<Entry> entries(Player player, BattlePassConfig.Season season) {
        long now = System.currentTimeMillis();
        boolean vipActive = plugin.vipActive(player);
        UUID uuid = player.getUniqueId();
        List<Entry> entries = new ArrayList<>();
        for (int index = 0; index < season.weeks().size(); index++) {
            if (!BattlePassConfig.isUnlocked(season, index, now)) {
                break;
            }
            for (BattlePassConfig.MissionDef mission : season.weeks().get(index).missions()) {
                entries.add(new Entry(index + 1, new BattlePassView.Mission(
                        mission,
                        plugin.store().progressOf(uuid, mission.id()),
                        plugin.store().isClaimed(uuid, mission.id(), "FREE"),
                        plugin.store().isClaimed(uuid, mission.id(), "VIP"),
                        vipActive)));
            }
        }
        return entries;
    }

    private ItemStack icon(Entry entry) {
        BattlePassView.Mission view = entry.view();
        BattlePassConfig.MissionDef mission = view.def();
        ItemStack item = new ItemStack(materialOf(mission));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(ru.cherdakmd.chrdkregister.Messages.format(mission.description()));
        List<Component> lore = new ArrayList<>(plugin.messages().getList("menu-lore",
                "week", entry.week(),
                "bar", plugin.screen().bar(view),
                "progress", view.shownProgress(),
                "target", mission.target(),
                "points", mission.points(),
                "reward", plugin.screen().reward(mission)));
        switch (view.state()) {
            case READY -> lore.add(plugin.messages().get("menu-lore-claim"));
            case VIP_LOCKED -> lore.add(plugin.messages().get("menu-lore-vip"));
            case CLAIMED -> lore.add(plugin.messages().get("menu-lore-claimed"));
            default -> { /* в работе — подсказка не нужна */ }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack summary(Player player, BattlePassConfig.Season season, List<Entry> entries) {
        java.util.Map<String, Integer> progress = plugin.store().progressSnapshot().getOrDefault(
                player.getUniqueId(), java.util.Map.of());
        java.util.Set<String> claims = plugin.store().claimsSnapshot().getOrDefault(
                player.getUniqueId(), java.util.Set.of());
        int ready = (int) entries.stream()
                .filter(entry -> entry.view().state() == BattlePassView.State.READY)
                .count();
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(plugin.messages().get("menu-summary-name",
                "season", BattlePassScreen.described(season.name())));
        meta.lore(plugin.messages().getList("menu-summary-lore",
                "points", BattlePassScore.points(season, progress),
                "completed", BattlePassScore.completed(season, progress),
                "claimed", BattlePassScore.claimed(season, claims),
                "ready", ready));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack button(Material material, String key) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.messages().get(key));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static Material materialOf(BattlePassConfig.MissionDef mission) {
        if (!mission.icon().isEmpty()) {
            Material configured = Material.matchMaterial(mission.icon());
            if (configured != null) {
                return configured;
            }
        }
        return switch (mission.type()) {
            case MINE -> Material.IRON_PICKAXE;
            case KILL -> Material.IRON_SWORD;
            case CRAFT -> Material.CRAFTING_TABLE;
            case FISH -> Material.FISHING_ROD;
            case PLACE -> Material.BRICKS;
            case SMELT -> Material.FURNACE;
            case ENCHANT -> Material.ENCHANTED_BOOK;
            case BREED -> Material.WHEAT;
        };
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.owner.equals(player.getUniqueId())) {
            return;
        }
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) {
            return; // shift/номер слота не должны случайно забрать награду
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= BattlePassMenuLayout.SIZE) {
            return;
        }
        int pages = BattlePassMenuLayout.pageCount(holder.itemCount);
        BattlePassMenuLayout.Action action = BattlePassMenuLayout.actionFor(
                slot, holder.page, pages, holder.itemCount);
        switch (action) {
            case CLOSE -> player.closeInventory();
            case PREV -> open(player, holder.page - 1);
            case NEXT -> open(player, holder.page + 1);
            case MISSION -> {
                int index = BattlePassMenuLayout.missionIndex(slot, holder.page, holder.itemCount);
                if (index >= 0 && index < holder.missionIds.size()) {
                    plugin.claims().claimOne(player, holder.missionIds.get(index));
                    // Перерисовываем: состояние, полосы и кнопки могли измениться.
                    open(player, holder.page);
                }
            }
            default -> { /* SUMMARY и пустые слоты ничего не делают */ }
        }
    }
}
