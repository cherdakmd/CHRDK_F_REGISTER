package ru.cherdakmd.chrdkartifacts;

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
import java.util.Set;
import java.util.UUID;

/**
 * GUI коллекции артефактов (1.4.130): {@code /artifacts collection}.
 *
 * <p>45 иконок на страницу (слоты 0–44), нижний ряд — навигация и сводка:
 * 45 «назад», 48 награды, 49 прогресс, 50 «закрыть», 53 «вперёд». Открытые
 * артефакты показываются своим материалом, именем и описанием; закрытые —
 * серым красителем с «???», чтобы коллекция не превращалась в готовый
 * гайд по всем 65 предметам.</p>
 *
 * <p>Клики по верхнему инвентарю всегда отменяются: предметы GUI нельзя
 * вытащить, а Shift-клик из своего инвентаря не должен ничего менять.</p>
 */
public final class CollectionGui implements Listener {

    private static final int PAGE_SIZE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_REWARDS = 48;
    private static final int SLOT_PROGRESS = 49;
    private static final int SLOT_CLOSE = 50;
    private static final int SLOT_NEXT = 53;

    private final ArtifactsPlugin plugin;

    public CollectionGui(ArtifactsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Открывает страницу коллекции (0-based, клампится по размеру каталога). */
    public void open(Player player, int page) {
        if (player == null) {
            return;
        }
        if (!plugin.config().collection().enabled()) {
            plugin.messages().send(player, "collection-disabled");
            return;
        }
        List<ArtifactDef> defs = ordered();
        int pages = pages(defs.size());
        int current = Math.max(0, Math.min(page, pages - 1));
        Holder holder = new Holder(player.getUniqueId(), current);
        holder.inventory = Bukkit.createInventory(holder, 54,
                plugin.messages().get("collection-title",
                        "page", String.valueOf(current + 1),
                        "pages", String.valueOf(pages)));
        fill(holder, defs, current, pages);
        player.openInventory(holder.inventory);
    }

    private List<ArtifactDef> ordered() {
        List<ArtifactDef> defs = new ArrayList<>(ArtifactRegistry.bySide(ArtifactDef.Side.GOOD));
        defs.addAll(ArtifactRegistry.bySide(ArtifactDef.Side.CURSED));
        return defs;
    }

    private static int pages(int total) {
        return Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void fill(Holder holder, List<ArtifactDef> defs, int page, int pages) {
        Player player = Bukkit.getPlayer(holder.owner);
        Set<String> discovered = player == null ? Set.of()
                : plugin.collection().discovered(player);
        int from = page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && from + slot < defs.size(); slot++) {
            ArtifactDef def = defs.get(from + slot);
            boolean found = discovered.contains(def.id());
            holder.inventory.setItem(slot, icon(def, found));
        }
        if (page > 0) {
            holder.inventory.setItem(SLOT_PREV, button(Material.ARROW, "collection-prev"));
        }
        holder.inventory.setItem(SLOT_REWARDS, rewardsIcon(player));
        holder.inventory.setItem(SLOT_PROGRESS, progressIcon(player));
        holder.inventory.setItem(SLOT_CLOSE, button(Material.BARRIER, "collection-close"));
        if (page + 1 < pages) {
            holder.inventory.setItem(SLOT_NEXT, button(Material.ARROW, "collection-next"));
        }
    }

    private ItemStack icon(ArtifactDef def, boolean found) {
        ItemStack item = new ItemStack(found ? def.material() : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        if (!found) {
            meta.displayName(plugin.messages().get("collection-unknown-name"));
            meta.lore(List.of(plugin.messages().get("collection-unknown-lore")));
            item.setItemMeta(meta);
            return item;
        }
        meta.displayName(plugin.messages().get("collection-entry-name",
                "name", Messages.trusted(plugin.items().nameOf(def))));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get(kindKey(def)));
        String desc = ArtifactText.loreOrEmpty(
                plugin.messages().raw("art." + def.id() + ".lore"));
        if (!desc.isEmpty()) {
            lore.add(plugin.messages().get("lore-desc", "text", Messages.trusted(desc)));
        }
        if (def.mode() == ArtifactDef.Mode.ACTIVE) {
            lore.add(plugin.messages().get("collection-entry-active",
                    "cooldown", String.valueOf(plugin.config().cooldownSeconds(def)),
                    "xp", String.valueOf(plugin.config().xpCostLevels(def))));
        }
        lore.add(plugin.messages().get("collection-entry-found"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String kindKey(ArtifactDef def) {
        return switch (def.side()) {
            case GOOD -> def.mode() == ArtifactDef.Mode.ACTIVE
                    ? "collection-entry-good-active" : "collection-entry-good-passive";
            case CURSED -> def.mode() == ArtifactDef.Mode.ACTIVE
                    ? "collection-entry-cursed-active" : "collection-entry-cursed-passive";
        };
    }

    private ItemStack progressIcon(Player player) {
        int found = plugin.collection().count(player);
        int total = plugin.collection().total();
        int percent = CollectionRewards.percentOf(found, total);
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get("collection-progress-name",
                "found", String.valueOf(found),
                "total", String.valueOf(total),
                "percent", String.valueOf(percent)));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("collection-progress-line",
                "bar", plugin.collection().progressBar(player),
                "percent", String.valueOf(percent)));
        lore.add(plugin.messages().get("collection-progress-hint"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack rewardsIcon(Player player) {
        ItemStack item = new ItemStack(Material.DIAMOND);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get("collection-rewards-name"));
        Set<Integer> claimed = plugin.collection().claimed(player);
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (CollectionRewards.Tier tier : plugin.config().collection().rewards()) {
            boolean done = claimed.contains(tier.percent());
            lore.add(plugin.messages().get("collection-reward-line",
                    "percent", String.valueOf(tier.percent()),
                    "reward", Messages.trusted(plugin.collection().rewardText(tier)),
                    "status", Messages.trusted(plugin.messages().raw(done
                            ? "collection-reward-claimed" : "collection-reward-pending"))));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack button(Material material, String key) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get(key));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        // Любой клик по коллекции отменяется: GUI только для чтения.
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
            case SLOT_PREV -> open(player, holder.page - 1);
            case SLOT_NEXT -> open(player, holder.page + 1);
            case SLOT_CLOSE -> player.closeInventory();
            default -> {
                // иконки артефактов, прогресс и награды — информационные
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder)) {
            return;
        }
        event.setCancelled(true);
    }

    private static final class Holder implements InventoryHolder {

        private final UUID owner;
        private final int page;
        private Inventory inventory;

        private Holder(UUID owner, int page) {
            this.owner = owner;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
