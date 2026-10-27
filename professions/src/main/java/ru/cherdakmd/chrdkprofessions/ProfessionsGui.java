package ru.cherdakmd.chrdkprofessions;

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
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Меню профессий: выбор ремесла и карточка (1.4.165).
 *
 * <p>Два экрана: главный (кто я сейчас и четыре ремесла) и карточка выбранной
 * профессии с подтверждением. Меню ничего не решает само: клик зовёт
 * {@link ProfessionService}, поэтому выбрать профессию в обход кулдауна нельзя
 * ни из чата, ни из GUI.</p>
 *
 * <p>Класс игрока берётся из владельца инвентаря, а игрок — из события: GUI
 * один на плагин, и держать в нём «текущего игрока» значило бы смешать двух
 * людей, открывших меню одновременно.</p>
 */
final class ProfessionsGui implements Listener {

    private static final int SIZE = 54;
    private static final int SUMMARY_SLOT = 4;
    private static final int MASTERY_SLOT = 40;
    /** Четыре профессии в ряд: слоты 19, 21, 23, 25. */
    private static final int[] PROFESSION_SLOTS = {19, 21, 23, 25};
    private static final int CONFIRM_SLOT = 31;
    private static final int BACK_SLOT = 45;
    private static final int CLOSE_SLOT = 49;

    enum Screen {
        MAIN,
        INFO, MASTERY, MASTERY_CONFIRM
    }

    static final class Holder implements InventoryHolder {
        private final UUID owner;
        private final Screen screen;
        private final String professionId;
        private Inventory inventory;

        Holder(UUID owner, Screen screen, String professionId) {
            this.owner = owner;
            this.screen = screen;
            this.professionId = professionId == null ? "" : professionId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final ProfessionsPlugin plugin;

    ProfessionsGui(ProfessionsPlugin plugin) {
        this.plugin = plugin;
    }

    /* --------------------------------- экраны --------------------------------- */

    /** Главное меню: текущая профессия и четыре варианта. */
    void open(Player player) {
        if (player == null) {
            return;
        }
        Holder holder = new Holder(player.getUniqueId(), Screen.MAIN, "");
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("menu-title"));
        holder.inventory = inventory;

        inventory.setItem(SUMMARY_SLOT, summaryItem(player));
        Profession[] values = Profession.values();
        for (int index = 0; index < PROFESSION_SLOTS.length && index < values.length; index++) {
            inventory.setItem(PROFESSION_SLOTS[index], professionItem(player, values[index]));
        }
        inventory.setItem(MASTERY_SLOT, button(Material.SMITHING_TABLE, "mastery-menu", List.of(plugin.messages().get("mastery-menu-hint"))));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "menu-close", List.of()));
        player.openInventory(inventory);
    }

    /** Карточка профессии с подтверждением выбора. */
    void openInfo(Player player, Profession profession) {
        if (player == null || profession == null) {
            return;
        }
        Holder holder = new Holder(player.getUniqueId(), Screen.INFO, profession.id());
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("info-title", "profession",
                        ProfessionText.nameComponent(plugin.messages(), profession)));
        holder.inventory = inventory;

        inventory.setItem(SUMMARY_SLOT, professionItem(player, profession));
        inventory.setItem(CONFIRM_SLOT, confirmItem(player, profession));
        inventory.setItem(BACK_SLOT, button(Material.ARROW, "menu-back", List.of()));
        inventory.setItem(MASTERY_SLOT, button(Material.SMITHING_TABLE, "mastery-menu", List.of(plugin.messages().get("mastery-menu-hint"))));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "menu-close", List.of()));
        player.openInventory(inventory);
    }

    void openMastery(Player player) {
        Profession profession = plugin.service().active(player.getUniqueId());
        if (profession == null) { plugin.messages().send(player, "mastery-no-profession"); return; }
        Holder holder = new Holder(player.getUniqueId(), Screen.MASTERY, profession.id());
        holder.inventory = Bukkit.createInventory(holder, SIZE, plugin.messages().get("mastery-menu"));
        holder.inventory.setItem(SUMMARY_SLOT, professionItem(player, profession));
        var choices = ProfessionMastery.of(profession);
        for (int i = 0; i < choices.size(); i++) holder.inventory.setItem(20 + i * 4, masteryItem(player, choices.get(i)));
        ProfessionMastery current = plugin.service().mastery(player.getUniqueId());
        List<Component> lore = new ArrayList<>();
        if (current == null) lore.add(plugin.messages().get("mastery-needed"));
        else {
            lore.add(plugin.messages().get(current.descKey()));
            lore.add(plugin.messages().get(current.costKey()));
            long left = plugin.service().workCooldownLeft(player.getUniqueId(), System.currentTimeMillis());
            lore.add(plugin.messages().get(left > 0 ? "work-cooldown" : "mastery-ready", "time", ProfessionMath.clock(left)));
        }
        holder.inventory.setItem(CONFIRM_SLOT, button(Material.CRAFTING_TABLE, "work-button", lore));
        holder.inventory.setItem(BACK_SLOT, button(Material.ARROW, "menu-back", List.of()));
        holder.inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "menu-close", List.of()));
        player.openInventory(holder.inventory);
    }

    void openMasteryConfirm(Player player, ProfessionMastery choice) {
        Holder holder = new Holder(player.getUniqueId(), Screen.MASTERY_CONFIRM, choice.id());
        holder.inventory = Bukkit.createInventory(holder, SIZE, plugin.messages().get("mastery-confirm"));
        holder.inventory.setItem(SUMMARY_SLOT, masteryItem(player, choice));
        holder.inventory.setItem(CONFIRM_SLOT, button(Material.LIME_DYE, "mastery-confirm", List.of(plugin.messages().get("mastery-confirm-hint"))));
        holder.inventory.setItem(BACK_SLOT, button(Material.ARROW, "menu-back", List.of()));
        holder.inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "menu-close", List.of()));
        player.openInventory(holder.inventory);
    }

    private ItemStack masteryItem(Player player, ProfessionMastery choice) {
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get(choice.descKey()));
        lore.add(plugin.messages().get(choice.costKey()));
        lore.add(plugin.messages().get("mastery-requirement", "level", ProfessionMastery.UNLOCK_LEVEL));
        lore.add(plugin.messages().get("mastery-interval", "time", ProfessionMath.clock(choice.cooldownSeconds())));
        if (choice == plugin.service().mastery(player.getUniqueId())) lore.add(plugin.messages().get("mastery-active"));
        else lore.add(plugin.messages().get("mastery-pick"));
        return button(material(choice.icon()), choice.nameKey(), lore);
    }

    /* --------------------------------- предметы -------------------------------- */

    private ItemStack summaryItem(Player player) {
        Profession active = plugin.service().active(player.getUniqueId());
        if (active == null) {
            List<Component> lore = new ArrayList<>();
            lore.add(plugin.messages().get("menu-current-none"));
            lore.add(plugin.messages().get("menu-current-hint"));
            return button(Material.BARRIER, "menu-current", lore);
        }
        return professionItem(player, active);
    }

    private ItemStack professionItem(Player player, Profession profession) {
        UUID id = player.getUniqueId();
        int level = plugin.service().level(id, profession);
        int xp = plugin.service().xp(id, profession);
        int maxLevel = plugin.service().maxLevel(id);
        int needed = ProfessionMath.needed(level, maxLevel);
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("profession." + profession.id() + ".desc"));
        lore.add(Component.empty());
        lore.add(plugin.messages().get("menu-profession-level",
                "level", String.valueOf(level),
                "rank", ProfessionText.rankComponent(plugin.messages(), level)));
        if (needed > 0) {
            int percent = ProfessionMath.progressPercent(xp, maxLevel);
            lore.add(plugin.messages().get("menu-profession-xp",
                    "xp", String.valueOf(ProfessionMath.progress(xp, maxLevel)),
                    "needed", String.valueOf(needed),
                    "percent", String.valueOf(percent)));
            lore.add(plugin.messages().get("menu-progress-bar", "bar", ProfessionText.bar(percent)));
        } else {
            lore.add(plugin.messages().get("menu-profession-max"));
        }
        lore.add(plugin.messages().get("menu-profession-perk",
                "perk", ProfessionText.perk(plugin.messages(), profession, level, maxLevel)));
        String next = ProfessionText.nextStepRaw(plugin.messages(), profession, level, maxLevel);
        if (!next.isBlank()) {
            lore.add(plugin.messages().get("menu-profession-next", "next", Messages.trusted(next)));
        }
        lore.add(plugin.messages().get("menu-profession-hint",
                "hint", ProfessionText.hintComponent(plugin.messages(), profession)));
        lore.add(Component.empty());
        lore.add(profession == plugin.service().active(id)
                ? plugin.messages().get("menu-profession-current")
                : plugin.messages().get("menu-profession-click"));
        return button(material(profession.iconName()),
                "profession." + profession.id() + ".name", lore);
    }

    private ItemStack confirmItem(Player player, Profession profession) {
        UUID id = player.getUniqueId();
        Profession current = plugin.service().active(id);
        if (current == profession) {
            return button(Material.GRAY_DYE, "menu-confirm-name",
                    List.of(plugin.messages().get("menu-confirm-same")));
        }
        long left = plugin.service().chooseCooldownLeft(id, System.currentTimeMillis());
        if (left > 0) {
            return button(Material.CLOCK, "menu-confirm-name",
                    List.of(plugin.messages().get("menu-confirm-cooldown",
                            "time", ProfessionMath.clock(left))));
        }
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get(current == null
                ? "menu-confirm-lore" : "menu-confirm-change-lore"));
        if (current != null) {
            lore.add(plugin.messages().get("menu-confirm-keeps",
                    "profession", ProfessionText.nameComponent(plugin.messages(), current)));
        }
        return button(current == null ? Material.LIME_DYE : Material.ORANGE_DYE,
                "menu-confirm-name", lore);
    }

    /* ---------------------------------- клики --------------------------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.owner.equals(player.getUniqueId())
                || event.getRawSlot() >= SIZE) {
            return;
        }
        if (!player.hasPermission("chrdkprofessions.use")) return;
        int slot = event.getRawSlot();
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (holder.screen == Screen.MASTERY_CONFIRM) {
            if (slot == BACK_SLOT) openMastery(player);
            else if (slot == CONFIRM_SLOT) {
                ProfessionResult result = plugin.service().chooseMastery(player.getUniqueId(), holder.professionId, System.currentTimeMillis());
                plugin.messages().send(player, result.key(), ProfessionText.localise(plugin.messages(), result).toArray());
                if (result.ok()) plugin.requestSave();
                openMastery(player);
            }
            return;
        }
        if (holder.screen == Screen.MASTERY) {
            if (slot == BACK_SLOT) open(player);
            else if (slot == CONFIRM_SLOT) {
                player.closeInventory();
                Bukkit.getScheduler().runTask(plugin, () -> plugin.workshop().perform(player));
            } else {
                var choices = ProfessionMastery.of(Profession.parse(holder.professionId));
                if (slot == 20 || slot == 24) openMasteryConfirm(player, choices.get(slot == 20 ? 0 : 1));
            }
            return;
        }
        if (slot == MASTERY_SLOT) { openMastery(player); return; }
        if (holder.screen == Screen.INFO) {
            if (slot == BACK_SLOT) {
                open(player);
            } else if (slot == CONFIRM_SLOT) {
                confirm(player, holder.professionId);
            }
            return;
        }
        Profession[] values = Profession.values();
        for (int index = 0; index < PROFESSION_SLOTS.length && index < values.length; index++) {
            if (PROFESSION_SLOTS[index] == slot) {
                openInfo(player, values[index]);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private void confirm(Player player, String professionId) {
        ProfessionResult result = plugin.service().choose(player.getUniqueId(), professionId,
                System.currentTimeMillis());
        plugin.messages().send(player, result.key(),
                ProfessionText.localise(plugin.messages(), result).toArray());
        if (result.ok()) {
            plugin.requestSave();
            plugin.hint(player, Profession.parse(professionId));
        }
        open(player);
    }

    /* -------------------------------- помощники -------------------------------- */

    private ItemStack button(Material material, String nameKey, List<Component> lore,
                             Object... placeholders) {
        ItemStack stack = new ItemStack(material == null ? Material.PAPER : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.messages().get(nameKey, placeholders));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static Material material(String name) {
        Material found = name == null ? null
                : Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        return found == null ? Material.PAPER : found;
    }
}
