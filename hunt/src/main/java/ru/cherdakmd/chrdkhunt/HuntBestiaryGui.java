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
 * Бестиарий: меню разделов и журнал видов (1.4.159).
 *
 * <p>Первый экран — семейства с прогрессом и наградой, второй — виды раздела.
 * Неоткрытый вид показан серой краской без имени: журнал должен заставлять
 * искать, а не выдавать список заранее. Открытый вид показывает, сколько раз
 * он побеждён, и помечает боссов.</p>
 *
 * <p>Меню только показывает и передаёт нажатие: закрытие раздела и выдача
 * награды живут в {@link HuntService}, поэтому клик не может выдать награду
 * дважды.</p>
 */
public final class HuntBestiaryGui implements Listener {

    /** Сетка разделов: четыре строки по семь. */
    private static final int[] SECTION_SLOTS = {10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};
    /** Сетка видов: пять строк по семь — столько же, сколько влезает в раздел. */
    private static final int[] ENTRY_SLOTS = {10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43, 46, 47, 48, 49, 50, 51, 52};

    private static final int INFO_SLOT = 4;
    private static final int BACK_SLOT = 45;
    private static final int REWARD_SLOT = 49;
    private static final int CLOSE_SLOT = 53;
    private static final int EMPTY_SLOT = 22;
    private static final int SIZE = 54;

    /** Владелец меню разделов: чужой игрок не может нажимать кнопки. */
    static final class SectionsHolder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        SectionsHolder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Владелец журнала раздела и порядок видов на экране. */
    static final class SectionHolder implements InventoryHolder {
        private final UUID owner;
        private final String sectionId;
        private final List<String> entryKeys;
        private Inventory inventory;

        SectionHolder(UUID owner, String sectionId, List<String> entryKeys) {
            this.owner = owner;
            this.sectionId = sectionId;
            this.entryKeys = List.copyOf(entryKeys);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final HuntPlugin plugin;

    public HuntBestiaryGui(HuntPlugin plugin) {
        this.plugin = plugin;
    }

    /* -------------------------------- открытие -------------------------------- */

    /** Меню разделов; {@code false} — бестиарий выключен или пуст. */
    public boolean open(Player player) {
        if (player == null) {
            return false;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "disabled");
            return false;
        }
        HuntBestiary bestiary = plugin.config().bestiary();
        if (bestiary == null || bestiary.empty()) {
            plugin.messages().send(player, "bestiary-empty");
            return false;
        }
        SectionsHolder holder = new SectionsHolder(player.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("bestiary-title",
                        "seen", String.valueOf(plugin.service().bestiarySeen(player)),
                        "total", String.valueOf(bestiary.totalEntries())));
        List<HuntBestiary.Section> sections = bestiary.sections();
        for (int index = 0; index < Math.min(SECTION_SLOTS.length, sections.size()); index++) {
            holder.inventory.setItem(SECTION_SLOTS[index], sectionIcon(player, sections.get(index)));
        }
        holder.inventory.setItem(INFO_SLOT, infoIcon(player, bestiary));
        holder.inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "menu-close"));
        player.openInventory(holder.inventory);
        return true;
    }

    /** Журнал раздела; {@code false} — раздела нет или бестиарий выключен. */
    public boolean openSection(Player player, String sectionId) {
        if (player == null) {
            return false;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "disabled");
            return false;
        }
        HuntBestiary bestiary = plugin.config().bestiary();
        HuntBestiary.Section section = bestiary == null ? null : bestiary.section(sectionId);
        if (section == null) {
            plugin.messages().send(player, "bestiary-unknown-section",
                    "id", sectionId == null ? "" : sectionId);
            return false;
        }
        List<String> keys = new ArrayList<>();
        for (HuntBestiary.Entry entry : section.entries()) {
            keys.add(entry.key());
        }
        SectionHolder holder = new SectionHolder(player.getUniqueId(), section.id(), keys);
        HuntBestiary.Progress progress = plugin.service().bestiaryProgress(player, section);
        holder.inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("bestiary-section-title",
                        "section", section.displayName(),
                        "seen", String.valueOf(progress.seen()),
                        "total", String.valueOf(progress.total())));
        for (int index = 0; index < Math.min(ENTRY_SLOTS.length, section.entries().size()); index++) {
            holder.inventory.setItem(ENTRY_SLOTS[index], entryIcon(player,
                    section.entries().get(index)));
        }
        holder.inventory.setItem(INFO_SLOT, sectionInfoIcon(player, section, progress));
        holder.inventory.setItem(BACK_SLOT, button(Material.ARROW, "bestiary-back"));
        holder.inventory.setItem(REWARD_SLOT, rewardIcon(player, section, progress));
        holder.inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "menu-close"));
        player.openInventory(holder.inventory);
        return true;
    }

    /** Перерисовывает открытое меню на следующем тике (после награды). */
    public void reopen(Player player, String sectionId) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sectionId == null || sectionId.isBlank()) {
                open(player);
            } else {
                openSection(player, sectionId);
            }
        });
    }

    /* --------------------------------- иконки --------------------------------- */

    private ItemStack infoIcon(Player player, HuntBestiary bestiary) {
        int seen = plugin.service().bestiarySeen(player);
        int total = bestiary.totalEntries();
        int sections = 0;
        int closed = 0;
        for (HuntBestiary.Section section : bestiary.sections()) {
            sections++;
            if (plugin.service().bestiaryProgress(player, section).complete()) {
                closed++;
            }
        }
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("bestiary-info-seen",
                "seen", String.valueOf(seen), "total", String.valueOf(total)));
        lore.add(plugin.messages().get("bestiary-info-sections",
                "closed", String.valueOf(closed), "sections", String.valueOf(sections)));
        if (bestiary.fullCoins() > 0) {
            lore.add(plugin.messages().get("bestiary-info-full",
                    "coins", String.valueOf(bestiary.fullCoins())));
        }
        if (player.hasPermission("chrdkhunt.use") && plugin.coreApi() != null && plugin.coreApi().isAuthenticated(player)) {
            lore.add(plugin.messages().get("mob-bestiary-levels", "levels", MobReport.numbers(plugin.store().observedLevels(player.getUniqueId()))));
            lore.add(plugin.messages().get("mob-bestiary-elites", "count", plugin.store().observedElites(player.getUniqueId()).size()));
        }
        lore.add(plugin.messages().get("bestiary-info-hint"));
        return plugin.items().icon(Material.WRITTEN_BOOK,
                plugin.messages().get("bestiary-info-name"), lore);
    }

    private ItemStack sectionIcon(Player player, HuntBestiary.Section section) {
        if (section == null) {
            return new ItemStack(Material.PAPER);
        }
        HuntBestiary.Progress progress = plugin.service().bestiaryProgress(player, section);
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("bestiary-section-progress",
                "seen", String.valueOf(progress.seen()),
                "total", String.valueOf(progress.total()),
                "percent", String.valueOf(progress.percent())));
        lore.add(plugin.messages().get("bestiary-section-reward",
                "reward", Messages.trusted(plugin.service().rewardText(section))));
        if (progress.complete()) {
            if (plugin.service().bestiaryRewarded(player, section.id())) {
                lore.add(plugin.messages().get("bestiary-section-claimed"));
            } else {
                lore.add(plugin.messages().get("bestiary-section-ready"));
            }
        } else {
            lore.add(plugin.messages().get("bestiary-section-locked",
                    "seen", String.valueOf(progress.seen()),
                    "total", String.valueOf(progress.total())));
        }
        lore.add(plugin.messages().get("bestiary-section-open"));
        Material icon = sectionIconMaterial(section);
        return plugin.items().icon(icon,
                plugin.messages().get("bestiary-section-name",
                        "section", section.displayName(),
                        "seen", String.valueOf(progress.seen()),
                        "total", String.valueOf(progress.total())),
                lore);
    }

    private Material sectionIconMaterial(HuntBestiary.Section section) {
        HuntFamily family = plugin.config().family(section.id());
        if (family == null || !HuntMaterials.isItem(family.shardItem())) {
            return Material.WRITTEN_BOOK;
        }
        return family.shardItem();
    }

    private ItemStack sectionInfoIcon(Player player, HuntBestiary.Section section,
                                      HuntBestiary.Progress progress) {
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("bestiary-section-progress",
                "seen", String.valueOf(progress.seen()),
                "total", String.valueOf(progress.total()),
                "percent", String.valueOf(progress.percent())));
        lore.add(plugin.messages().get("bestiary-section-reward",
                "reward", Messages.trusted(plugin.service().rewardText(section))));
        return plugin.items().icon(sectionIconMaterial(section),
                plugin.messages().get("bestiary-section-name",
                        "section", section.displayName(),
                        "seen", String.valueOf(progress.seen()),
                        "total", String.valueOf(progress.total())),
                lore);
    }

    private ItemStack entryIcon(Player player, HuntBestiary.Entry entry) {
        long kills = plugin.service().bestiaryKills(player, entry.key());
        List<Component> lore = new ArrayList<>();
        if (kills > 0L) {
            lore.add(plugin.messages().get("bestiary-entry-kills",
                    "kills", String.valueOf(kills)));
            if (entry.boss()) {
                lore.add(plugin.messages().get("bestiary-entry-boss"));
            }
            return plugin.items().icon(entry.icon(),
                    plugin.messages().get("bestiary-entry-name",
                            "mob", Messages.trusted(entry.translationTag())),
                    lore);
        }
        lore.add(plugin.messages().get("bestiary-entry-locked"));
        return plugin.items().icon(Material.GRAY_DYE,
                plugin.messages().get("bestiary-entry-unknown-name"), lore);
    }

    private ItemStack rewardIcon(Player player, HuntBestiary.Section section,
                                 HuntBestiary.Progress progress) {
        List<Component> lore = new ArrayList<>();
        boolean rewarded = plugin.service().bestiaryRewarded(player, section.id());
        Material material;
        String nameKey;
        if (!progress.complete()) {
            material = Material.BARRIER;
            nameKey = "bestiary-reward-locked-name";
            lore.add(plugin.messages().get("bestiary-reward-locked",
                    "seen", String.valueOf(progress.seen()),
                    "total", String.valueOf(progress.total())));
        } else if (!rewarded) {
            material = Material.CHEST;
            nameKey = "bestiary-reward-ready-name";
            lore.add(plugin.messages().get("bestiary-reward-ready"));
        } else {
            material = Material.ENDER_CHEST;
            nameKey = "bestiary-reward-claimed-name";
            lore.add(plugin.messages().get("bestiary-reward-claimed-lore"));
        }
        lore.add(plugin.messages().get("bestiary-reward-value",
                "reward", Messages.trusted(plugin.service().rewardText(section))));
        return plugin.items().icon(material, plugin.messages().get(nameKey), lore);
    }

    private ItemStack button(Material material, String nameKey) {
        return plugin.items().icon(material, plugin.messages().get(nameKey), List.of());
    }

    /* --------------------------------- клики ---------------------------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder top = event.getView().getTopInventory().getHolder();
        boolean sections = top instanceof SectionsHolder;
        boolean section = top instanceof SectionHolder;
        if (!sections && !section) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        UUID owner = sections ? ((SectionsHolder) top).owner : ((SectionHolder) top).owner;
        if (!owner.equals(player.getUniqueId())) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (sections) {
            handleSections(player, slot);
        } else {
            handleSection(player, (SectionHolder) top, slot);
        }
    }

    private void handleSections(Player player, int slot) {
        int index = slotIndex(SECTION_SLOTS, slot);
        if (index < 0) {
            return;
        }
        List<HuntBestiary.Section> sections = plugin.config().bestiary().sections();
        if (index >= sections.size()) {
            return;
        }
        openSection(player, sections.get(index).id());
    }

    private void handleSection(Player player, SectionHolder holder, int slot) {
        if (slot == BACK_SLOT) {
            open(player);
            return;
        }
        if (slot == REWARD_SLOT) {
            if (plugin.service().claimBestiary(player, holder.sectionId)) {
                reopen(player, holder.sectionId);
            }
            return;
        }
        int index = slotIndex(ENTRY_SLOTS, slot);
        if (index < 0 || index >= holder.entryKeys.size()) {
            return;
        }
        // Клик по виду показывает, сколько раз он побеждён, — без перехода.
        HuntBestiary bestiary = plugin.config().bestiary();
        HuntBestiary.Entry entry = bestiary.entry(holder.entryKeys.get(index));
        if (entry == null) {
            return;
        }
        long kills = plugin.service().bestiaryKills(player, entry.key());
        if (kills <= 0L) {
            plugin.messages().send(player, "bestiary-entry-hint-unknown");
        } else {
            plugin.messages().send(player, "bestiary-entry-hint-known",
                    "mob", Messages.trusted(entry.translationTag()),
                    "kills", String.valueOf(kills));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder top = event.getView().getTopInventory().getHolder();
        if (top instanceof SectionsHolder || top instanceof SectionHolder) {
            event.setCancelled(true);
        }
    }

    private static int slotIndex(int[] slots, int slot) {
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] == slot) {
                return index;
            }
        }
        return -1;
    }

    /** Закрывает все меню бестиария — при выключении и перезагрузке. */
    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof SectionsHolder || holder instanceof SectionHolder) {
                player.closeInventory();
            }
        }
    }
}
