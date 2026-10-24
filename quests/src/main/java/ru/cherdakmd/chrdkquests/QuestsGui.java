package ru.cherdakmd.chrdkquests;

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
 * Меню заданий: фракции и персонажи, затем цепочка выбранного NPC (1.4.161).
 *
 * <p>Первый экран — состояние игрока, фракции и персонажи; второй — задания
 * одного персонажа с прогрессом, наградой и статусом. Нажатие на задание
 * передаётся в {@link QuestsPlugin#claim}: меню не решает, можно ли сдавать,
 * поэтому клик не может выдать награду в обход правил.</p>
 */
public final class QuestsGui implements Listener {

    private static final int SIZE = 54;
    private static final int INFO_SLOT = 4;
    private static final int CLOSE_SLOT = 49;
    private static final int BACK_SLOT = 45;
    /** Сетка фракций: две строки по четыре. */
    private static final int[] FACTION_SLOTS = {19, 20, 21, 22};
    /** Сетка персонажей: две строки по семь. */
    private static final int[] NPC_SLOTS = {28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};
    /** Сетка заданий: две строки по семь. */
    private static final int[] QUEST_SLOTS = {10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25};

    /** Владелец главного меню. */
    static final class MainHolder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        MainHolder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Владелец меню цепочки: ид персонажа и порядок заданий на экране. */
    static final class NpcHolder implements InventoryHolder {
        private final UUID owner;
        private final String npcId;
        private final List<String> questIds;
        private Inventory inventory;

        NpcHolder(UUID owner, String npcId, List<String> questIds) {
            this.owner = owner;
            this.npcId = npcId;
            this.questIds = List.copyOf(questIds);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final QuestsPlugin plugin;

    public QuestsGui(QuestsPlugin plugin) {
        this.plugin = plugin;
    }

    /* --------------------------------- экраны --------------------------------- */

    /** Главное меню. */
    public void open(Player player) {
        if (player == null) {
            return;
        }
        MainHolder holder = new MainHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("menu-title"));
        holder.inventory = inventory;

        inventory.setItem(INFO_SLOT, infoItem(player));

        List<QuestFaction> factions = plugin.config().factionList();
        for (int index = 0; index < factions.size() && index < FACTION_SLOTS.length; index++) {
            inventory.setItem(FACTION_SLOTS[index], factionItem(player, factions.get(index)));
        }
        List<QuestNpc> npcs = plugin.config().npcList();
        for (int index = 0; index < npcs.size() && index < NPC_SLOTS.length; index++) {
            inventory.setItem(NPC_SLOTS[index], npcItem(player, npcs.get(index)));
        }
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "menu-close"));
        player.openInventory(inventory);
    }

    /** Цепочка персонажа. */
    public void openNpc(Player player, QuestNpc npc) {
        if (player == null || npc == null) {
            return;
        }
        List<String> questIds = new ArrayList<>();
        for (Quest quest : npc.quests()) {
            questIds.add(quest.id());
        }
        NpcHolder holder = new NpcHolder(player.getUniqueId(), npc.id(), questIds);
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("menu-npc-title", "npc", npc.name()));
        holder.inventory = inventory;

        inventory.setItem(INFO_SLOT, npcInfoItem(player, npc));
        for (int index = 0; index < npc.quests().size() && index < QUEST_SLOTS.length; index++) {
            inventory.setItem(QUEST_SLOTS[index], questItem(player, npc.quests().get(index)));
        }
        inventory.setItem(BACK_SLOT, button(Material.ARROW, "menu-back"));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "menu-close"));
        player.openInventory(inventory);
    }

    /* --------------------------------- предметы -------------------------------- */

    private ItemStack infoItem(Player player) {
        UUID id = player.getUniqueId();
        QuestFaction faction = plugin.service().faction(id);
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("menu-info-faction",
                "faction", faction == null ? plugin.messages().plain("faction-nobody") : faction.name()));
        if (faction != null) {
            int reputation = plugin.service().reputation(id);
            lore.add(plugin.messages().get("menu-info-rank",
                    "rank", plugin.messages().plain("rank." + plugin.service().rankId(id)),
                    "number", String.valueOf(plugin.service().rankNumber(id))));
            lore.add(plugin.messages().get("menu-info-reputation",
                    "reputation", String.valueOf(reputation),
                    "next", faction.nextThreshold(reputation) < 0
                            ? plugin.messages().plain("faction-max")
                            : String.valueOf(faction.nextThreshold(reputation))));
        }
        lore.add(plugin.messages().get("menu-info-done",
                "done", String.valueOf(plugin.service().doneCount(id)),
                "total", String.valueOf(plugin.service().totalQuests())));
        lore.add(plugin.messages().get("menu-info-claims",
                "left", String.valueOf(plugin.service().claimsLeft(id, plugin.today())),
                "limit", String.valueOf(plugin.config().claimsPerDay())));
        lore.add(plugin.messages().get("menu-info-ready",
                "ready", String.valueOf(plugin.service().readyCount(id))));
        return item(Material.WRITABLE_BOOK, "menu-info-name", lore);
    }

    private ItemStack factionItem(Player player, QuestFaction faction) {
        UUID id = player.getUniqueId();
        boolean current = faction.id().equals(plugin.service().factionId(id));
        int reputation = plugin.store().reputation(id, faction.id());
        List<Component> lore = new ArrayList<>();
        if (!faction.description().isEmpty()) {
            lore.add(plugin.messages().get("menu-faction-description",
                    "description", faction.description()));
        }
        lore.add(plugin.messages().get("menu-faction-reputation",
                "reputation", String.valueOf(reputation)));
        lore.add(plugin.messages().get("menu-faction-rank",
                "rank", plugin.messages().plain("rank." + faction.rankId(reputation))));
        lore.add(plugin.messages().get(current ? "menu-faction-current" : "menu-faction-join-hint"));
        return item(faction.icon(), null, faction.name(), lore);
    }

    private ItemStack npcItem(Player player, QuestNpc npc) {
        UUID id = player.getUniqueId();
        int done = 0;
        for (Quest quest : npc.quests()) {
            if (plugin.store().done(id, quest.id())) {
                done++;
            }
        }
        Quest current = plugin.service().current(id, npc);
        List<Component> lore = new ArrayList<>();
        if (!npc.description().isEmpty()) {
            lore.add(plugin.messages().get("menu-npc-description",
                    "description", npc.description()));
        }
        QuestFaction faction = plugin.config().faction(npc.factionId());
        lore.add(plugin.messages().get("menu-npc-faction",
                "faction", faction == null
                        ? plugin.messages().plain("faction-neutral") : faction.name()));
        lore.add(plugin.messages().get("menu-npc-progress",
                "done", String.valueOf(done),
                "total", String.valueOf(npc.quests().size())));
        if (current != null) {
            lore.add(plugin.messages().get("menu-npc-current",
                    "name", current.name()));
        }
        lore.add(plugin.messages().get("menu-npc-click"));
        return item(npc.icon(), null, npc.name(), lore);
    }

    private ItemStack npcInfoItem(Player player, QuestNpc npc) {
        UUID id = player.getUniqueId();
        int done = 0;
        for (Quest quest : npc.quests()) {
            if (plugin.store().done(id, quest.id())) {
                done++;
            }
        }
        List<Component> lore = new ArrayList<>();
        if (!npc.description().isEmpty()) {
            lore.add(plugin.messages().get("menu-npc-description",
                    "description", npc.description()));
        }
        lore.add(plugin.messages().get("menu-npc-progress",
                "done", String.valueOf(done),
                "total", String.valueOf(npc.quests().size())));
        return item(npc.icon(), null, npc.name(), lore);
    }

    private ItemStack questItem(Player player, Quest quest) {
        UUID id = player.getUniqueId();
        int have = quest.type() == QuestType.GATHER
                ? Math.min(plugin.countItems(player, quest), quest.amount())
                : plugin.service().progress(id, quest);
        QuestService.Availability availability = plugin.service().availability(id, quest, have);
        List<Component> lore = new ArrayList<>();
        if (!quest.description().isEmpty()) {
            lore.add(plugin.messages().get("menu-quest-description",
                    "description", quest.description()));
        }
        lore.add(plugin.messages().get("menu-quest-target",
                "target", Messages.trusted(QuestText.joinTags(quest.targetTags()))));
        lore.add(plugin.messages().get("menu-quest-progress",
                "have", String.valueOf(have),
                "need", String.valueOf(quest.amount())));
        lore.add(plugin.messages().get("menu-quest-reward",
                "xp", String.valueOf(quest.reward().xp()),
                "reputation", String.valueOf(quest.reward().reputation())));
        if (quest.minRank() > 0) {
            lore.add(plugin.messages().get("menu-quest-rank",
                    "rank", String.valueOf(quest.minRank())));
        }
        lore.add(plugin.messages().get("menu-quest-status",
                "status", Messages.trusted(plugin.messages().raw(statusKey(availability)))));
        if (availability == QuestService.Availability.READY) {
            lore.add(plugin.messages().get("menu-quest-claim"));
        }
        Material icon = switch (quest.type()) {
            case KILL -> Material.IRON_SWORD;
            case MINE -> Material.IRON_PICKAXE;
            case GATHER -> Material.CHEST;
        };
        return item(icon, null, quest.name(), lore);
    }

    private String statusKey(QuestService.Availability availability) {
        return switch (availability) {
            case AVAILABLE -> "status-available";
            case IN_PROGRESS -> "status-progress";
            case READY -> "status-ready";
            case DONE -> "status-done";
            case LOCKED -> "status-locked";
            case WRONG_FACTION -> "status-faction";
            case RANK -> "status-rank";
        };
    }

    private ItemStack button(Material material, String nameKey) {
        return item(material, nameKey, null, List.of());
    }

    private ItemStack item(Material material, String nameKey, String plainName,
                           List<Component> lore) {
        ItemStack stack = new ItemStack(material == null ? Material.PAPER : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (plainName != null) {
                meta.displayName(plugin.messages().get("menu-item-name", "name", plainName));
            } else if (nameKey != null) {
                meta.displayName(plugin.messages().get(nameKey));
            }
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /* --------------------------------- клики ---------------------------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder rawHolder = event.getInventory().getHolder();
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (rawHolder instanceof MainHolder holder) {
            event.setCancelled(true);
            if (!holder.owner.equals(player.getUniqueId()) || event.getRawSlot() >= SIZE) {
                return;
            }
            clickMain(player, event.getRawSlot());
            return;
        }
        if (rawHolder instanceof NpcHolder holder) {
            event.setCancelled(true);
            if (!holder.owner.equals(player.getUniqueId()) || event.getRawSlot() >= SIZE) {
                return;
            }
            clickNpc(player, holder, event.getRawSlot());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MainHolder
                || event.getInventory().getHolder() instanceof NpcHolder) {
            event.setCancelled(true);
        }
    }

    private void clickMain(Player player, int slot) {
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        int factionIndex = indexOf(FACTION_SLOTS, slot);
        if (factionIndex >= 0) {
            List<QuestFaction> factions = plugin.config().factionList();
            if (factionIndex < factions.size()) {
                clickFaction(player, factions.get(factionIndex));
            }
            return;
        }
        int npcIndex = indexOf(NPC_SLOTS, slot);
        if (npcIndex >= 0) {
            List<QuestNpc> npcs = plugin.config().npcList();
            if (npcIndex < npcs.size()) {
                openNpc(player, npcs.get(npcIndex));
            }
        }
    }

    private void clickFaction(Player player, QuestFaction faction) {
        UUID id = player.getUniqueId();
        String current = plugin.service().factionId(id);
        if (faction.id().equals(current)) {
            plugin.messages().send(player, "join-same");
            return;
        }
        if (!current.isEmpty()) {
            QuestFaction other = plugin.config().faction(current);
            plugin.messages().send(player, "join-switch",
                    "current", other == null ? current : other.name());
            return;
        }
        QuestService.Result result = plugin.service().join(id, faction.id());
        if (result == QuestService.Result.OK) {
            plugin.requestSave();
            plugin.messages().send(player, "join-ok", "faction", faction.name());
            player.closeInventory();
        } else {
            plugin.messages().send(player, "join-unknown", "faction", faction.id());
        }
    }

    private void clickNpc(Player player, NpcHolder holder, int slot) {
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == BACK_SLOT) {
            open(player);
            return;
        }
        int questIndex = indexOf(QUEST_SLOTS, slot);
        if (questIndex < 0 || questIndex >= holder.questIds.size()) {
            return;
        }
        Quest quest = plugin.config().quest(holder.questIds.get(questIndex));
        if (quest == null) {
            plugin.messages().send(player, "claim-unknown");
            return;
        }
        plugin.claim(player, quest);
        // Перерисовать: прогресс и награды уже изменились.
        QuestNpc npc = plugin.config().npc(holder.npcId);
        if (npc != null) {
            openNpc(player, npc);
        }
    }

    private static int indexOf(int[] slots, int slot) {
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] == slot) {
                return index;
            }
        }
        return -1;
    }
}
