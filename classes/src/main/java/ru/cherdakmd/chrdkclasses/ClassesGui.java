package ru.cherdakmd.chrdkclasses;

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
import java.util.Locale;
import java.util.UUID;

/**
 * Меню классов: выбор, карточка стиля и дерево талантов (1.4.164).
 *
 * <p>Три экрана: главный (кто я и кем могу стать), карточка класса (что он даёт
 * и кнопка подтверждения) и дерево (15 узлов тремя ветками). Меню ничего не
 * решает само: клик зовёт {@link TalentService}, поэтому выбрать класс или
 * выучить талант в обход очков и требований нельзя.</p>
 */
final class ClassesGui implements Listener {

    private static final int SIZE = 54;

    /** Экран, который открыт: от него зависит разбор клика. */
    enum Screen {
        MAIN,
        INFO,
        TALENTS
    }

    static final class Holder implements InventoryHolder {
        private final UUID owner;
        private final Screen screen;
        private final String classId;
        private Inventory inventory;

        Holder(UUID owner, Screen screen, String classId) {
            this.owner = owner;
            this.screen = screen;
            this.classId = classId == null ? "" : classId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final int INFO_SLOT = 4;
    private static final int CLOSE_SLOT = 49;
    private static final int BACK_SLOT = 45;
    /**
     * В дереве навигация сверху: пять ярусов занимают все нижние ряды, и
     * кнопки в подвале отняли бы место у мастерского яруса.
     */
    private static final int TREE_CLOSE_SLOT = 8;
    private static final int TREE_BACK_SLOT = 0;
    /** Четыре класса в ряд: слоты 19, 21, 23, 25. */
    private static final int[] CLASS_SLOTS = {19, 21, 23, 25};
    private static final int TALENTS_SLOT = 39;
    /** Умение класса — между деревом и пересборкой. */
    private static final int ABILITY_SLOT = 40;
    private static final int RESPEC_SLOT = 41;
    private static final int TREE_RESPEC_SLOT = 45;
    private static final int SUMMARY_SLOT = 46;
    /** Карточка класса в дереве — правый нижний угол. */
    private static final int TREE_SUMMARY_SLOT = 53;
    /** Подтверждение выбора в карточке класса. */
    private static final int CONFIRM_SLOT = 31;
    /** Превью веток в карточке класса. */
    private static final int[] PREVIEW_SLOTS = {19, 21, 23};
    /** Заголовки веток в дереве — над колонками 1, 4 и 7. */
    private static final int[] BRANCH_SLOTS = {1, 4, 7};
    /** Ярусы дерева: пять рядов по три колонки (пятый — мастерский). */
    private static final int[][] TALENT_SLOTS = {
            {10, 13, 16},
            {19, 22, 25},
            {28, 31, 34},
            {37, 40, 43},
            {46, 49, 52}};

    /** Ярус, который открывается только навыком 75; подсвечивается в тексте. */
    static final int MASTERY_TIER = 5;

    private final ClassesPlugin plugin;

    ClassesGui(ClassesPlugin plugin) {
        this.plugin = plugin;
    }

    /* --------------------------------- экраны --------------------------------- */

    /** Главное меню: класс игрока и четыре варианта. */
    void open(Player player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUniqueId();
        TalentService service = plugin.service();
        PlayerClass current = service.classOf(id);
        Holder holder = new Holder(id, Screen.MAIN, current == null ? "" : current.id());
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("menu-title"));
        holder.inventory = inventory;

        inventory.setItem(INFO_SLOT, current == null
                ? simple(Material.WHITE_BANNER, "menu-info-none")
                : summaryItem(player, current));

        List<PlayerClass> classes = ClassCatalog.classes();
        for (int index = 0; index < classes.size() && index < CLASS_SLOTS.length; index++) {
            PlayerClass candidate = classes.get(index);
            inventory.setItem(CLASS_SLOTS[index], classItem(candidate, candidate == current));
        }
        List<Component> talentsLore = new ArrayList<>();
        if (current == null) {
            talentsLore.add(plugin.messages().get("menu-talents-none"));
        } else {
            talentsLore.add(plugin.messages().get("menu-talents-lore",
                    "points", String.valueOf(service.availablePoints(id))));
            talentsLore.add(plugin.messages().get("menu-talents-nodes",
                    "count", String.valueOf(plugin.store().talentCount(id)),
                    "max", String.valueOf(ClassCatalog.TALENTS_PER_CLASS)));
        }
        inventory.setItem(TALENTS_SLOT, button(Material.BOOK, "menu-talents-name", talentsLore));
        inventory.setItem(ABILITY_SLOT, abilityItem(player, current));
        inventory.setItem(RESPEC_SLOT, respecItem(id));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "menu-close", List.of()));
        player.openInventory(inventory);
    }

    /** Карточка класса перед выбором. */
    void openInfo(Player player, PlayerClass candidate) {
        if (player == null || candidate == null) {
            return;
        }
        Holder holder = new Holder(player.getUniqueId(), Screen.INFO, candidate.id());
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("info-title", "class", Messages.trusted(
                        plugin.messages().raw("class." + candidate.id() + ".name"))));
        holder.inventory = inventory;

        TalentService service = plugin.service();
        PlayerClass current = service.classOf(player.getUniqueId());

        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("class." + candidate.id() + ".desc"));
        lore.add(Component.empty());
        lore.add(plugin.messages().get("menu-class-passive"));
        for (var entry : ClassCatalog.passive(candidate.id()).entrySet()) {
            lore.add(effectLine(entry.getKey(), entry.getValue()));
        }
        inventory.setItem(INFO_SLOT, button(material(candidate.iconName()),
                "class." + candidate.id() + ".name", lore));

        List<String> branches = ClassCatalog.branches(candidate.id());
        for (int index = 0; index < branches.size() && index < PREVIEW_SLOTS.length; index++) {
            inventory.setItem(PREVIEW_SLOTS[index],
                    branchItem(candidate.id(), branches.get(index), true));
        }
        inventory.setItem(CONFIRM_SLOT, confirmItem(player, candidate, current));
        inventory.setItem(BACK_SLOT, button(Material.ARROW, "menu-back", List.of()));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "menu-close", List.of()));
        player.openInventory(inventory);
    }

    /** Дерево талантов выбранного класса. */
    void openTalents(Player player) {
        if (player == null) {
            return;
        }
        TalentService service = plugin.service();
        PlayerClass current = service.classOf(player.getUniqueId());
        if (current == null) {
            plugin.messages().send(player, "class-required");
            open(player);
            return;
        }
        Holder holder = new Holder(player.getUniqueId(), Screen.TALENTS, current.id());
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("talents-title", "class", Messages.trusted(
                        plugin.messages().raw("class." + current.id() + ".name"))));
        holder.inventory = inventory;

        List<String> branches = ClassCatalog.branches(current.id());
        for (int index = 0; index < branches.size() && index < BRANCH_SLOTS.length; index++) {
            inventory.setItem(BRANCH_SLOTS[index],
                    branchItem(current.id(), branches.get(index), false));
        }
        for (int branchIndex = 0; branchIndex < branches.size() && branchIndex < 3; branchIndex++) {
            for (Talent talent : ClassCatalog.branch(current.id(), branches.get(branchIndex))) {
                int tier = Math.clamp(talent.tier(), 1, TALENT_SLOTS.length);
                if (branchIndex < TALENT_SLOTS[tier - 1].length) {
                    inventory.setItem(TALENT_SLOTS[tier - 1][branchIndex],
                            talentItem(player, talent));
                }
            }
        }
        inventory.setItem(TREE_SUMMARY_SLOT, summaryItem(player, current));
        inventory.setItem(TREE_BACK_SLOT, button(Material.ARROW, "menu-back", List.of()));
        inventory.setItem(TREE_RESPEC_SLOT, respecItem(player.getUniqueId()));
        inventory.setItem(TREE_CLOSE_SLOT, button(Material.BARRIER, "menu-close", List.of()));
        player.openInventory(inventory);
    }

    /* --------------------------------- предметы -------------------------------- */

    private ItemStack summaryItem(Player player, PlayerClass playerClass) {
        UUID id = player.getUniqueId();
        TalentService service = plugin.service();
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("menu-summary-class",
                "class", Messages.trusted(plugin.messages().raw(
                        "class." + playerClass.id() + ".name"))));
        lore.add(plugin.messages().get("menu-summary-level",
                "level", String.valueOf(service.level(id)),
                "total", String.valueOf(service.totalSkill(id))));
        lore.add(plugin.messages().get("menu-summary-points",
                "points", String.valueOf(service.availablePoints(id)),
                "spent", String.valueOf(service.spentPoints(id))));
        lore.add(plugin.messages().get("menu-summary-talents",
                "count", String.valueOf(service.store().talentCount(id)),
                "max", String.valueOf(ClassCatalog.TALENTS_PER_CLASS)));
        return button(material(playerClass.iconName()), "menu-summary-name", lore);
    }

    private ItemStack classItem(PlayerClass candidate, boolean current) {
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("class." + candidate.id() + ".desc"));
        lore.add(Component.empty());
        lore.add(plugin.messages().get("menu-class-passive"));
        for (var entry : ClassCatalog.passive(candidate.id()).entrySet()) {
            lore.add(effectLine(entry.getKey(), entry.getValue()));
        }
        lore.add(Component.empty());
        lore.add(plugin.messages().get(current ? "menu-class-current" : "menu-class-click"));
        return button(material(candidate.iconName()), "class." + candidate.id() + ".name", lore);
    }

    private ItemStack branchItem(String classId, String branch, boolean preview) {
        List<Talent> talents = ClassCatalog.branch(classId, branch);
        List<Component> lore = new ArrayList<>();
        if (preview) {
            for (Talent talent : talents) {
                lore.add(plugin.messages().get("menu-branch-preview-line",
                        "cost", String.valueOf(talent.cost()),
                        "effect", Messages.trusted(plugin.messages().raw(
                                "effect." + talent.kind().id(),
                                "amount", ClassMath.amount(talent.kind(), talent.value())))));
            }
            if (!talents.isEmpty()) {
                lore.add(Component.empty());
                lore.add(plugin.messages().get("menu-branch-preview-hint"));
            }
        }
        Material icon = talents.isEmpty() ? Material.BOOK : materialFor(talents.get(0).kind());
        return button(icon, "branch." + branch, lore);
    }

    private ItemStack talentItem(Player player, Talent talent) {
        UUID id = player.getUniqueId();
        TalentService service = plugin.service();
        ClassResult state = service.checkLearn(id, talent.id());
        boolean known = state.key().equals("talent-known");

        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("talent." + talent.id() + ".desc"));
        lore.add(Component.empty());
        lore.add(effectLine(talent.kind(), talent.value()));
        if (talent.tier() >= MASTERY_TIER) {
            lore.add(plugin.messages().get("menu-talent-mastery"));
        }
        if (talent.needsSkill()) {
            lore.add(plugin.messages().get("menu-talent-skill",
                    "skill", Messages.trusted(plugin.messages().raw(
                            "skill." + talent.skill() + ".name")),
                    "level", String.valueOf(talent.skillLevel()),
                    "have", String.valueOf(service.skillLevel(id, talent.skill()))));
        }
        lore.add(Component.empty());
        lore.add(statusLine(state, talent, id));
        lore.add(plugin.messages().get("menu-talent-cost", "cost",
                String.valueOf(talent.cost())));

        Material icon = known ? Material.LIME_DYE
                : state.ok() ? materialFor(talent.kind()) : Material.GRAY_DYE;
        return button(icon, "talent." + talent.id() + ".name", lore);
    }

    private Component statusLine(ClassResult state, Talent talent, UUID playerId) {
        return switch (state.key()) {
            case "talent-known" -> plugin.messages().get("menu-talent-known");
            case "talent-locked" -> plugin.messages().get("menu-talent-locked", "talent",
                    Messages.trusted(plugin.messages().raw(
                            "talent." + talent.requires() + ".name")));
            case "talent-skill" -> plugin.messages().get("menu-talent-skill-locked",
                    "level", String.valueOf(talent.skillLevel()));
            case "talent-points" -> plugin.messages().get("menu-talent-no-points",
                    "points", String.valueOf(plugin.service().availablePoints(playerId)));
            case "class-required" -> plugin.messages().get("menu-talent-no-class");
            default -> plugin.messages().get("menu-talent-ready");
        };
    }

    private ItemStack confirmItem(Player player, PlayerClass candidate, PlayerClass current) {
        UUID id = player.getUniqueId();
        TalentService service = plugin.service();
        List<Component> lore = new ArrayList<>();
        if (current == candidate) {
            lore.add(plugin.messages().get("menu-confirm-same"));
            return button(Material.GRAY_DYE, "menu-confirm-name", lore);
        }
        long left = service.changeCooldownLeft(id, System.currentTimeMillis());
        if (left > 0) {
            lore.add(plugin.messages().get("menu-confirm-cooldown", "time", ClassMath.clock(left)));
            return button(Material.CLOCK, "menu-confirm-name", lore);
        }
        lore.add(current == null
                ? plugin.messages().get("menu-confirm-choose-lore")
                : plugin.messages().get("menu-confirm-change-lore",
                "class", Messages.trusted(plugin.messages().raw(
                        "class." + current.id() + ".name"))));
        return button(current == null ? Material.LIME_DYE : Material.ORANGE_DYE,
                "menu-confirm-name", lore);
    }

    /** Кнопка умения: состояние кулдауна и подсказка, что даёт класс. */
    private ItemStack abilityItem(Player player, PlayerClass current) {
        List<Component> lore = new ArrayList<>();
        if (current == null) {
            lore.add(plugin.messages().get("menu-ability-none"));
            return button(Material.GRAY_DYE, "menu-ability-name", lore);
        }
        ClassAbility ability = ClassAbility.of(current);
        if (!plugin.config().abilitiesEnabled() || ability == null) {
            lore.add(plugin.messages().get("ability-disabled"));
            return button(Material.GRAY_DYE, "menu-ability-name", lore);
        }
        lore.add(plugin.messages().get("ability." + current.id() + ".desc"));
        lore.add(Component.empty());
        long left = plugin.abilities().cooldownLeft(player.getUniqueId(), current);
        lore.add(left > 0
                ? plugin.messages().get("menu-ability-cooldown", "time", ClassMath.clockMillis(left))
                : plugin.messages().get("menu-ability-ready"));
        return button(material(ability.iconName()), "menu-ability-name", lore);
    }

    private ItemStack respecItem(UUID id) {
        TalentService service = plugin.service();
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("menu-respec-lore"));
        long left = service.respecCooldownLeft(id, System.currentTimeMillis());
        if (left > 0) {
            lore.add(plugin.messages().get("menu-respec-cooldown", "time", ClassMath.clock(left)));
        } else if (service.store().talentCount(id) == 0) {
            lore.add(plugin.messages().get("menu-respec-empty"));
        } else {
            lore.add(plugin.messages().get("menu-respec-ready",
                    "talents", String.valueOf(service.store().talentCount(id)),
                    "cost", String.valueOf(service.spentPoints(id))));
        }
        return button(Material.GRINDSTONE, "menu-respec-name", lore);
    }

    private Component effectLine(EffectKind kind, double value) {
        return plugin.messages().get("effect." + kind.id(),
                "amount", ClassMath.amount(kind, value));
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
        int slot = event.getRawSlot();
        // В дереве кнопки сверху: нижние ряды заняты ярусами.
        boolean tree = holder.screen == Screen.TALENTS;
        if (slot == (tree ? TREE_CLOSE_SLOT : CLOSE_SLOT)) {
            player.closeInventory();
            return;
        }
        if (slot == (tree ? TREE_BACK_SLOT : BACK_SLOT)) {
            open(player);
            return;
        }
        switch (holder.screen) {
            case MAIN -> clickMain(player, slot);
            case INFO -> clickInfo(player, holder, slot);
            case TALENTS -> clickTalents(player, holder.classId, slot);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private void clickMain(Player player, int slot) {
        if (slot == TALENTS_SLOT) {
            openTalents(player);
            return;
        }
        if (slot == ABILITY_SLOT) {
            if (plugin.service().classOf(player.getUniqueId()) != null) {
                // Меню закрывается, только если умение есть: иначе игрок
                // останется в меню и прочитает «сначала выберите класс».
                player.closeInventory();
            }
            ClassResult result = plugin.abilities().use(player);
            plugin.messages().send(player, result.key(),
                    ClassText.localise(plugin.messages(), result).toArray());
            return;
        }
        if (slot == RESPEC_SLOT) {
            respec(player);
            return;
        }
        List<PlayerClass> classes = ClassCatalog.classes();
        for (int index = 0; index < CLASS_SLOTS.length && index < classes.size(); index++) {
            if (CLASS_SLOTS[index] != slot) {
                continue;
            }
            PlayerClass candidate = classes.get(index);
            if (plugin.service().classOf(player.getUniqueId()) == candidate) {
                openTalents(player);
            } else {
                openInfo(player, candidate);
            }
            return;
        }
    }

    private void clickInfo(Player player, Holder holder, int slot) {
        if (slot != CONFIRM_SLOT) {
            return;
        }
        ClassResult result = plugin.service().choose(player.getUniqueId(), holder.classId,
                System.currentTimeMillis());
        plugin.messages().send(player, result.key(),
                ClassText.localise(plugin.messages(), result).toArray());
        if (result.ok()) {
            plugin.requestSave();
            plugin.effects().apply(player);
            openTalents(player);
        } else {
            open(player);
        }
    }

    private void clickTalents(Player player, String classId, int slot) {
        if (slot == TREE_RESPEC_SLOT) {
            respec(player);
            return;
        }
        Talent talent = talentAt(classId, slot);
        if (talent == null) {
            return;
        }
        ClassResult result = plugin.service().learn(player.getUniqueId(), talent.id());
        plugin.messages().send(player, result.key(),
                ClassText.localise(plugin.messages(), result).toArray());
        if (result.ok()) {
            plugin.requestSave();
            plugin.effects().apply(player);
            if (plugin.config().actionbar()) {
                player.sendActionBar(plugin.messages().get("talent-actionbar",
                        "talent", Messages.trusted(plugin.messages().raw(
                                "talent." + talent.id() + ".name"))));
            }
        }
        openTalents(player);
    }

    private void respec(Player player) {
        ClassResult result = plugin.service().respec(player.getUniqueId(),
                System.currentTimeMillis());
        plugin.messages().send(player, result.key(),
                ClassText.localise(plugin.messages(), result).toArray());
        if (result.ok()) {
            plugin.requestSave();
            plugin.effects().apply(player);
        }
        openTalents(player);
    }

    /** Талант, который стоит на слоте дерева, или {@code null}. */
    private static Talent talentAt(String classId, int slot) {
        List<String> branches = ClassCatalog.branches(classId);
        for (int tier = 0; tier < TALENT_SLOTS.length; tier++) {
            for (int branch = 0; branch < TALENT_SLOTS[tier].length; branch++) {
                if (TALENT_SLOTS[tier][branch] != slot || branch >= branches.size()) {
                    continue;
                }
                for (Talent talent : ClassCatalog.branch(classId, branches.get(branch))) {
                    if (talent.tier() == tier + 1) {
                        return talent;
                    }
                }
            }
        }
        return null;
    }

    /* -------------------------------- помощники -------------------------------- */

    private ItemStack button(Material material, String nameKey, List<Component> lore,
                             Object... placeholders) {
        ItemStack stack = new ItemStack(material == null ? Material.PAPER : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.messages().get(nameKey, placeholders));
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack simple(Material material, String nameKey) {
        return button(material, nameKey, List.of());
    }

    private static Material material(String name) {
        Material found = name == null ? null
                : Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        return found == null ? Material.PAPER : found;
    }

    /** Иконка по виду эффекта: игрок узнаёт ветку по картинке, а не по тексту. */
    static Material materialFor(EffectKind kind) {
        return switch (kind) {
            case MAX_HEALTH -> Material.GOLDEN_APPLE;
            case ARMOR -> Material.IRON_CHESTPLATE;
            case MOVEMENT_SPEED -> Material.FEATHER;
            case KNOCKBACK_RESISTANCE -> Material.SHIELD;
            case MELEE_DAMAGE -> Material.IRON_SWORD;
            case ARROW_DAMAGE -> Material.ARROW;
            case DAMAGE_REDUCTION -> Material.NETHERITE_INGOT;
            case FALL_REDUCTION -> Material.SLIME_BALL;
            case REGEN -> Material.GHAST_TEAR;
            case SATURATION -> Material.BREAD;
        };
    }
}
