package ru.cherdakmd.chrdktierforge;

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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Витрина «Коллекция кузни» (1.4.150).
 *
 * <p>Три раздела: семь редкостей, 105 свойств тиров и 30 аффиксов. Открытые
 * позиции горят цветом, закрытые показаны серым красителем — игрок видит, что
 * ещё можно встретить, но не видит эффекта, пока не найдёт. Свойства
 * листаются страницами по 45 штук.</p>
 *
 * <p>Меню только читает; все клики отменяются, чтобы витрину нельзя было
 * использовать как сундук.</p>
 */
public final class TierCollectionMenu implements Listener {

    private static final int CONTENT = 45;
    private static final int SLOT_TIERS = 45;
    private static final int SLOT_PROPERTIES = 46;
    private static final int SLOT_AFFIXES = 47;
    private static final int SLOT_PREV = 48;
    private static final int SLOT_PROGRESS = 49;
    private static final int SLOT_NEXT = 50;
    private static final int SLOT_TITLES = 51;
    private static final int SLOT_CLOSE = 53;

    private final TierForgePlugin plugin;
    private final TierCollectionService service;

    public TierCollectionMenu(TierForgePlugin plugin, TierCollectionService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** Разделы витрины. */
    public enum Tab {
        TIERS,
        PROPERTIES,
        AFFIXES,
        /** Выбор титула: авто, «не показывать» и четыре закрытых раздела. */
        TITLES
    }

    /** Одна карточка раздела: ид, имя, тир, эффект, открыта ли. */
    private record Entry(String id, String name, String tier, String effect,
                         boolean known, boolean good) {
    }

    public void open(Player player) {
        open(player, Tab.TIERS, 1);
    }

    /**
     * Открывает раздел на нужной странице.
     *
     * @param page номер с 1; вне диапазона приводится к ближайшему
     */
    public void open(Player player, Tab tab, int page) {
        if (player == null) {
            return;
        }
        if (!service.enabled()) {
            plugin.messages().send(player, "collection-disabled");
            return;
        }
        Tab currentTab = tab == null ? Tab.TIERS : tab;
        TierCollectionBook.Snapshot snapshot = service.snapshot(player);
        List<Entry> entries = entries(currentTab, snapshot, player);
        int pages = Math.max(1, (entries.size() + CONTENT - 1) / CONTENT);
        int current = Math.clamp(page, 1, pages);
        Holder holder = new Holder(player.getUniqueId(), currentTab, current);
        holder.inventory = Bukkit.createInventory(holder, 54,
                plugin.messages().get("collection-title",
                        "tab", Messages.trusted(tabName(currentTab))));
        int from = (current - 1) * CONTENT;
        for (int index = from; index < Math.min(entries.size(), from + CONTENT); index++) {
            holder.inventory.setItem(index - from, icon(entries.get(index), currentTab));
        }
        holder.inventory.setItem(SLOT_TIERS, tabIcon(Material.NETHER_STAR,
                plugin.messages().get("collection-tab-tiers"),
                snapshot.tierCount(), TierCollectionBook.TIER_TOTAL, currentTab == Tab.TIERS));
        holder.inventory.setItem(SLOT_PROPERTIES, tabIcon(Material.ENCHANTED_BOOK,
                plugin.messages().get("collection-tab-properties"),
                snapshot.propertyCount(), TierCollectionConfig.PROPERTY_TOTAL,
                currentTab == Tab.PROPERTIES));
        holder.inventory.setItem(SLOT_AFFIXES, tabIcon(Material.AMETHYST_SHARD,
                plugin.messages().get("collection-tab-affixes"),
                snapshot.affixCount(), TierCollectionConfig.AFFIX_TOTAL,
                currentTab == Tab.AFFIXES));
        holder.inventory.setItem(SLOT_PROGRESS, progressIcon(player, snapshot));
        holder.inventory.setItem(SLOT_TITLES, tabIcon(Material.NAME_TAG,
                plugin.messages().get("collection-tab-titles"),
                snapshot.total(), TierCollectionBook.CATALOG_TOTAL, currentTab == Tab.TITLES));
        if (pages > 1) {
            int previous = current <= 1 ? pages : current - 1;
            int next = current >= pages ? 1 : current + 1;
            holder.inventory.setItem(SLOT_PREV, pageButton(
                    plugin.messages().get("collection-page-prev", "page", previous),
                    previous, current, pages));
            holder.inventory.setItem(SLOT_NEXT, pageButton(
                    plugin.messages().get("collection-page-next", "page", next),
                    next, current, pages));
        }
        holder.inventory.setItem(SLOT_CLOSE, button(Material.BARRIER,
                plugin.messages().get("collection-close")));
        player.openInventory(holder.inventory);
    }

    private String tabName(Tab tab) {
        return switch (tab) {
            case TIERS -> plugin.messages().raw("collection-tab-tiers");
            case PROPERTIES -> plugin.messages().raw("collection-tab-properties");
            case AFFIXES -> plugin.messages().raw("collection-tab-affixes");
            case TITLES -> plugin.messages().raw("collection-tab-titles");
        };
    }

    /* ------------------------------- содержимое ------------------------------- */

    private List<Entry> entries(Tab tab, TierCollectionBook.Snapshot snapshot, Player player) {
        return switch (tab) {
            case TIERS -> tierEntries(snapshot);
            case PROPERTIES -> propertyEntries(snapshot);
            case AFFIXES -> affixEntries(snapshot);
            case TITLES -> titleEntries(snapshot, player);
        };
    }

    /**
     * Витрина титулов: «авто», «скрыт» и четыре раздела. Выбранный помечен
     * зелёным красителем, незакрытый раздел — серым (надеть нельзя).
     */
    private List<Entry> titleEntries(TierCollectionBook.Snapshot snapshot, Player player) {
        TierCollectionBook.Progress progress = snapshot.progress();
        String choice = service.choice(player.getUniqueId());
        String shown = service.shownSection(player.getUniqueId());
        List<Entry> out = new ArrayList<>(6);
        out.add(new Entry(TierCollectionTitles.AUTO,
                plugin.messages().raw("collection-title-auto-name"), "", "",
                true, choice == null));
        out.add(new Entry(TierCollectionTitles.OFF,
                plugin.messages().raw("collection-title-off-name"), "", "",
                true, TierCollectionTitles.OFF.equals(choice)));
        for (String section : TierCollectionConfig.SECTIONS) {
            boolean unlocked = progress.complete(section);
            out.add(new Entry(section,
                    plugin.messages().raw("title." + section), "",
                    plugin.messages().raw("collection-title-progress",
                            "found", progress.count(section),
                            "count", TierCollectionConfig.threshold(section)),
                    unlocked, section.equals(shown)));
        }
        return out;
    }

    private List<Entry> tierEntries(TierCollectionBook.Snapshot snapshot) {
        List<Entry> out = new ArrayList<>(TierCollectionBook.TIER_TOTAL);
        for (String id : TierDef.LADDER) {
            TierDef.Snapshot def = plugin.config() == null ? null : plugin.config().find(id);
            out.add(new Entry(id,
                    plugin.messages().raw("tier." + id + ".name"),
                    id,
                    def == null ? "" : percentText(def.percent()),
                    snapshot.hasTier(id), true));
        }
        return out;
    }

    private List<Entry> propertyEntries(TierCollectionBook.Snapshot snapshot) {
        List<TierProperty> all = new ArrayList<>(TierProperties.all());
        all.sort(Comparator.comparingInt(property -> TierDef.indexOf(property.tier())));
        List<Entry> out = new ArrayList<>(all.size());
        for (TierProperty property : all) {
            out.add(new Entry(property.id(), property.name(), property.tier(),
                    property.describe(), snapshot.hasProperty(property.id()),
                    true));
        }
        return out;
    }

    private List<Entry> affixEntries(TierCollectionBook.Snapshot snapshot) {
        List<TierAffix> all = new ArrayList<>(TierAffixes.all());
        all.sort(Comparator.comparing((TierAffix affix) -> !affix.good()));
        List<Entry> out = new ArrayList<>(all.size());
        for (TierAffix affix : all) {
            out.add(new Entry(affix.id(), affix.name(), "",
                    affix.describe(), snapshot.hasAffix(affix.id()), affix.good()));
        }
        return out;
    }

    private ItemStack icon(Entry entry, Tab tab) {
        Material material = switch (tab) {
            case TIERS -> tierMaterial(entry.id());
            case PROPERTIES -> entry.known() ? Material.ENCHANTED_BOOK : Material.GRAY_DYE;
            case AFFIXES -> entry.known()
                    ? (entry.good() ? Material.AMETHYST_SHARD : Material.GUNPOWDER)
                    : Material.GRAY_DYE;
            case TITLES -> entry.good() ? Material.LIME_DYE
                    : (entry.known() ? Material.NAME_TAG : Material.GRAY_DYE);
        };
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        if (tab == Tab.TIERS) {
            String color = plugin.messages().raw("tier." + entry.id() + ".color");
            meta.displayName(plugin.messages().get("collection-tier-name",
                    "color", Messages.trusted(color),
                    "name", entry.name()));
        } else if (tab == Tab.TITLES) {
            // Имена титулов — готовая MiniMessage-разметка из messages.yml.
            meta.displayName(plugin.messages().renderTemplate(entry.name()));
        } else {
            meta.displayName(entry.known()
                    ? plugin.messages().get("collection-name-known", "name", entry.name())
                    : plugin.messages().get("collection-name-unknown", "name", entry.name()));
        }
        List<Component> lore = new ArrayList<>();
        if (tab == Tab.PROPERTIES) {
            lore.add(plugin.messages().get("collection-entry-tier-label",
                    "tier", Messages.trusted(plugin.messages()
                            .raw("tier." + entry.tier() + ".name"))));
        }
        if (tab == Tab.TITLES) {
            if (!entry.known()) {
                lore.add(plugin.messages().get("collection-title-entry-locked"));
            } else if (entry.good()) {
                lore.add(plugin.messages().get("collection-title-entry-worn"));
            } else {
                lore.add(plugin.messages().get("collection-title-entry-click"));
            }
            if (!entry.effect().isBlank()) {
                lore.add(plugin.messages().get("collection-title-entry-progress",
                        "progress", entry.effect()));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
            return item;
        }
        if (tab == Tab.AFFIXES) {
            lore.add(entry.good()
                    ? plugin.messages().get("collection-entry-good")
                    : plugin.messages().get("collection-entry-bad"));
        }
        if (entry.known() && !entry.effect().isBlank()) {
            lore.add(plugin.messages().get("collection-entry-effect", "effect", entry.effect()));
        }
        lore.add(entry.known()
                ? plugin.messages().get("collection-entry-known")
                : plugin.messages().get("collection-entry-unknown"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack tabIcon(Material material, Component name, int found, int total,
                              boolean active) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(name);
        List<Component> lore = new ArrayList<>();
        int percent = total <= 0 ? 0 : (int) (Math.min(found, total) * 100L / total);
        lore.add(plugin.messages().get("collection-tab-progress",
                "found", found, "total", total, "percent", percent));
        if (active) {
            lore.add(plugin.messages().get("collection-tab-active"));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack progressIcon(Player player, TierCollectionBook.Snapshot snapshot) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(plugin.messages().get("collection-progress-name"));
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("collection-progress-line",
                "found", snapshot.total(),
                "total", TierCollectionBook.CATALOG_TOTAL,
                "percent", snapshot.percent()));
        lore.add(plugin.messages().get("collection-progress-row",
                "name", Messages.trusted(plugin.messages().raw("collection-tab-tiers")),
                "found", snapshot.tierCount(), "total", TierCollectionBook.TIER_TOTAL));
        lore.add(plugin.messages().get("collection-progress-row",
                "name", Messages.trusted(plugin.messages().raw("collection-tab-properties")),
                "found", snapshot.propertyCount(),
                "total", TierCollectionConfig.PROPERTY_TOTAL));
        lore.add(plugin.messages().get("collection-progress-row",
                "name", Messages.trusted(plugin.messages().raw("collection-tab-affixes")),
                "found", snapshot.affixCount(),
                "total", TierCollectionConfig.AFFIX_TOTAL));
        // 1.4.151: титулы за закрытые разделы — заработанный и ближайший.
        TierCollectionBook.Progress progress = snapshot.progress();
        String earnedTitle = service.titleOf(player);
        if (earnedTitle != null) {
            lore.add(plugin.messages().get("collection-progress-title",
                    "title", Messages.trusted(earnedTitle)));
        }
        String nextTitle = TierCollectionTitles.next(progress);
        if (nextTitle != null) {
            lore.add(plugin.messages().get("collection-progress-next-title",
                    "name", Messages.trusted(service.sectionName(nextTitle)),
                    "found", progress.count(nextTitle),
                    "count", TierCollectionConfig.threshold(nextTitle)));
        }
        TierCollectionConfig config = service.config();
        if (config != null) {
            MilestoneText next = nextMilestone(config, snapshot);
            if (next != null) {
                lore.add(plugin.messages().get("collection-progress-next",
                        "name", Messages.trusted(next.name()),
                        "found", next.found(), "count", next.count()));
            } else if (snapshot.total() >= TierCollectionBook.CATALOG_TOTAL) {
                lore.add(plugin.messages().get("collection-progress-done"));
            }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private record MilestoneText(String name, int found, int count) {
    }

    /** Ближайшая незакрытая веха по порядку: свойства, затем аффиксы. */
    private MilestoneText nextMilestone(TierCollectionConfig config,
                                        TierCollectionBook.Snapshot snapshot) {
        // Разделы (включая «все редкости») показывает строка «следующий титул»,
        // здесь — только промежуточные вехи, за которые платят дни и алмазы.
        for (TierCollectionConfig.Milestone milestone : config.properties()) {
            if (snapshot.propertyCount() < milestone.count()) {
                return new MilestoneText(plugin.messages().raw("collection-milestone-properties",
                        "count", milestone.count()), snapshot.propertyCount(), milestone.count());
            }
        }
        for (TierCollectionConfig.Milestone milestone : config.affixes()) {
            if (snapshot.affixCount() < milestone.count()) {
                return new MilestoneText(plugin.messages().raw("collection-milestone-affixes",
                        "count", milestone.count()), snapshot.affixCount(), milestone.count());
            }
        }
        return null;
    }

    private ItemStack pageButton(Component title, int target, int page, int pages) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(title);
        meta.lore(List.of(plugin.messages().get("collection-page-info",
                "current", page, "pages", pages)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack button(Material material, Component title) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(title);
        item.setItemMeta(meta);
        return item;
    }

    private static String percentText(int percent) {
        return (percent > 0 ? "+" : "") + percent + "%";
    }

    private static Material tierMaterial(String id) {
        return switch (id) {
            case "cursed" -> Material.WITHER_ROSE;
            case "weak" -> Material.STONE_SWORD;
            case "common" -> Material.IRON_SWORD;
            case "fine" -> Material.GOLDEN_SWORD;
            case "rare" -> Material.DIAMOND_SWORD;
            case "epic" -> Material.AMETHYST_SHARD;
            case "legendary" -> Material.NETHERITE_SWORD;
            default -> Material.PAPER;
        };
    }

    /* --------------------------------- клики --------------------------------- */

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
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= holder.inventory.getSize()) {
            return;
        }
        switch (slot) {
            case SLOT_TIERS -> open(player, Tab.TIERS, 1);
            case SLOT_PROPERTIES -> open(player, Tab.PROPERTIES, 1);
            case SLOT_AFFIXES -> open(player, Tab.AFFIXES, 1);
            case SLOT_TITLES -> open(player, Tab.TITLES, 1);
            case SLOT_PREV -> open(player, holder.tab, holder.page - 1);
            case SLOT_NEXT -> open(player, holder.tab, holder.page + 1);
            case SLOT_CLOSE -> player.closeInventory();
            default -> {
                if (holder.tab == Tab.TITLES && slot < CONTENT) {
                    chooseTitle(player, holder, slot);
                }
                // остальное содержимое — только для чтения
            }
        }
    }

    /** Клик по титулу: надеть, включить авто или скрыть. */
    private void chooseTitle(Player player, Holder holder, int slot) {
        List<Entry> entries = entries(Tab.TITLES, service.snapshot(player), player);
        if (slot >= entries.size()) {
            return;
        }
        String error = service.chooseTitle(player, entries.get(slot).id());
        if (error != null) {
            plugin.messages().send(player, error, "id", entries.get(slot).id());
            return;
        }
        String choice = service.choice(player.getUniqueId());
        if (choice == null) {
            plugin.messages().send(player, "collection-title-set-auto");
        } else if (TierCollectionTitles.OFF.equals(choice)) {
            plugin.messages().send(player, "collection-title-set-off");
        } else {
            plugin.messages().send(player, "collection-title-set",
                    "title", Messages.trusted(plugin.messages().raw("title." + choice)));
        }
        open(player, holder.tab, holder.page);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private static final class Holder implements InventoryHolder {

        private final UUID owner;
        private final Tab tab;
        private final int page;
        private Inventory inventory;

        private Holder(UUID owner, Tab tab, int page) {
            this.owner = owner;
            this.tab = tab;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
