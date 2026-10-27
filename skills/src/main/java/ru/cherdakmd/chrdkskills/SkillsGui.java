package ru.cherdakmd.chrdkskills;

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
 * Витрина навыков (`/skills`).
 *
 * <p>Два экрана: общий — десять навыков иконками по группам, и карточка
 * навыка — уровень, полоса опыта, все шесть перков с отметкой «работает /
 * откроется на N». Клик по навыку открывает карточку, клик по перкам ничего не
 * меняет: перки нельзя «купить», они открываются уровнем.</p>
 *
 * <p>Иконки навыков не показывают чужие данные и не позволяют таскать предметы:
 * все клики по верхнему инвентарю отменяются, драг запрещён.</p>
 */
public final class SkillsGui implements Listener {

    private static final int[] SKILL_SLOTS = {10, 11, 12, 13, 14, 19, 20, 21, 28, 29};
    private static final int SLOT_TOP = 48;
    private static final int SLOT_SUMMARY = 49;
    private static final int SLOT_CLOSE = 50;
    private static final int DETAIL_BACK = 18;
    private static final int DETAIL_CLOSE = 26;
    private static final int[] PERK_SLOTS = {10, 11, 12, 13, 14, 15};

    private final SkillsPlugin plugin;
    private final SkillService service;

    public SkillsGui(SkillsPlugin plugin, SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /* --------------------------------- главный -------------------------------- */

    public void open(Player player) {
        if (player == null) {
            return;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "skills-disabled");
            return;
        }
        Holder holder = new Holder(player.getUniqueId(), null);
        holder.inventory = Bukkit.createInventory(holder, 54,
                plugin.messages().get("menu-title"));
        List<SkillType> skills = SkillRegistry.all();
        for (int index = 0; index < Math.min(SKILL_SLOTS.length, skills.size()); index++) {
            holder.inventory.setItem(SKILL_SLOTS[index], icon(player, skills.get(index)));
        }
        holder.inventory.setItem(SLOT_SUMMARY, summary(player));
        holder.inventory.setItem(SLOT_TOP, button(Material.GOLD_INGOT, "menu-top"));
        holder.inventory.setItem(SLOT_CLOSE, button(Material.BARRIER, "menu-close"));
        player.openInventory(holder.inventory);
    }

    /** Одна иконка навыка: уровень, опыт, ближайший перк. */
    private ItemStack icon(Player player, SkillType skill) {
        int level = service.level(player, skill);
        SkillCurve.LevelProgress progress = service.progress(player, skill);
        ItemStack item = new ItemStack(materialOf(skill));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get("menu-skill-name",
                "name", Messages.trusted(plugin.messages().raw("skill." + skill.id() + ".name")),
                "level", String.valueOf(level)));
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("menu-skill-progress",
                "bar", SkillCurve.bar(progress.percent(), 10),
                "current", String.valueOf(progress.into()),
                "need", String.valueOf(progress.need()),
                "percent", String.valueOf(progress.percent())));
        SkillPerk next = SkillPerks.nextPerk(skill, level);
        if (next != null) {
            lore.add(plugin.messages().get("menu-skill-next",
                    "level", String.valueOf(next.level()),
                    "perk", Messages.trusted(plugin.messages().raw("perk." + next.id() + ".name"))));
        } else {
            lore.add(plugin.messages().get("menu-skill-max"));
        }
        lore.add(plugin.messages().get("menu-skill-click"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack summary(Player player) {
        int total = service.totalLevel(player);
        int unlocked = 0;
        int all = 0;
        for (SkillType skill : SkillRegistry.all()) {
            int level = service.level(player, skill);
            for (SkillPerk perk : SkillRegistry.perksOf(skill)) {
                all++;
                if (level >= perk.level()) {
                    unlocked++;
                }
            }
        }
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get("menu-summary-name",
                "total", String.valueOf(total)));
        meta.lore(List.of(
                plugin.messages().get("menu-summary-perks",
                        "unlocked", String.valueOf(unlocked),
                        "all", String.valueOf(all)),
                plugin.messages().get("menu-summary-xp",
                        "xp", String.valueOf(service.totalXp(player))),
                plugin.messages().get("ability-energy", "energy", plugin.abilities().energy(player.getUniqueId()), "max", SkillAbility.MAX_ENERGY)));
        item.setItemMeta(meta);
        return item;
    }

    /* --------------------------------- карточка -------------------------------- */

    public void openSkill(Player player, SkillType skill) {
        if (player == null || skill == null) {
            return;
        }
        Holder holder = new Holder(player.getUniqueId(), skill);
        int level = service.level(player, skill);
        holder.inventory = Bukkit.createInventory(holder, 27,
                plugin.messages().get("detail-title",
                        "name", Messages.trusted(plugin.messages().raw("skill." + skill.id() + ".name"))));
        SkillCurve.LevelProgress progress = service.progress(player, skill);
        ItemStack head = icon(player, skill);
        ItemMeta headMeta = head.getItemMeta();
        headMeta.lore(List.of(
                plugin.messages().get("detail-progress",
                        "bar", SkillCurve.bar(progress.percent(), 10),
                        "percent", String.valueOf(progress.percent())),
                plugin.messages().get("detail-xp",
                        "into", String.valueOf(progress.into()),
                        "need", String.valueOf(progress.need()))));
        head.setItemMeta(headMeta);
        holder.inventory.setItem(4, head);
        List<SkillPerk> perks = SkillRegistry.perksOf(skill);
        for (int index = 0; index < Math.min(PERK_SLOTS.length, perks.size()); index++) {
            SkillPerk perk = perks.get(index);
            holder.inventory.setItem(PERK_SLOTS[index], perkIcon(perk, level));
        }
        SkillAbility ability = SkillAbility.of(skill);
        if (ability != null) holder.inventory.setItem(22, abilityIcon(player, ability));
        holder.inventory.setItem(DETAIL_BACK, button(Material.ARROW, "detail-back"));
        holder.inventory.setItem(DETAIL_CLOSE, button(Material.BARRIER, "menu-close"));
        player.openInventory(holder.inventory);
    }

    private ItemStack abilityIcon(Player player, SkillAbility ability) {
        ItemStack item = new ItemStack(Material.BLAZE_POWDER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get(ability.nameKey()));
        meta.lore(List.of(plugin.messages().get(ability.descKey()),
                plugin.messages().get("ability-card", "level", SkillAbility.UNLOCK_LEVEL, "cost", ability.cost(),
                        "seconds", ability.seconds(), "cooldown", ability.cooldown()),
                plugin.messages().get("ability-energy", "energy", plugin.abilities().energy(player.getUniqueId()), "max", SkillAbility.MAX_ENERGY),
                plugin.messages().get("ability-card-ready", "time", plugin.abilities().cooldown(player.getUniqueId(), ability)),
                plugin.messages().get("ability-card-click")));
        item.setItemMeta(meta); return item;
    }

    private ItemStack perkIcon(SkillPerk perk, int level) {
        boolean active = level >= perk.level();
        ItemStack item = new ItemStack(active ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get(active ? "perk-name-active" : "perk-name-locked",
                "name", Messages.trusted(plugin.messages().raw("perk." + perk.id() + ".name")),
                "level", String.valueOf(perk.level())));
        meta.lore(List.of(
                plugin.messages().get("perk-desc",
                        "text", Messages.trusted(plugin.messages().raw("perk." + perk.id() + ".desc"))),
                plugin.messages().get(active ? "perk-active" : "perk-locked")));
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

    static Material materialOf(SkillType skill) {
        return switch (skill) {
            case MINING -> Material.IRON_PICKAXE;
            case WOODCUTTING -> Material.IRON_AXE;
            case DIGGING -> Material.IRON_SHOVEL;
            case FARMING -> Material.WHEAT;
            case FISHING -> Material.FISHING_ROD;
            case COMBAT -> Material.IRON_SWORD;
            case ARCHERY -> Material.BOW;
            case DEFENSE -> Material.SHIELD;
            case ALCHEMY -> Material.BREWING_STAND;
            case ENCHANTING -> Material.ENCHANTING_TABLE;
        };
    }

    /* ---------------------------------- клики --------------------------------- */

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
        if (holder.skill == null) {
            if (slot == SLOT_CLOSE) {
                player.closeInventory();
                return;
            }
            if (slot == SLOT_TOP) {
                player.closeInventory();
                plugin.messages().send(player, "top-header");
                int place = 1;
                for (SkillService.Entry entry : service.top()) {
                    plugin.messages().send(player, "top-line",
                            "place", String.valueOf(place++),
                            "name", entry.name(),
                            "level", String.valueOf(entry.level()),
                            "xp", String.valueOf(entry.xp()));
                }
                return;
            }
            for (int index = 0; index < SKILL_SLOTS.length; index++) {
                if (slot == SKILL_SLOTS[index] && index < SkillRegistry.size()) {
                    openSkill(player, SkillRegistry.all().get(index));
                    return;
                }
            }
            return;
        }
        if (slot == 22 && SkillAbility.of(holder.skill) != null) {
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () -> plugin.abilities().activate(player, SkillAbility.of(holder.skill)));
        } else if (slot == DETAIL_BACK) {
            open(player);
        } else if (slot == DETAIL_CLOSE) {
            player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private static final class Holder implements InventoryHolder {

        private final UUID owner;
        private final SkillType skill;
        private Inventory inventory;

        private Holder(UUID owner, SkillType skill) {
            this.owner = owner;
            this.skill = skill;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
