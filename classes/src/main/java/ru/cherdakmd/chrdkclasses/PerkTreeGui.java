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
import ru.cherdakmd.chrdkskills.tree.PerkTreeRegistry;
import ru.cherdakmd.chrdkskills.tree.PerkTreeState;
import ru.cherdakmd.chrdkskills.tree.TreeDomain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Меню дерева перков. Все решения принимает {@link PerkTreeService}, поэтому
 * клики и команды проходят одинаковую проверку тира, зависимостей, токенов и
 * пыли. Иконки узлов не являются предметами игрока: все клики по меню
 * отменяются, драг запрещён.
 */
final class PerkTreeGui implements Listener {

    private static final int SLOT_RESET = 49;
    private static final int SLOT_CLOSE = 50;
    private static final int[] NODE_SLOTS =
            {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 28, 29, 30};

    private static final class Holder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        private Holder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record View(TreeDomain domain, Map<Integer, String> slots) {
    }

    private final ClassesPlugin plugin;
    private final PerkTreeService trees;
    private final Map<UUID, View> views = new HashMap<>();

    PerkTreeGui(ClassesPlugin plugin, PerkTreeService trees) {
        this.plugin = plugin;
        this.trees = trees;
    }

    void open(Player player, TreeDomain domain) {
        if (player == null || domain == null) {
            return;
        }
        PerkTreeDefinition definition = PerkTreeRegistry.of(domain).orElse(null);
        if (definition == null) {
            plugin.messages().send(player, "perktree-unknown-domain");
            return;
        }
        PerkTreeState state = trees.state(player.getUniqueId(), domain,
                plugin.service().level(player.getUniqueId()));
        if (state == null) {
            plugin.messages().send(player, "perktree-no-tree");
            return;
        }
        Holder holder = new Holder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, 54,
                plugin.messages().get("perktree-title", "domain",
                        PerkTreeText.domain(plugin.messages(), domain)));
        holder.inventory = inventory;
        Map<Integer, String> slots = new HashMap<>();
        int index = 0;
        for (PerkNode node : definition.nodes()) {
            if (index >= NODE_SLOTS.length) {
                break;
            }
            int slot = NODE_SLOTS[index++];
            inventory.setItem(slot, icon(node, state, definition));
            slots.put(slot, node.id());
        }
        inventory.setItem(SLOT_RESET, named(Material.BARRIER,
                plugin.messages().get("perktree-reset-button")));
        inventory.setItem(SLOT_CLOSE, named(Material.OAK_DOOR,
                plugin.messages().get("perktree-close-button")));
        views.put(player.getUniqueId(), new View(domain, slots));
        player.openInventory(inventory);
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
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler(ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        if (!holder.owner.equals(player.getUniqueId())) {
            return;
        }
        View view = views.get(player.getUniqueId());
        if (view == null) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == SLOT_CLOSE) {
            views.remove(player.getUniqueId());
            player.closeInventory();
            return;
        }
        if (slot == SLOT_RESET) {
            PerkTreeService.Result request = trees.requestReset(player.getUniqueId(),
                    view.domain(), plugin.service().level(player.getUniqueId()),
                    System.currentTimeMillis());
            if (request.ok()) {
                plugin.messages().send(player, "perktree-reset-pending",
                        "token", request.reason());
            } else {
                plugin.messages().send(player, "perktree-reset-failed");
            }
            return;
        }
        String nodeId = view.slots().get(slot);
        if (nodeId == null) {
            return;
        }
        PerkNode node = PerkTreeRegistry.of(view.domain()).orElseThrow().node(nodeId).orElseThrow();
        int classLevel = plugin.service().level(player.getUniqueId());
        PerkTreeService.Result result = node.ability()
                ? trees.select(player.getUniqueId(), view.domain(), nodeId, classLevel)
                : trees.unlock(player.getUniqueId(), view.domain(), nodeId, classLevel);
        if (result.ok()) {
            plugin.messages().send(player,
                    node.ability() ? "perktree-selected" : "perktree-unlocked",
                    "node", PerkTreeText.nodeNamePlain(plugin.messages(), nodeId));
        } else {
            plugin.messages().send(player, "perktree-locked", "reason", result.reason());
        }
        open(player, view.domain());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    /** Открытые меню и неподтверждённый сброс не держим после выхода игрока. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        views.remove(player);
        trees.forget(player);
    }
}
