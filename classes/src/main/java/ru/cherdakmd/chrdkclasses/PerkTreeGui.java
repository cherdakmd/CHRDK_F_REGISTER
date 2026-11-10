package ru.cherdakmd.chrdkclasses;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkskills.tree.PerkNode;
import ru.cherdakmd.chrdkskills.tree.PerkTreeDefinition;
import ru.cherdakmd.chrdkskills.tree.PerkTreeMath;
import ru.cherdakmd.chrdkskills.tree.PerkTreeMenuApi;
import ru.cherdakmd.chrdkskills.tree.PerkTreeRegistry;
import ru.cherdakmd.chrdkskills.tree.PerkTreeState;
import ru.cherdakmd.chrdkskills.tree.TreeDomain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Меню дерева перков. Все решения принимает {@link PerkTreeService}, поэтому
 * клики и команды проходят одинаковую проверку тира, зависимостей, токенов и
 * пыли. Иконки узлов не являются предметами игрока: все клики по меню
 * отменяются, драг запрещён.
 *
 * <p>Дерево на 10 тиров не помещается в 15 слотов, поэтому меню
 * постраничное: стрелки в нижнем ряду листают узлы, а покупка остаётся на той
 * же странице. Книга «Как качать» и панель прогресса отвечают на два главных
 * вопроса игрока: как растёт дерево и где брать пыль.</p>
 *
 * <p>Реализует {@link PerkTreeMenuApi}: {@code /skills} из CHRDK_Skills
 * спрашивает готовые иконки и обработчик кликов через {@code ServicesManager}
 * и рисует их в своём инвентаре. {@link #items} только собирает предметы,
 * {@link #click} только меняет состояние и шлёт сообщения — ни один из них
 * не открывает окно. Отдельный {@code /perktree} без CHRDK_Skills по-прежнему
 * работает сам и тоже перерисовывает свой инвентарь на месте.</p>
 */
final class PerkTreeGui implements Listener, PerkTreeMenuApi {

    private static final int SLOT_PREV = 45;
    private static final int SLOT_GUIDE = 46;
    private static final int SLOT_RESET = 49;
    private static final int SLOT_CLOSE = 50;
    private static final int SLOT_STATUS = 52;
    private static final int SLOT_NEXT = 53;
    private static final int[] NODE_SLOTS =
            {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 28, 29, 30};

    private static final class Holder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;
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

    /** Всё, что нужно для отрисовки страницы без обращения к Bukkit. */
    private record Page(Map<Integer, ItemStack> items, int page, int pages, Component title) {
    }

    private final ClassesPlugin plugin;
    private final PerkTreeService trees;

    PerkTreeGui(ClassesPlugin plugin, PerkTreeService trees) {
        this.plugin = plugin;
        this.trees = trees;
    }

    /* ------------------------------ сервисный API ------------------------------ */

    @Override
    public List<TreeDomain> domains(Player player) {
        int classLevel = plugin.service().level(player.getUniqueId());
        List<TreeDomain> result = new ArrayList<>();
        // Только зарегистрированные дефиниции, не все 14 зарезервированных id.
        for (TreeDomain domain : TreeDomain.values()) {
            if (PerkTreeRegistry.defined().contains(domain)
                    && trees.state(player.getUniqueId(), domain, classLevel) != null) {
                result.add(domain);
            }
        }
        return result;
    }

    @Override
    public TreeDomain primary(Player player) {
        PlayerClass role = plugin.service().classOf(player.getUniqueId());
        return ClassPerkTreeBridge.primaryDomain(role);
    }

    @Override
    public int pages(TreeDomain domain) {
        return PerkTreeRegistry.of(domain)
                .map(definition -> pageCount(definition.size(), NODE_SLOTS.length))
                .orElse(1);
    }

    @Override
    public String domainName(TreeDomain domain) {
        return PerkTreeText.domain(plugin.messages(), domain);
    }

    @Override
    public Component title(Player player, TreeDomain domain, int page) {
        Page rendered = page(player, domain, page);
        return rendered == null ? Component.empty() : rendered.title();
    }

    @Override
    public Map<Integer, ItemStack> items(Player player, TreeDomain domain, int page) {
        Page rendered = page(player, domain, page);
        return rendered == null ? Map.of() : rendered.items();
    }

    @Override
    public List<Component> guide(Player player) {
        return plugin.messages().getList("perktree-guide");
    }

    @Override
    public int closeSlot() {
        return SLOT_CLOSE;
    }

    /**
     * Обработка клика без открытия окон. Возвращает, что сделать принимающей
     * стороне: ничего, перерисовать страницу или закрыть меню.
     */
    @Override
    public PerkTreeMenuApi.Result click(Player player, TreeDomain domain, int requestedPage, int rawSlot) {
        PerkTreeDefinition definition = PerkTreeRegistry.of(domain).orElse(null);
        if (definition == null) {
            plugin.messages().send(player, "perktree-unknown-domain");
            return PerkTreeMenuApi.Result.none(domain, requestedPage);
        }
        int pages = pageCount(definition.size(), NODE_SLOTS.length);
        int current = clampPage(requestedPage, pages);
        PerkTreeState state = trees.state(player.getUniqueId(), domain,
                plugin.service().level(player.getUniqueId()));
        if (state == null) {
            plugin.messages().send(player, "perktree-no-tree");
            return PerkTreeMenuApi.Result.none(domain, current);
        }
        if (rawSlot == SLOT_CLOSE) {
            return PerkTreeMenuApi.Result.close(domain, current);
        }
        if (rawSlot == SLOT_PREV) {
            return PerkTreeMenuApi.Result.repaint(domain, clampPage(current - 1, pages));
        }
        if (rawSlot == SLOT_NEXT) {
            return PerkTreeMenuApi.Result.repaint(domain, clampPage(current + 1, pages));
        }
        if (rawSlot == SLOT_GUIDE) {
            plugin.messages().sendList(player, "perktree-guide");
            return PerkTreeMenuApi.Result.none(domain, current);
        }
        if (rawSlot == SLOT_STATUS) {
            return PerkTreeMenuApi.Result.none(domain, current);
        }
        if (rawSlot == SLOT_RESET) {
            PerkTreeService.Result request = trees.requestReset(player.getUniqueId(),
                    domain, plugin.service().level(player.getUniqueId()),
                    System.currentTimeMillis());
            if (request.ok()) {
                plugin.messages().send(player, "perktree-reset-pending",
                        "token", request.reason());
            } else {
                plugin.messages().send(player, "perktree-reset-failed");
            }
            return PerkTreeMenuApi.Result.none(domain, current);
        }
        String nodeId = nodeIdAt(definition, current, rawSlot);
        if (nodeId == null) {
            return PerkTreeMenuApi.Result.none(domain, current);
        }
        PerkNode node = definition.node(nodeId).orElse(null);
        if (node == null) {
            return PerkTreeMenuApi.Result.none(domain, current);
        }
        int classLevel = plugin.service().level(player.getUniqueId());
        PerkTreeService.Result result = node.ability()
                ? trees.select(player.getUniqueId(), domain, nodeId, classLevel)
                : trees.unlock(player.getUniqueId(), domain, nodeId, classLevel);
        if (result.ok()) {
            plugin.messages().send(player,
                    node.ability() ? "perktree-selected" : "perktree-unlocked",
                    "node", PerkTreeText.nodeNamePlain(plugin.messages(), nodeId));
        } else {
            plugin.messages().send(player, "perktree-locked", "reason", result.reason());
        }
        return PerkTreeMenuApi.Result.repaint(domain, current);
    }

    /** Слот → id узла: страница вычисляется из номера, состояние не хранится. */
    static String nodeIdAt(PerkTreeDefinition definition, int page, int rawSlot) {
        int index = -1;
        for (int slot = 0; slot < NODE_SLOTS.length; slot++) {
            if (NODE_SLOTS[slot] == rawSlot) {
                index = slot;
                break;
            }
        }
        if (index < 0) {
            return null;
        }
        int nodeIndex = page * NODE_SLOTS.length + index;
        return nodeIndex < definition.size() ? definition.nodes().get(nodeIndex).id() : null;
    }

    /* ------------------------------- отрисовка -------------------------------- */

    /** Собирает иконки страницы. Пустой результат — дерево недоступно игроку. */
    private Page page(Player player, TreeDomain domain, int requestedPage) {
        if (domain == null) {
            return null;
        }
        PerkTreeDefinition definition = PerkTreeRegistry.of(domain).orElse(null);
        if (definition == null) {
            return null;
        }
        PerkTreeState state = trees.state(player.getUniqueId(), domain,
                plugin.service().level(player.getUniqueId()));
        if (state == null) {
            return null;
        }
        int pages = pageCount(definition.size(), NODE_SLOTS.length);
        int current = clampPage(requestedPage, pages);
        Messages messages = plugin.messages();
        String domainName = PerkTreeText.domain(messages, domain);
        Component title = pages > 1
                ? messages.get("perktree-title-page", "domain", domainName,
                        "page", current + 1, "pages", pages)
                : messages.get("perktree-title", "domain", domainName);

        Map<Integer, ItemStack> items = new LinkedHashMap<>();
        int from = current * NODE_SLOTS.length;
        for (int index = 0; index < NODE_SLOTS.length && from + index < definition.size(); index++) {
            PerkNode node = definition.nodes().get(from + index);
            items.put(NODE_SLOTS[index], icon(node, state, definition));
        }
        items.put(SLOT_GUIDE, button(Material.BOOK,
                messages.get("perktree-guide-button"),
                messages.getList("perktree-guide")));
        items.put(SLOT_STATUS, button(Material.CLOCK,
                messages.get("perktree-status-button"),
                statusLore(state, definition)));
        if (pages > 1) {
            if (current > 0) {
                items.put(SLOT_PREV, named(Material.ARROW,
                        messages.get("perktree-page-prev")));
            }
            if (current + 1 < pages) {
                items.put(SLOT_NEXT, named(Material.ARROW,
                        messages.get("perktree-page-next")));
            }
        }
        items.put(SLOT_RESET, named(Material.BARRIER,
                messages.get("perktree-reset-button")));
        items.put(SLOT_CLOSE, named(Material.OAK_DOOR,
                messages.get("perktree-close-button")));
        return new Page(items, current, pages, title);
    }

    /** Чистая арифметика страниц: проверяется JVM-тестом без Bukkit. */
    static int pageCount(int nodes, int slots) {
        if (slots <= 0) {
            return 1;
        }
        return Math.max(1, (Math.max(0, nodes) + slots - 1) / slots);
    }

    static int clampPage(int page, int pages) {
        return Math.max(0, Math.min(page, Math.max(1, pages) - 1));
    }

    private List<Component> statusLore(PerkTreeState state, PerkTreeDefinition definition) {
        int tier = PerkTreeMath.availableTier(state.level(), definition.tiers());
        int next = tier >= definition.tiers() ? 0
                : PerkTreeMath.LEVELS_PER_TOKEN
                        - (state.level() % PerkTreeMath.LEVELS_PER_TOKEN);
        return plugin.messages().getList("perktree-status",
                "level", state.level(), "powder", state.powder(),
                "tokens", state.tokensAvailable(), "tier", tier,
                "tiers", definition.tiers(), "next", next,
                "nodes", state.levels().size(), "total", definition.size(),
                "cap", definition.powderCap());
    }

    private ItemStack icon(PerkNode node, PerkTreeState state, PerkTreeDefinition definition) {
        Messages messages = plugin.messages();
        int level = state.levelOf(node.id());
        ItemStack item = named(level > 0 ? Material.EMERALD_BLOCK : Material.PAPER,
                PerkTreeText.nodeName(messages, node.id()));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>(PerkTreeText.description(messages, node.id()));
            lore.add(messages.get("perktree-node-tier",
                    "tier", node.tier(), "level", level, "max", node.maxLevel()));
            lore.add(messages.get("perktree-node-cost",
                    "tokens", node.tokenCost(), "powder", node.powderCost()));
            if (node.tokenCost() == 0) {
                lore.add(messages.get("perktree-node-dust-only"));
            }
            if (level > 0) {
                lore.add(messages.get("perktree-node-open", "level", level, "max", node.maxLevel()));
            }
            int available = PerkTreeMath.availableTier(state.level(), definition.tiers());
            if (node.tier() > available) {
                lore.add(messages.get("perktree-node-locked-tier",
                        "tier", node.tier(), "available", available));
            } else {
                lore.add(messages.get("perktree-node-available", "tier", available));
            }
            if (!node.requires().isEmpty()) {
                List<String> names = node.requires().stream()
                        .map(required -> PerkTreeText.nodeNamePlain(messages, required))
                        .sorted()
                        .toList();
                lore.add(messages.get("perktree-node-requires", "nodes", String.join(", ", names)));
            }
            if (node.core()) {
                lore.add(messages.get("perktree-node-core"));
            }
            if (node.ability()) {
                lore.add(messages.get("perktree-node-ability"));
            }
            lore.add(messages.get(node.ability()
                    ? "perktree-node-hint-select" : "perktree-node-hint-unlock"));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack named(Material material, Component name) {
        return button(material, name, List.of());
    }

    private static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /* ------------------------ отдельное окно /perktree ------------------------ */

    void open(Player player, TreeDomain domain) {
        open(player, domain, 0);
    }

    /** Страница с запасом: несуществующий номер прижимается к границам. */
    void open(Player player, TreeDomain domain, int requestedPage) {
        if (player == null || domain == null) {
            return;
        }
        Page rendered = page(player, domain, requestedPage);
        if (rendered == null) {
            plugin.messages().send(player, PerkTreeRegistry.of(domain).isEmpty()
                    ? "perktree-unknown-domain" : "perktree-no-tree");
            return;
        }
        // Отдельное окно создаётся только при первом открытии. Единое меню
        // /skills этим путём не пользуется: оно берёт items() и рисует в своём
        // собственном инвентаре, не вызывая openInventory повторно.
        Holder holder = player.getOpenInventory().getTopInventory().getHolder() instanceof Holder opened
                && opened.owner.equals(player.getUniqueId()) ? opened : null;
        boolean fresh = holder == null;
        if (fresh) {
            holder = new Holder(player.getUniqueId());
            holder.inventory = Bukkit.createInventory(holder, 54, rendered.title());
        }
        holder.domain = domain;
        holder.page = rendered.page();
        repaint(holder, rendered);
        if (fresh) {
            player.openInventory(holder.inventory);
        }
    }

    /** Чистит слоты и кладёт новые иконки в тот же инвентарь, без второго окна. */
    private void repaint(Holder holder, Page rendered) {
        holder.inventory.clear();
        for (Map.Entry<Integer, ItemStack> entry : rendered.items().entrySet()) {
            holder.inventory.setItem(entry.getKey(), entry.getValue());
        }
    }

    @EventHandler(ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        if (!holder.owner.equals(player.getUniqueId()) || holder.domain == null) {
            return;
        }
        PerkTreeMenuApi.Result result = click(player, holder.domain, holder.page, event.getRawSlot());
        if (result.action() == PerkTreeMenuApi.Action.CLOSE) {
            player.closeInventory();
            return;
        }
        if (result.action() == PerkTreeMenuApi.Action.REPAINT) {
            holder.domain = result.domain();
            holder.page = result.page();
            Page rendered = page(player, holder.domain, holder.page);
            if (rendered != null) {
                repaint(holder, rendered);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    /** Неподтверждённый сброс не держим после выхода игрока. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        trees.forget(player);
    }

}
