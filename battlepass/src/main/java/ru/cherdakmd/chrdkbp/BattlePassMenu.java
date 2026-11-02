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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Меню батлпасса: задания иконками, клик открывает понятные условия и отдельную кнопку получения.
 * Раскладка и действия вынесены в {@link BattlePassMenuLayout} и покрыты тестами.
 */
final class BattlePassMenu implements Listener {

    private final BattlePassPlugin plugin;

    BattlePassMenu(BattlePassPlugin plugin) {
        this.plugin = plugin;
    }

    /** Строка меню: номер волны и состояние задания. */
    private record Entry(int week, long unlockAt, boolean unlocked, BattlePassView.Mission view) {
    }

    static final class Holder implements InventoryHolder {
        private final UUID owner;
        private final int page;
        private final BattlePassConfig.Season season;
        private Inventory inventory;
        private List<String> missionIds = List.of();
        private int itemCount;
        private BattlePassFilter filter=BattlePassFilter.ALL;

        Holder(UUID owner, int page, BattlePassConfig.Season season) {
            this.owner = owner;
            this.page = page;
            this.season = season;
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
    void missions(Player player, int page) { missions(player,page,BattlePassFilter.ALL); }
    void missions(Player player, int page,BattlePassFilter filter) {
        if (!plugin.canUse(player)) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        if (!plugin.settings().menuEnabled()) {
            plugin.messages().send(player, "menu-disabled");
            return;
        }
        BattlePassConfig.Season season = plugin.season();
        if (season == null || season.empty()) {
            plugin.messages().send(player, "no-season");
            return;
        }
        List<Entry> entries = entries(player, season).stream().filter(e->filter.includes(e.unlocked(),e.view())).toList();
        List<String> ids = entries.stream().map(entry -> entry.view().def().id()).toList();
        int safePage = BattlePassMenuLayout.clampPage(page, entries.size());
        Holder holder = new Holder(player.getUniqueId(), safePage, season);
        holder.filter=filter;
        Inventory inventory = Bukkit.createInventory(holder, BattlePassMenuLayout.SIZE,
                plugin.messages().get("menu-title",
                        "season", BattlePassScreen.described(season.name()),
                        "page", safePage + 1, "pages", BattlePassMenuLayout.pageCount(entries.size())));
        holder.bind(inventory, ids, entries.size());

        if(entries.isEmpty())inventory.setItem(22,button(Material.GRAY_DYE,"menu-filter-empty"));
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
        inventory.setItem(BattlePassMenuLayout.SLOT_CLAIM_ALL, button(Material.CHEST, "menu-claim-all"));
        inventory.setItem(BattlePassMenuLayout.SLOT_BACK, button(Material.COMPASS, "menu-back"));
        inventory.setItem(BattlePassMenuLayout.SLOT_REFRESH, button(Material.CLOCK, "menu-refresh"));
        ItemStack premium = button(Material.GOLD_INGOT, "menu-premium-name");
        ItemMeta premiumMeta = premium.getItemMeta();
        if (premiumMeta != null) {
            premiumMeta.lore(plugin.messages().getList("menu-premium-lore",
                    "vip", plugin.messages().mini(playerStatusKey(plugin.vipActive(player))),
                    "premium", plugin.messages().mini(playerStatusKey(plugin.premiumActive(player)))));
            premium.setItemMeta(premiumMeta);
        }
        inventory.setItem(BattlePassMenuLayout.SLOT_PREMIUM, premium);
        inventory.setItem(BattlePassMenuLayout.SLOT_CLOSE, button(Material.BARRIER, "menu-close"));
        ItemStack filterButton=button(Material.HOPPER,"menu-filter");
        var filterMeta=filterButton.getItemMeta();filterMeta.displayName(plugin.messages().get("menu-filter","filter",plugin.messages().plain("filter-"+filter.name().toLowerCase(java.util.Locale.ROOT))));filterButton.setItemMeta(filterMeta);
        inventory.setItem(51,filterButton);
        player.openInventory(inventory);
    }

    void open(Player player,int page){plugin.guide().home(player);}

    /** Все задания сезона: будущие волны видны с датой открытия. */
    private List<Entry> entries(Player player, BattlePassConfig.Season season) {
        long now = System.currentTimeMillis();
        boolean vipActive = plugin.vipActive(player);
        boolean premiumActive = plugin.premiumActive(player);
        UUID uuid = player.getUniqueId();
        List<Entry> entries = new ArrayList<>();
        for (int index = 0; index < season.weeks().size(); index++) {
            for (BattlePassConfig.MissionDef mission : season.weeks().get(index).missions()) {
                entries.add(new Entry(index + 1, BattlePassConfig.unlockAt(season, index),
                        BattlePassConfig.isUnlocked(season, index, now), new BattlePassView.Mission(
                        mission,
                        plugin.store().progressOf(uuid, mission.id()),
                        plugin.store().isClaimed(uuid, mission.id(), "FREE"),
                        plugin.store().isClaimed(uuid, mission.id(), "VIP"),
                        vipActive,
                        plugin.store().isClaimed(uuid, mission.id(), "PREMIUM"),
                        premiumActive)));
            }
        }
        return entries;
    }

    private ItemStack icon(Entry entry) {
        BattlePassView.Mission view = entry.view();
        BattlePassConfig.MissionDef mission = view.def();
        ItemStack item = new ItemStack(entry.unlocked() ? materialOf(mission) : Material.GRAY_DYE);
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
        if (!entry.unlocked()) {
            lore.add(plugin.messages().get("menu-lore-locked", "date",
                    java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                            .withZone(java.time.ZoneId.systemDefault())
                            .format(java.time.Instant.ofEpochMilli(entry.unlockAt()))));
        } else switch (view.state()) {
            case READY -> lore.add(plugin.messages().get("menu-lore-claim"));
            case VIP_LOCKED -> lore.add(plugin.messages().get("menu-lore-vip"));
            case PREMIUM_LOCKED -> lore.add(plugin.messages().get("menu-lore-premium"));
            case CLAIMED -> lore.add(plugin.messages().get("menu-lore-claimed"));
            default -> { /* в работе — подсказка не нужна */ }
        }
        if (entry.unlocked() && view.state() == BattlePassView.State.READY) {
            meta.setEnchantmentGlintOverride(true);
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
                .filter(entry -> entry.unlocked() && entry.view().state() == BattlePassView.State.READY)
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

    private static String playerStatusKey(boolean active) {
        return active ? "menu-track-active" : "menu-track-locked";
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
            if (configured != null && configured.isItem() && !configured.isAir()) {
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

    void closeAll() {
        for(Player player:Bukkit.getOnlinePlayers())
            if(player.getOpenInventory().getTopInventory().getHolder() instanceof Holder)player.closeInventory();
    }
    @EventHandler(priority = EventPriority.HIGHEST,ignoreCancelled=true)
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
        // Bukkit запрещает менять открытый инвентарь внутри InventoryClickEvent.
        // Проверка текущего Holder также гасит второй клик из старого окна.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != holder) {
                return;
            }
            if (!plugin.canUse(player) || !plugin.settings().menuEnabled()) {
                player.closeInventory();
                return;
            }
            if (plugin.season() != holder.season) {
                missions(player, 0); // после reload старый id не должен выдать награду другого сезона
                return;
            }
            int pages = BattlePassMenuLayout.pageCount(holder.itemCount);
            BattlePassMenuLayout.Action action = BattlePassMenuLayout.actionFor(
                    slot, holder.page, pages, holder.itemCount);
            switch (action) {
                case FILTER -> missions(player,0,holder.filter.next());
                case CLOSE -> player.closeInventory();
                case PREV -> missions(player, holder.page - 1,holder.filter);
                case NEXT -> missions(player, holder.page + 1,holder.filter);
                case REFRESH -> missions(player, holder.page,holder.filter);
                case BACK -> {
                    player.closeInventory();
                    plugin.guide().home(player);
                }
                case SUMMARY -> plugin.guide().home(player);
                case PREMIUM -> {
                    player.closeInventory();
                    player.performCommand("pass shop");
                }
                case CLAIM_ALL -> {
                    plugin.claims().claimAll(player);
                    missions(player, holder.page,holder.filter);
                }
                case MISSION -> {
                    int index = BattlePassMenuLayout.missionIndex(slot, holder.page, holder.itemCount);
                    if (index >= 0 && index < holder.missionIds.size()) {
                        // Claims повторно проверяет волну, прогресс, права и уже выданные награды.
                        plugin.guide().detail(player, holder.missionIds.get(index));
                    }
                }
                default -> { }
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

}
