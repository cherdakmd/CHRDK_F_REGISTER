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
import ru.cherdakmd.chrdkskills.tree.PerkTreeMenuApi;
import ru.cherdakmd.chrdkskills.tree.TreeDomain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Единое меню {@code /skills}: один инвентарь на 54 слота, три вкладки.
 *
 * <p>Вкладки «Навыки», «Дерево перков» и «Топ» переключаются кликом по верхнему
 * ряду: {@link #render} просто очищает слоты и рисует их заново, не создавая
 * второго инвентаря и не вызывая {@code openInventory} повторно. Карточка
 * навыка — тоже состояние вкладки «Навыки», а не отдельное окно на 27 слотов.</p>
 *
 * <p>Дерево перков приходит из {@code CHRDK_Classes} через
 * {@link PerkTreeMenuApi}, зарегистрированный в {@code ServicesManager}. Сам
 * модуль {@code skills} о классах ничего не знает: если аддон не установлен,
 * вкладка показывает понятную заглушку. {@code /perktree} открывает это же окно
 * сразу на вкладке дерева через {@link SkillsMenuApi}.</p>
 *
 * <p>Иконки не показывают чужие данные и не позволяют таскать предметы: все
 * клики по верхнему инвентарю отменяются, драг запрещён.</p>
 */
public final class SkillsGui implements Listener, SkillsMenuApi {

    /** Верхний ряд — вкладки и общие кнопки. */
    private static final int TAB_SKILLS = 0;
    private static final int TAB_TREE = 1;
    private static final int TAB_TOP = 2;
    private static final int SLOT_GUIDE = 3;
    private static final int SLOT_CONTEXT = 4;
    private static final int SLOT_CLOSE = 8;

    /** Содержимое вкладки «Навыки». */
    private static final int[] SKILL_SLOTS = {10, 11, 12, 13, 14, 19, 20, 21, 28, 29};
    /** 12 ступеней перков вместо прежних шести. */
    private static final int[] PERK_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23};
    private static final int DETAIL_HEAD = SLOT_CONTEXT;
    private static final int DETAIL_ABILITY = 28;
    private static final int DETAIL_BACK = 45;
    private static final int SLOT_SUMMARY = 49;

    /** Содержимое вкладки «Топ». */
    private static final int[] TOP_SLOTS = {10, 11, 12, 13, 14, 19, 20, 21, 28, 29};

    private final SkillsPlugin plugin;
    private final SkillService service;

    public SkillsGui(SkillsPlugin plugin, SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /* ---------------------------------- открытие --------------------------------- */

    /** {@code /skills}: витрина навыков. */
    public void open(Player player) {
        open(player, Tab.SKILLS, null, 0);
    }

    /**
     * Открыть единое меню. Если оно уже открыто у игрока — только сменить
     * вкладку/домен/страницу и перерисовать слоты, без второго окна.
     */
    @Override
    public void open(Player player, Tab tab, String domain, int page) {
        if (player == null) {
            return;
        }
        // Вкладка дерева живёт в CHRDK_Classes и не зависит от выключателя
        // навыков: /perktree должен открываться даже при enabled: false.
        if (!plugin.config().enabled() && tab != Tab.TREE) {
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
        holder.domain = resolveDomain(player, domain, holder.tab);
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

    /** Ленивый lookup: аддон классов может включиться позже навыков. */
    private PerkTreeMenuApi perkTrees() {
        try {
            return Bukkit.getServicesManager().load(PerkTreeMenuApi.class);
        } catch (LinkageError | RuntimeException error) {
            return null;
        }
    }

    private TreeDomain resolveDomain(Player player, String raw, Tab tab) {
        if (tab != Tab.TREE) {
            return null;
        }
        PerkTreeMenuApi api = perkTrees();
        if (api == null) {
            return null;
        }
        List<TreeDomain> domains = api.domains(player);
        if (raw != null) {
            for (TreeDomain candidate : domains) {
                if (candidate.id().equalsIgnoreCase(raw)) {
                    return candidate;
                }
            }
        }
        TreeDomain primary = api.primary(player);
        if (primary != null && domains.contains(primary)) {
            return primary;
        }
        return domains.isEmpty() ? null : domains.get(0);
    }

    /* --------------------------------- отрисовка -------------------------------- */

    /** Перерисовать ВСЕ слоты одного и того же инвентаря. */
    private void render(Player player, Holder holder) {
        Inventory inventory = holder.inventory;
        inventory.clear();
        inventory.setItem(TAB_SKILLS, tabItem(holder, Tab.SKILLS, Material.IRON_PICKAXE,
                "menu-tab-skills"));
        inventory.setItem(TAB_TREE, tabItem(holder, Tab.TREE, Material.NETHER_STAR,
                "menu-tab-tree"));
        inventory.setItem(TAB_TOP, tabItem(holder, Tab.TOP, Material.GOLD_INGOT,
                "menu-top"));
        inventory.setItem(SLOT_GUIDE, button(Material.BOOK, "menu-guide-button",
                guideLore(player)));
        inventory.setItem(SLOT_CLOSE, button(Material.BARRIER, "menu-close"));
        switch (holder.tab) {
            case SKILLS -> renderSkills(player, holder);
            case TREE -> renderTree(player, holder);
            case TOP -> renderTop(player, holder);
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

    private void renderTree(Player player, Holder holder) {
        Inventory inventory = holder.inventory;
        PerkTreeMenuApi api = perkTrees();
        if (api == null) {
            inventory.setItem(22, placeholder(Material.BARRIER, "menu-tree-unavailable"));
            return;
        }
        List<TreeDomain> domains = api.domains(player);
        if (domains.isEmpty() || holder.domain == null) {
            inventory.setItem(22, placeholder(Material.PAPER, "menu-tree-no-tree"));
            return;
        }
        if (!domains.contains(holder.domain)) {
            holder.domain = domains.get(0);
        }
        int pages = Math.max(1, api.pages(holder.domain));
        holder.page = Math.max(0, Math.min(holder.page, pages - 1));
        Map<Integer, ItemStack> items = api.items(player, holder.domain, holder.page);
        if (items.isEmpty()) {
            inventory.setItem(22, placeholder(Material.PAPER, "menu-tree-no-tree"));
        } else {
            for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
                int slot = entry.getKey();
                if (slot >= 9 && slot < inventory.getSize()) {
                    inventory.setItem(slot, entry.getValue());
                }
            }
        }
        inventory.setItem(SLOT_CONTEXT, button(Material.COMPASS, "menu-tab-domain",
                List.of(plugin.messages().get("menu-tab-domain-hint"),
                        api.title(player, holder.domain, holder.page)),
                "domain", api.domainName(holder.domain),
                "page", holder.page + 1, "pages", pages));
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
        List<Component> lines = new ArrayList<>(plugin.messages().getList("menu-guide",
                "steps", SkillRegistry.thresholdLine()));
        PerkTreeMenuApi api = perkTrees();
        if (api != null) {
            lines.addAll(api.guide(player));
        }
        return lines;
    }

    private void sendGuide(Player player) {
        plugin.messages().sendList(player, "menu-guide",
                "steps", SkillRegistry.thresholdLine());
        PerkTreeMenuApi api = perkTrees();
        if (api != null) {
            for (Component line : api.guide(player)) {
                player.sendMessage(line);
            }
        }
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

    private ItemStack stepsBook() {
        return button(Material.KNOWLEDGE_BOOK, "menu-steps-title",
                List.of(plugin.messages().get("menu-skill-steps",
                        "steps", SkillRegistry.thresholdLine())));
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
        if (slot == TAB_TREE) {
            switchTab(player, holder, Tab.TREE);
            return;
        }
        if (slot == TAB_TOP) {
            switchTab(player, holder, Tab.TOP);
            return;
        }
        if (slot == SLOT_GUIDE) {
            sendGuide(player);
            return;
        }
        switch (holder.tab) {
            case TREE -> onTreeClick(player, holder, slot);
            case SKILLS -> onSkillsClick(player, holder, slot);
            case TOP -> { /* рейтинг только для чтения */ }
        }
    }

    private void switchTab(Player player, Holder holder, Tab tab) {
        holder.tab = tab;
        holder.skill = null;
        holder.page = 0;
        if (tab == Tab.TREE) {
            holder.domain = resolveDomain(player, null, Tab.TREE);
        } else {
            holder.domain = null;
        }
        render(player, holder);
    }

    private void onTreeClick(Player player, Holder holder, int slot) {
        PerkTreeMenuApi api = perkTrees();
        if (api == null) {
            return;
        }
        if (slot == SLOT_CONTEXT) {
            List<TreeDomain> domains = api.domains(player);
            if (!domains.isEmpty()) {
                int index = holder.domain == null ? -1 : domains.indexOf(holder.domain);
                holder.domain = domains.get((index + 1) % domains.size());
                holder.page = 0;
                render(player, holder);
            }
            return;
        }
        if (slot == api.closeSlot()) {
            player.closeInventory();
            return;
        }
        if (holder.domain == null) {
            return;
        }
        PerkTreeMenuApi.Result result = api.click(player, holder.domain, holder.page, slot);
        if (result == null) {
            return;
        }
        switch (result.action()) {
            case CLOSE -> player.closeInventory();
            case REPAINT -> {
                holder.domain = result.domain();
                holder.page = result.page();
                render(player, holder);
            }
            case NONE -> { /* сообщение уже отправила реализация */ }
        }
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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    /** Состояние живёт в Holder инвентаря: после закрытия карта не нужна. */
    private static final class Holder implements InventoryHolder {

        private final UUID owner;
        private Inventory inventory;
        private Tab tab = Tab.SKILLS;
        private SkillType skill;
        private TreeDomain domain;
        private int page;

        private Holder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
