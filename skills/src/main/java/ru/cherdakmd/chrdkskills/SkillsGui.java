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
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.cherdakmd.chrdkregister.Messages;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Единое меню {@code /skills}: один инвентарь на 54 слота, шесть вкладок.
 *
 * <p>Вкладки «Навыки», «Атрибуты», «Топ», «Перерождение», «Кристаллы опыта» и
 * «Ветки билда» переключаются кликом по верхнему ряду: {@link #render} просто
 * очищает слоты и рисует их заново, не создавая второго инвентаря и не вызывая
 * {@code openInventory} повторно. Карточка навыка — тоже состояние вкладки
 * «Навыки», а не отдельное окно на 27 слотов.</p>
 *
 * <p>Иконки не показывают чужие данные и не позволяют таскать предметы: все
 * клики по верхнему инвентарю отменяются, драг запрещён.</p>
 */
public final class SkillsGui implements Listener, SkillsMenuApi {

    /** Верхний ряд — вкладки и общие кнопки. */
    private static final int TAB_SKILLS = 0;
    private static final int TAB_ATTRIBUTES = 1;
    private static final int TAB_TOP = 2;
    private static final int SLOT_GUIDE = 3;
    private static final int TAB_REBIRTH = 5;
    private static final int SLOT_CONTEXT = 4;
    private static final int TAB_POTIONS = 6;
    private static final int TAB_BUILDS = 7;
    private static final int SLOT_CLOSE = 8;

    /**
     * Симметричная раскладка: 10 навыков — два ряда по 5 (слоты 11–15 и
     * 20–24), 12 ступеней — два ряда по 6 (10–15 и 19–24). Раньше ряды были
     * 5/3/2 и 7/5: на скриншоте это выглядело «лесенкой» и половина окна
     * пустовала. Все свободные слоты теперь закрываются рамкой.
     */
    private static final int[] SKILL_SLOTS = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};
    /** 12 ступеней перков вместо прежних шести. */
    private static final int[] PERK_SLOTS = {10, 11, 12, 13, 14, 15, 19, 20, 21, 22, 23, 24};
    private static final int DETAIL_HEAD = SLOT_CONTEXT;
    private static final int DETAIL_ABILITY = 31;
    private static final int DETAIL_BACK = 45;
    private static final int SLOT_SUMMARY = 49;

    /** Содержимое вкладки «Топ»: та же сетка 5/5, что и у навыков. */
    private static final int[] TOP_SLOTS = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};

    /** Пять атрибутов — один ряд по пять. */
    private static final int[] ATTR_SLOTS = {11, 12, 13, 14, 15};

    /** Шесть веток билда — два ряда по три. */
    private static final int[] BRANCH_SLOTS = {12, 13, 14, 21, 22, 23};

    /** Кнопка сброса распределения очков (правый нижний угол). */
    private static final int SLOT_RESET = 53;

    private final SkillsPlugin plugin;
    private final SkillService service;

    public SkillsGui(SkillsPlugin plugin, SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /* ---------------------------------- открытие --------------------------------- */

    /** {@code /skills}: витрина навыков. */
    public void open(Player player) {
        open(player, Tab.SKILLS, 0);
    }

    /**
     * Открыть меню. Если оно уже открыто у игрока — только сменить
     * вкладку/страницу и перерисовать слоты, без второго окна.
     */
    @Override
    public void open(Player player, Tab tab, int page) {
        if (player == null) {
            return;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "skills-disabled");
            return;
        }
        Holder holder = currentHolder(player);
        boolean fresh = holder == null;
        if (fresh) {
            holder = new Holder(player.getUniqueId());
            holder.inventory = Bukkit.createInventory(holder, 54,
                    plugin.messages().get("menu-title"));
        }
        holder.tab = tab == null ? Tab.SKILLS : tab;
        holder.page = Math.max(0, page);
        if (holder.tab != Tab.SKILLS) {
            holder.skill = null;
        }
        render(player, holder);
        if (fresh) {
            player.openInventory(holder.inventory);
        }
    }

    private Holder currentHolder(Player player) {
        InventoryView view = player.getOpenInventory();
        if (view == null) {
            return null;
        }
        if (view.getTopInventory().getHolder() instanceof Holder holder
                && holder.owner.equals(player.getUniqueId())) {
            return holder;
        }
        return null;
    }

    /* --------------------------------- отрисовка -------------------------------- */

    /** Перерисовать ВСЕ слоты одного и того же инвентаря. */
    private void render(Player player, Holder holder) {
        Inventory inventory = holder.inventory;
        inventory.clear();
        inventory.setItem(TAB_SKILLS, tabItem(holder, Tab.SKILLS, Material.IRON_PICKAXE,
                "menu-tab-skills"));
        inventory.setItem(TAB_ATTRIBUTES, tabItem(holder, Tab.ATTRIBUTES,
                Material.REDSTONE, "menu-tab-attrs"));
        inventory.setItem(TAB_TOP, tabItem(holder, Tab.TOP, Material.GOLD_INGOT,
                "menu-top"));
        inventory.setItem(TAB_REBIRTH, tabItem(holder, Tab.REBIRTH,
                Material.NETHERITE_INGOT, "menu-tab-rebirth"));
        inventory.setItem(TAB_POTIONS, tabItem(holder, Tab.POTIONS,
                Material.AMETHYST_SHARD, "menu-tab-potions"));
        inventory.setItem(TAB_BUILDS, tabItem(holder, Tab.BUILDS,
                Material.CRAFTING_TABLE, "menu-tab-builds"));
        inventory.setItem(SLOT_GUIDE, button(Material.BOOK, "menu-guide-button",
                guideLore(player)));
        inventory.setItem(SLOT_CLOSE, button(Material.BARRIER, "menu-close"));
        switch (holder.tab) {
            case SKILLS -> renderSkills(player, holder);
            case TOP -> renderTop(player, holder);
            case REBIRTH -> renderRebirth(player, holder);
            case POTIONS -> renderPotions(player, holder);
            case ATTRIBUTES -> renderAttributes(player, holder);
            case BUILDS -> renderBuilds(player, holder);
        }
        fillEmpty(inventory);
    }

    /** Рамка из стекла: пустые слоты больше не выглядят сломанной вёрсткой. */
    private void fillEmpty(Inventory inventory) {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.empty());
        pane.setItemMeta(meta);
        for (int slot = 9; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, pane);
            }
        }
    }

    private void renderSkills(Player player, Holder holder) {
        Inventory inventory = holder.inventory;
        if (holder.skill == null) {
            List<SkillType> skills = SkillRegistry.all();
            for (int index = 0; index < Math.min(SKILL_SLOTS.length, skills.size()); index++) {
                inventory.setItem(SKILL_SLOTS[index], icon(player, skills.get(index)));
            }
            inventory.setItem(SLOT_SUMMARY, summary(player));
            inventory.setItem(SLOT_CONTEXT, stepsBook());
            return;
        }
        int level = service.level(player, holder.skill);
        SkillCurve.LevelProgress progress = service.progress(player, holder.skill);
        ItemStack head = icon(player, holder.skill);
        ItemMeta headMeta = head.getItemMeta();
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("detail-progress",
                "bar", SkillCurve.bar(progress.percent(), 10),
                "percent", String.valueOf(progress.percent())));
        if (progress.maxed()) {
            lore.add(plugin.messages().get("detail-xp-max",
                    "xp", String.valueOf(service.xp(player, holder.skill))));
        } else {
            lore.add(plugin.messages().get("detail-xp",
                    "into", String.valueOf(progress.into()),
                    "need", String.valueOf(progress.need())));
        }
        lore.add(plugin.messages().get("menu-skill-steps",
                "steps", SkillRegistry.thresholdLine()));
        int rebirths = service.rebirths(player);
        if (rebirths > 0) {
            lore.add(plugin.messages().get("menu-rebirth-line",
                    "count", String.valueOf(rebirths)));
        }
        headMeta.lore(lore);
        head.setItemMeta(headMeta);
        inventory.setItem(DETAIL_HEAD, head);
        List<SkillPerk> perks = SkillRegistry.perksOf(holder.skill);
        for (int index = 0; index < Math.min(PERK_SLOTS.length, perks.size()); index++) {
            inventory.setItem(PERK_SLOTS[index], perkIcon(perks.get(index), level));
        }
        SkillAbility ability = SkillAbility.of(holder.skill);
        if (ability != null) {
            inventory.setItem(DETAIL_ABILITY, abilityIcon(player, ability));
        }
        inventory.setItem(DETAIL_BACK, button(Material.ARROW, "detail-back"));
        inventory.setItem(SLOT_SUMMARY, summary(player));
    }

    /* ------------------------------- престиж -------------------------------- */

    private static final int REBIRTH_TIER_SLOT = 31;
    private static final int REBIRTH_TOP_SLOT = 46;

    private void renderRebirth(Player player, Holder holder) {
        Inventory inventory = holder.inventory;
        List<SkillType> skills = SkillRegistry.all();
        for (int index = 0; index < Math.min(SKILL_SLOTS.length, skills.size()); index++) {
            inventory.setItem(SKILL_SLOTS[index], rebirthSkillIcon(player, skills.get(index)));
        }
        int count = service.rebirths(player);
        double bonus = RebirthMath.xpBonusPercent(count,
                plugin.config().rebirthXpPercentPerRebirth(),
                plugin.config().rebirthXpDecay(),
                plugin.config().rebirthXpBonusCapPercent());
        int next = 0;
        for (int mark : RebirthMath.milestoneThresholds()) {
            if (mark > count) {
                next = mark;
                break;
            }
        }
        if (next == 0 && count >= 50) {
            next = (count / 25 + 1) * 25;
        }
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("menu-rebirth-count",
                "count", String.valueOf(count),
                "title", Messages.trusted(RebirthMath.title(count))));
        lore.add(plugin.messages().get("menu-rebirth-bonus-xp",
                "percent", String.format(java.util.Locale.ROOT, "%.1f", bonus)));
        if (next > 0) {
            lore.add(plugin.messages().get("menu-rebirth-next-milestone",
                    "count", String.valueOf(next),
                    "need", String.valueOf(next - count)));
        } else {
            lore.add(plugin.messages().get("menu-rebirth-milestone-cap"));
        }
        lore.add(plugin.messages().get("menu-rebirth-hint",
                "level", String.valueOf(plugin.config().curve().maxLevel())));
        inventory.setItem(REBIRTH_TIER_SLOT,
                button(Material.NETHERITE_INGOT, "menu-rebirth-title", lore,
                        "title", Messages.trusted(RebirthMath.title(count)),
                        "count", String.valueOf(count)));
        inventory.setItem(REBIRTH_TOP_SLOT,
                button(Material.PAPER, "menu-rebirth-top-button", rebirthTopLore(player)));
        inventory.setItem(DETAIL_BACK, button(Material.ARROW, "menu-rebirth-back"));
        inventory.setItem(SLOT_SUMMARY, summary(player));
    }

    private ItemStack rebirthSkillIcon(Player player, SkillType skill) {
        int level = service.level(player, skill);
        int max = plugin.config().curve().maxLevel();
        ItemStack item = new ItemStack(materialOf(skill));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get("menu-rebirth-skill-name",
                "name", Messages.trusted(plugin.messages().raw("skill." + skill.id() + ".name"))));
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("menu-rebirth-skill-level",
                "level", String.valueOf(level),
                "max", String.valueOf(max)));
        lore.add(plugin.messages().get(level >= max
                ? "menu-rebirth-skill-ready" : "menu-rebirth-skill-hint"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private List<Component> rebirthTopLore(Player player) {
        List<Component> out = new ArrayList<>();
        int place = 1;
        for (SkillService.RebirthEntry entry : service.rebirthTop()) {
            if (place > 5) {
                break;
            }
            out.add(plugin.messages().get("menu-rebirth-top-line",
                    "place", String.valueOf(place++),
                    "name", entry.name(),
                    "count", String.valueOf(entry.count())));
        }
        if (out.isEmpty()) {
            out.add(plugin.messages().get("menu-rebirth-top-empty"));
        }
        return out;
    }

    private void onRebirthClick(Player player, Holder holder, int slot) {
        if (slot == DETAIL_BACK) {
            switchTab(player, holder, Tab.SKILLS);
            return;
        }
        if (slot == REBIRTH_TOP_SLOT) {
            player.closeInventory();
            plugin.messages().send(player, "rebirth-top-header");
            int place = 1;
            for (SkillService.RebirthEntry entry : service.rebirthTop()) {
                plugin.messages().send(player, "rebirth-top-line",
                        "place", String.valueOf(place++),
                        "name", entry.name(),
                        "count", String.valueOf(entry.count()));
            }
            if (place == 1) {
                plugin.messages().send(player, "rebirth-top-empty");
            }
        }
    }

    private void renderTop(Player player, Holder holder) {
        Inventory inventory = holder.inventory;
        List<SkillService.Entry> top = service.top();
        if (top.isEmpty()) {
            inventory.setItem(22, placeholder(Material.PAPER, "top-empty"));
        } else {
            for (int index = 0; index < Math.min(TOP_SLOTS.length, top.size()); index++) {
                SkillService.Entry entry = top.get(index);
                inventory.setItem(TOP_SLOTS[index], button(Material.PAPER, "top-line",
                        List.of(plugin.messages().get("menu-top-hint")),
                        "place", index + 1, "name", entry.name(),
                        "level", entry.level(), "xp", entry.xp()));
            }
        }
        inventory.setItem(SLOT_SUMMARY, summary(player));
        inventory.setItem(SLOT_CONTEXT, stepsBook());
    }

    private List<Component> guideLore(Player player) {
        return new ArrayList<>(plugin.messages().getList("menu-guide",
                "steps", SkillRegistry.thresholdLine()));
    }

    private void sendGuide(Player player) {
        plugin.messages().sendList(player, "menu-guide",
                "steps", SkillRegistry.thresholdLine());
    }

    /* ---------------------------------- иконки ---------------------------------- */

    /** Одна иконка навыка: уровень, опыт или «МАКС», ближайшая ступень. */
    private ItemStack icon(Player player, SkillType skill) {
        int level = service.level(player, skill);
        SkillCurve.LevelProgress progress = service.progress(player, skill);
        ItemStack item = new ItemStack(materialOf(skill));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get("menu-skill-name",
                "name", Messages.trusted(plugin.messages().raw("skill." + skill.id() + ".name")),
                "level", String.valueOf(level)));
        List<Component> lore = new ArrayList<>();
        if (progress.maxed()) {
            lore.add(plugin.messages().get("menu-skill-progress-max",
                    "bar", SkillCurve.bar(progress.percent(), 10),
                    "xp", String.valueOf(service.xp(player, skill))));
        } else {
            lore.add(plugin.messages().get("menu-skill-progress",
                    "bar", SkillCurve.bar(progress.percent(), 10),
                    "current", String.valueOf(progress.into()),
                    "need", String.valueOf(progress.need()),
                    "percent", String.valueOf(progress.percent())));
        }
        SkillPerk next = SkillPerks.nextPerk(skill, level);
        if (next != null) {
            lore.add(plugin.messages().get("menu-skill-next",
                    "level", String.valueOf(next.level()),
                    "perk", Messages.trusted(plugin.messages().raw("perk." + next.id() + ".name"))));
        } else {
            lore.add(plugin.messages().get("menu-skill-max"));
        }
        lore.add(plugin.messages().get("menu-skill-click"));
        int rebirths = service.rebirths(player);
        if (rebirths > 0) {
            lore.add(plugin.messages().get("menu-rebirth-line",
                    "count", String.valueOf(rebirths)));
        }
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
                plugin.messages().get("menu-summary-rebirth",
                        "count", String.valueOf(service.rebirths(player))),
                plugin.messages().get("ability-energy", "energy", plugin.abilities().energy(player.getUniqueId()), "max", SkillAbility.MAX_ENERGY)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack stepsBook() {
        return button(Material.KNOWLEDGE_BOOK, "menu-steps-title",
                List.of(plugin.messages().get("menu-skill-steps",
                        "steps", SkillRegistry.thresholdLine())));
    }

    private ItemStack abilityIcon(Player player, SkillAbility ability) {
        ItemStack item = new ItemStack(Material.BLAZE_POWDER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get(ability.nameKey()));
        int prestige = Math.max(SkillAbility.UNLOCK_REBIRTH, plugin.skills().rebirths(player));
        var lore = new java.util.ArrayList<net.kyori.adventure.text.Component>();
        lore.add(plugin.messages().get(ability.descKey()));
        lore.add(plugin.messages().get("ability-card", "level", prestige, "cost", ability.cost(prestige),
                "seconds", ability.seconds(prestige), "cooldown", ability.cooldown(prestige)));
        if (ability.master(prestige)) lore.add(plugin.messages().get("ability-card-master"));
        lore.add(plugin.messages().get("ability-energy", "energy", plugin.abilities().energy(player.getUniqueId()), "max", SkillAbility.MAX_ENERGY));
        lore.add(plugin.messages().get("ability-card-ready", "time", plugin.abilities().cooldown(player.getUniqueId(), ability)));
        lore.add(plugin.messages().get("ability-card-click"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
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
                plugin.messages().get(active ? "perk-active" : "perk-locked",
                        "level", String.valueOf(perk.level()))));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack tabItem(Holder holder, Tab tab, Material material, String key) {
        boolean selected = holder.tab == tab;
        ItemStack item = new ItemStack(selected ? Material.EMERALD_BLOCK : material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get(key));
        meta.lore(List.of(plugin.messages().get(selected ? "menu-tab-selected" : "menu-tab-hint")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack placeholder(Material material, String key) {
        return button(material, key);
    }

    private ItemStack button(Material material, String key) {
        return button(material, key, List.of());
    }

    private ItemStack button(Material material, String key, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get(key));
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack button(Material material, String key, List<Component> lore, Object... placeholders) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get(key, placeholders));
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
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

    /* ---------------------------------- клики ---------------------------------- */

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
        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot == TAB_SKILLS) {
            switchTab(player, holder, Tab.SKILLS);
            return;
        }
        if (slot == TAB_ATTRIBUTES) {
            switchTab(player, holder, Tab.ATTRIBUTES);
            return;
        }
        if (slot == TAB_TOP) {
            switchTab(player, holder, Tab.TOP);
            return;
        }
        if (slot == TAB_REBIRTH) {
            switchTab(player, holder, Tab.REBIRTH);
            return;
        }
        if (slot == TAB_POTIONS) {
            switchTab(player, holder, Tab.POTIONS);
            return;
        }
        if (slot == TAB_BUILDS) {
            switchTab(player, holder, Tab.BUILDS);
            return;
        }
        if (slot == SLOT_GUIDE) {
            sendGuide(player);
            return;
        }
        switch (holder.tab) {
            case SKILLS -> onSkillsClick(player, holder, slot);
            case REBIRTH -> onRebirthClick(player, holder, slot);
            case POTIONS -> onPotionsClick(player, holder, slot, event.isShiftClick());
            case ATTRIBUTES -> onAttributesClick(player, holder, slot, event.isShiftClick());
            case BUILDS -> onBuildsClick(player, holder, slot, event.isShiftClick());
            case TOP -> { /* рейтинг только для чтения */ }
        }
    }

    private void switchTab(Player player, Holder holder, Tab tab) {
        holder.tab = tab;
        holder.skill = null;
        holder.page = 0;
        holder.resetArmed = false;
        render(player, holder);
    }

    private void onSkillsClick(Player player, Holder holder, int slot) {
        if (holder.skill != null) {
            if (slot == DETAIL_BACK) {
                holder.skill = null;
                render(player, holder);
                return;
            }
            if (slot == DETAIL_ABILITY && SkillAbility.of(holder.skill) != null) {
                player.closeInventory();
                SkillAbility ability = SkillAbility.of(holder.skill);
                Bukkit.getScheduler().runTask(plugin, () -> plugin.abilities().activate(player, ability));
            }
            return;
        }
        List<SkillType> skills = SkillRegistry.all();
        for (int index = 0; index < Math.min(SKILL_SLOTS.length, skills.size()); index++) {
            if (slot == SKILL_SLOTS[index]) {
                holder.skill = skills.get(index);
                render(player, holder);
                return;
            }
        }
    }

    /** Витрина зелий: клик по зелью — покупка одного предмета за алмазы. */
    private void renderPotions(Player player, Holder holder) {
        Inventory inventory = holder.inventory;
        SkillXpShop shop = plugin.xpShop();
        List<SkillType> skills = SkillRegistry.all();
        for (int index = 0; index < Math.min(SKILL_SLOTS.length, skills.size()); index++) {
            inventory.setItem(SKILL_SLOTS[index], potionIcon(player, skills.get(index), shop));
        }
        inventory.setItem(SLOT_SUMMARY, summary(player));
        inventory.setItem(SLOT_CONTEXT, button(Material.EMERALD, "potion-shop-info",
                List.of(plugin.messages().get(shop != null && shop.shopEnabled()
                        ? "potion-shop-price" : "potion-shop-closed",
                        "cost", String.valueOf(shop == null ? 0 : shop.price())))));
    }

    private ItemStack potionIcon(Player player, SkillType skill, SkillXpShop shop) {
        ItemStack potion = plugin.xpItems().create(skill);
        ItemMeta meta = potion.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        if (shop != null && shop.shopEnabled()) {
            lore.add(plugin.messages().get("potion-buy", "cost", String.valueOf(shop.price())));
            lore.add(plugin.messages().get("potion-buy-hint", "stack", String.valueOf(shop.stackSize())));
        } else {
            lore.add(plugin.messages().get("potion-shop-closed", "cost", "0"));
        }
        meta.lore(lore);
        potion.setItemMeta(meta);
        return potion;
    }

    private void onPotionsClick(Player player, Holder holder, int slot, boolean shift) {
        SkillXpShop shop = plugin.xpShop();
        if (shop == null || !shop.shopEnabled()) {
            plugin.messages().send(player, "potion-shop-closed", "cost", "0");
            return;
        }
        List<SkillType> skills = SkillRegistry.all();
        for (int index = 0; index < Math.min(SKILL_SLOTS.length, skills.size()); index++) {
            if (slot != SKILL_SLOTS[index]) continue;
            SkillType skill = skills.get(index);
            // Shift+клик — стак: одним durable-намерением и одной оплатой.
            int count = shift ? shop.stackSize() : 1;
            if (shop.buy(player, skill, count)) {
                plugin.messages().send(player, "potion-bought",
                        "skill", Messages.trusted(plugin.messages().raw("skill." + skill.id() + ".name")),
                        "count", String.valueOf(count),
                        "cost", String.valueOf((long) shop.price() * count));
            }
            // Отказ/возврат уже объявлены движком оплаты (finance.service-*).
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    /* --------------------------- атрибуты и ветки --------------------------- */

    /** Панель атрибутов: пять иконок, сводка общего пула и бесплатный сброс. */
    private void renderAttributes(Player player, Holder holder) {
        Inventory inventory = holder.inventory;
        PlayerAttribute[] attributes = PlayerAttribute.values();
        for (int index = 0; index < Math.min(ATTR_SLOTS.length, attributes.length); index++) {
            inventory.setItem(ATTR_SLOTS[index], attributeIcon(player, attributes[index]));
        }
        inventory.setItem(SLOT_SUMMARY, pointsSummary(player, "attrs-gui-summary-name",
                List.of(plugin.messages().get("attrs-gui-sum",
                        "sum", String.valueOf(service.attributeSum(player)),
                        "rank", String.valueOf(AttributeMath.attrRank(service.attributeSum(player)))),
                        plugin.messages().get("attrs-gui-bonus",
                                "points", String.valueOf(service.bonusPoints(player))))));
        inventory.setItem(SLOT_CONTEXT, stepsBook());
        inventory.setItem(SLOT_RESET, resetButton(holder,
                "attrs-gui-reset", "attrs-gui-reset-confirm",
                plugin.messages().get("attrs-gui-reset-hint")));
        inventory.setItem(DETAIL_BACK, button(Material.ARROW, "detail-back"));
    }

    /** Панель веток билда: шесть иконок, сводка пула и платный сброс. */
    private void renderBuilds(Player player, Holder holder) {
        Inventory inventory = holder.inventory;
        BuildBranch[] branches = BuildBranch.values();
        for (int index = 0; index < Math.min(BRANCH_SLOTS.length, branches.length); index++) {
            inventory.setItem(BRANCH_SLOTS[index], branchIcon(player, branches[index]));
        }
        inventory.setItem(SLOT_SUMMARY, pointsSummary(player, "build-gui-summary-name",
                List.of(plugin.messages().get("build-gui-pve",
                        "pve", String.valueOf(service.pveContribution(player))))));
        inventory.setItem(SLOT_CONTEXT, stepsBook());
        inventory.setItem(SLOT_RESET, resetButton(holder,
                "build-gui-reset", "build-gui-reset-confirm",
                plugin.messages().get("build-gui-reset-hint",
                        "price", String.valueOf(BranchMath.RESPEC_PRICE))));
        inventory.setItem(DETAIL_BACK, button(Material.ARROW, "detail-back"));
    }

    /** Сводка общего пула очков: свободно/всего плюс строки конкретной панели. */
    private ItemStack pointsSummary(Player player, String nameKey, List<Component> extra) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get(nameKey));
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("points-gui-free",
                "points", String.valueOf(service.freeAttributePoints(player)),
                "total", String.valueOf(service.attributePoints(player) + service.bonusPoints(player))));
        lore.addAll(extra);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack attributeIcon(Player player, PlayerAttribute attribute) {
        int value = service.attribute(player, attribute);
        ItemStack item = new ItemStack(attributeMaterial(attribute));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get("attrs-gui-item",
                "name", attribute.displayName(), "value", String.valueOf(value)));
        meta.lore(List.of(
                plugin.messages().get("attrs-gui-desc-" + attribute.id()),
                plugin.messages().get("points-gui-free",
                        "points", String.valueOf(service.freeAttributePoints(player)),
                        "total", String.valueOf(service.attributePoints(player) + service.bonusPoints(player))),
                plugin.messages().get("points-gui-hint-add"),
                plugin.messages().get("points-gui-hint-add5")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack branchIcon(Player player, BuildBranch branch) {
        int level = service.branchLevel(player, branch);
        ItemStack item = new ItemStack(branchMaterial(branch));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get("build-gui-item",
                "name", branch.displayName(),
                "level", String.valueOf(level),
                "max", String.valueOf(BuildBranch.MAX_LEVEL)));
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("build-gui-desc-" + branch.id()));
        for (BranchAbility ability : BranchAbility.values()) {
            if (ability.branch() != branch) {
                continue;
            }
            lore.add(plugin.messages().get("build-gui-ability",
                    "level", String.valueOf(ability.unlockLevel()),
                    "name", Messages.trusted(plugin.messages().raw(ability.nameKey()))));
        }
        lore.add(plugin.messages().get("points-gui-free",
                "points", String.valueOf(service.freeAttributePoints(player)),
                "total", String.valueOf(service.attributePoints(player) + service.bonusPoints(player))));
        lore.add(plugin.messages().get("points-gui-hint-add"));
        lore.add(plugin.messages().get("points-gui-hint-add5"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Кнопка сброса распределения: первый клик взводит подтверждение, второй —
     * выполняет. Взводится одна кнопка на открытую вкладку и гасится сменой
     * вкладки, поэтому случайный двойной клик не сбрасывает очки.
     */
    private ItemStack resetButton(Holder holder, String nameKey, String confirmKey, Component hint) {
        ItemStack item = new ItemStack(holder.resetArmed ? Material.REDSTONE_BLOCK : Material.TNT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get(holder.resetArmed ? confirmKey : nameKey));
        meta.lore(List.of(hint));
        item.setItemMeta(meta);
        return item;
    }

    private void onAttributesClick(Player player, Holder holder, int slot, boolean shift) {
        if (slot == DETAIL_BACK) {
            switchTab(player, holder, Tab.SKILLS);
            return;
        }
        if (slot == SLOT_RESET) {
            if (!holder.resetArmed) {
                holder.resetArmed = true;
                render(player, holder);
                return;
            }
            holder.resetArmed = false;
            service.resetAttributes(player);
            plugin.effects().apply(player);
            plugin.messages().send(player, "attrs-reset-done",
                    "points", service.freeAttributePoints(player));
            render(player, holder);
            return;
        }
        holder.resetArmed = false;
        PlayerAttribute[] attributes = PlayerAttribute.values();
        for (int index = 0; index < Math.min(ATTR_SLOTS.length, attributes.length); index++) {
            if (slot != ATTR_SLOTS[index]) {
                continue;
            }
            PlayerAttribute attribute = attributes[index];
            int free = service.freeAttributePoints(player);
            int points = Math.min(shift ? 5 : 1, free);
            if (points <= 0 || !service.spendAttribute(player, attribute, points)) {
                plugin.messages().send(player, "attrs-short", "points", free);
                return;
            }
            plugin.effects().apply(player);
            plugin.messages().send(player, "attrs-added",
                    "points", points,
                    "attribute", attribute.displayName(),
                    "value", service.attribute(player, attribute));
            render(player, holder);
            return;
        }
    }

    private void onBuildsClick(Player player, Holder holder, int slot, boolean shift) {
        if (slot == DETAIL_BACK) {
            switchTab(player, holder, Tab.SKILLS);
            return;
        }
        if (slot == SLOT_RESET) {
            if (!holder.resetArmed) {
                holder.resetArmed = true;
                render(player, holder);
                return;
            }
            holder.resetArmed = false;
            boolean paid = plugin.xpShop() != null && plugin.xpShop().payments().perform(player, player,
                    (int) BranchMath.RESPEC_PRICE, "skill-branch-respec", "reset",
                    () -> {
                        service.resetBranches(player);
                        plugin.effects().apply(player);
                        return true;
                    });
            if (paid) {
                plugin.messages().send(player, "build-reset-done",
                        "points", service.freeAttributePoints(player));
            } else {
                plugin.messages().send(player, "build-reset-fail",
                        "price", BranchMath.RESPEC_PRICE);
            }
            render(player, holder);
            return;
        }
        holder.resetArmed = false;
        BuildBranch[] branches = BuildBranch.values();
        for (int index = 0; index < Math.min(BRANCH_SLOTS.length, branches.length); index++) {
            if (slot != BRANCH_SLOTS[index]) {
                continue;
            }
            BuildBranch branch = branches[index];
            int level = service.branchLevel(player, branch);
            if (level >= BuildBranch.MAX_LEVEL) {
                plugin.messages().send(player, "build-max", "name", branch.displayName());
                return;
            }
            int free = service.freeAttributePoints(player);
            int points = Math.min(shift ? 5 : 1, Math.min(free, BuildBranch.MAX_LEVEL - level));
            if (points <= 0 || !service.spendBranch(player, branch, points)) {
                plugin.messages().send(player, "build-short", "points", free);
                return;
            }
            plugin.effects().apply(player);
            plugin.messages().send(player, "build-added",
                    "points", points,
                    "name", branch.displayName(),
                    "level", service.branchLevel(player, branch));
            render(player, holder);
            return;
        }
    }

    static Material attributeMaterial(PlayerAttribute attribute) {
        return switch (attribute) {
            case STRENGTH -> Material.IRON_SWORD;
            case AGILITY -> Material.FEATHER;
            case STAMINA -> Material.SHIELD;
            case WISDOM -> Material.ENCHANTING_TABLE;
            case CUNNING -> Material.SPIDER_EYE;
        };
    }

    static Material branchMaterial(BuildBranch branch) {
        return switch (branch) {
            case WARRIOR -> Material.IRON_SWORD;
            case ARCHER -> Material.BOW;
            case MAGE -> Material.BLAZE_ROD;
            case CLERIC -> Material.SHIELD;
            case PRIEST -> Material.TOTEM_OF_UNDYING;
            case ROGUE -> Material.ENDER_PEARL;
        };
    }

    /** Состояние живёт в Holder инвентаря: после закрытия карта не нужна. */
    private static final class Holder implements InventoryHolder {

        private final UUID owner;
        private Inventory inventory;
        private Tab tab = Tab.SKILLS;
        private SkillType skill;

        private int page;

        /** Взведённое подтверждение сброса распределения очков. */
        private boolean resetArmed;

        private Holder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
