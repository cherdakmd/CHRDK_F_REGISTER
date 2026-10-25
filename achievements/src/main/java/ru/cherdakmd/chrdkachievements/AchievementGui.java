package ru.cherdakmd.chrdkachievements;

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
 * Витрина достижений (`/achievements`).
 *
 * <p>45 слотов под достижения на страницу (в каталоге 72 записи: раздел
 * помещается целиком, «Все» листается стрелками), нижний ряд —
 * вкладки разделов, сводка прогресса и закрытие. Открытые достижения
 * показываются материалом своего раздела, закрытые — серым красителем с
 * прогресс-баром, чтобы игрок видел, сколько осталось.</p>
 *
 * <p>Клик по достижению с бейджем надевает или снимает его: это единственное
 * действие в GUI, остальное — только чтение. Все клики по верхнему инвентарю
 * отменяются.</p>
 *
 * <p>В шаблоны подставляется {@link Messages#trusted(String)}: имена и описания
 * уже несут разметку из messages.yml, а обычные плейсхолдеры её экранируют.</p>
 */
public final class AchievementGui implements Listener {

    private static final int SLOTS = 45;
    private static final int SLOT_PATH = 45;
    private static final int SLOT_COMBAT = 46;
    private static final int SLOT_CRAFT = 47;
    private static final int SLOT_SOCIAL = 48;
    private static final int SLOT_ALL = 49;
    private static final int SLOT_PROGRESS = 50;
    private static final int SLOT_PREV = 51;
    private static final int SLOT_NEXT = 52;
    private static final int SLOT_CLOSE = 53;
    /** Сколько достижений помещается на одну страницу. */
    private static final int PAGE_SIZE = SLOTS;

    private final AchievementsPlugin plugin;
    private final AchievementService service;

    public AchievementGui(AchievementsPlugin plugin, AchievementService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void open(Player player, AchievementDef.Category category) {
        open(player, category, 1);
    }

    /**
     * Открывает раздел на нужной странице.
     *
     * @param page номер страницы с 1; вне диапазона приводится к ближайшей
     */
    public void open(Player player, AchievementDef.Category category, int page) {
        if (player == null) {
            return;
        }
        if (!service.enabled()) {
            plugin.messages().send(player, "achievements-disabled");
            return;
        }
        List<AchievementDef> defs = service.byCategory(category);
        int pages = Math.max(1, (defs.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.clamp(page, 1, pages);
        Holder holder = new Holder(player.getUniqueId(), category, current);
        String categoryName = category == null
                ? plugin.messages().raw("category.all")
                : plugin.messages().raw("category." + category.id());
        holder.inventory = Bukkit.createInventory(holder, 54,
                plugin.messages().get("achievements-title",
                        "category", Messages.trusted(categoryName)));
        int from = (current - 1) * PAGE_SIZE;
        for (int index = from; index < Math.min(defs.size(), from + PAGE_SIZE); index++) {
            holder.inventory.setItem(index - from, icon(player, defs.get(index)));
        }
        holder.inventory.setItem(SLOT_PATH, tab(Material.GRASS_BLOCK, "tab-path",
                category == AchievementDef.Category.PATH));
        holder.inventory.setItem(SLOT_COMBAT, tab(Material.IRON_SWORD, "tab-combat",
                category == AchievementDef.Category.COMBAT));
        holder.inventory.setItem(SLOT_CRAFT, tab(Material.CRAFTING_TABLE, "tab-craft",
                category == AchievementDef.Category.CRAFT));
        holder.inventory.setItem(SLOT_SOCIAL, tab(Material.BELL, "tab-social",
                category == AchievementDef.Category.SOCIAL));
        holder.inventory.setItem(SLOT_ALL, tab(Material.COMPASS, "tab-all", category == null));
        holder.inventory.setItem(SLOT_PROGRESS, progressIcon(player));
        if (pages > 1) {
            holder.inventory.setItem(SLOT_PREV, pageButton(Material.ARROW, "page-prev",
                    current <= 1 ? pages : current - 1, current, pages));
            holder.inventory.setItem(SLOT_NEXT, pageButton(Material.ARROW, "page-next",
                    current >= pages ? 1 : current + 1, current, pages));
        }
        holder.inventory.setItem(SLOT_CLOSE, button(Material.BARRIER, "gui-close"));
        player.openInventory(holder.inventory);
    }

    private ItemStack icon(Player player, AchievementDef def) {
        boolean unlocked = service.isUnlocked(player, def.id());
        int percent = service.percent(def, player);
        ItemStack item = new ItemStack(unlocked ? materialOf(def.category()) : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get(unlocked ? "entry-unlocked-name" : "entry-locked-name",
                "name", Messages.trusted(nameOf(def))));
        List<Component> lore = new ArrayList<>();
        String desc = plugin.messages().raw("ach." + def.id() + ".desc");
        if (!desc.isBlank()) {
            lore.add(plugin.messages().get("entry-desc", "text", Messages.trusted(desc)));
        }
        lore.add(plugin.messages().get("entry-progress",
                "bar", AchievementRules.bar(percent, 10),
                "current", String.valueOf(AchievementRules.displayValue(
                        service.value(player, def.metric()), def.goal())),
                "goal", String.valueOf(def.goal()),
                "percent", String.valueOf(percent)));
        AchievementsConfig.Reward reward = plugin.config().rewardOf(def);
        if (reward.passDays() > 0 || reward.diamonds() > 0) {
            lore.add(plugin.messages().get("entry-reward",
                    "reward", Messages.trusted(service.rewardText(reward))));
        }
        if (unlocked) {
            lore.add(plugin.messages().get("entry-done"));
            if (def.badge() != null && plugin.config().badges()) {
                boolean worn = def.badge().equals(service.badgeOf(player));
                lore.add(plugin.messages().get(worn ? "entry-badge-worn" : "entry-badge-click"));
            }
        } else {
            lore.add(plugin.messages().get("entry-locked"));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String nameOf(AchievementDef def) {
        String raw = plugin.messages().raw("ach." + def.id() + ".name");
        return raw.isBlank() ? AchievementService.ArtifactTextFallback.humanize(def.id()) : raw;
    }

    private ItemStack progressIcon(Player player) {
        int unlocked = service.unlockedCount(player);
        int total = service.total();
        int percent = AchievementRules.percent(unlocked, total);
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get("progress-name",
                "unlocked", String.valueOf(unlocked), "total", String.valueOf(total)));
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("progress-line",
                "bar", AchievementRules.bar(percent, 10),
                "percent", String.valueOf(percent)));
        AchievementDef next = service.nextGoal(player);
        if (next != null) {
            lore.add(plugin.messages().get("progress-next",
                    "name", Messages.trusted(nameOf(next))));
        }
        String badge = service.badgeText(player);
        if (badge != null) {
            lore.add(plugin.messages().get("progress-badge", "badge", Messages.trusted(badge)));
        } else if (plugin.config().badges()) {
            lore.add(plugin.messages().get("progress-badge-none"));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack pageButton(Material material, String key, int target, int page, int pages) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get(key, "page", String.valueOf(target),
                "current", String.valueOf(page), "pages", String.valueOf(pages)));
        meta.lore(List.of(plugin.messages().get("page-info",
                "current", String.valueOf(page), "pages", String.valueOf(pages))));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack tab(Material material, String key, boolean active) {
        ItemStack item = new ItemStack(active ? material : Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get(key));
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

    private static Material materialOf(AchievementDef.Category category) {
        return switch (category) {
            case PATH -> Material.GRASS_BLOCK;
            case COMBAT -> Material.IRON_SWORD;
            case CRAFT -> Material.CRAFTING_TABLE;
            case SOCIAL -> Material.BELL;
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
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= holder.inventory.getSize()) {
            return;
        }
        switch (slot) {
            case SLOT_PATH -> open(player, AchievementDef.Category.PATH);
            case SLOT_COMBAT -> open(player, AchievementDef.Category.COMBAT);
            case SLOT_CRAFT -> open(player, AchievementDef.Category.CRAFT);
            case SLOT_SOCIAL -> open(player, AchievementDef.Category.SOCIAL);
            case SLOT_ALL -> open(player, null, holder.page);
            case SLOT_PREV -> open(player, holder.category, holder.page - 1);
            case SLOT_NEXT -> open(player, holder.category, holder.page + 1);
            case SLOT_CLOSE -> player.closeInventory();
            default -> {
                if (slot < SLOTS) {
                    List<AchievementDef> defs = service.byCategory(holder.category);
                    int index = (holder.page - 1) * PAGE_SIZE + slot;
                    if (index >= 0 && index < defs.size()) {
                        wearBadge(player, defs.get(index), holder.category, holder.page);
                    }
                }
            }
        }
    }

    /** Клик по открытому достижению с бейджем надевает или снимает его. */
    private void wearBadge(Player player, AchievementDef def, AchievementDef.Category category, int page) {
        if (def.badge() == null || !plugin.config().badges()) {
            return;
        }
        if (!service.isUnlocked(player, def.id())) {
            return;
        }
        boolean wearing = def.badge().equals(service.badgeOf(player));
        String error = service.setBadge(player, wearing ? null : def.id());
        if (error != null) {
            plugin.messages().send(player, error);
            return;
        }
        plugin.messages().send(player, wearing ? "badge-removed" : "badge-worn",
                "name", Messages.trusted(nameOf(def)));
        open(player, category, page);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private static final class Holder implements InventoryHolder {

        private final UUID owner;
        private final AchievementDef.Category category;
        private final int page;
        private Inventory inventory;

        private Holder(UUID owner, AchievementDef.Category category, int page) {
            this.owner = owner;
            this.category = category;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
