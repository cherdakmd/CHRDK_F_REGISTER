package ru.cherdakmd.chrdkquests;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuestsGuiVisualsTest {
    @Test void questScreensKeepConfiguredIconsAndDelegateClaims() {
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(QuestsPlugin.class);
            var config = new YamlConfiguration(); config.set("textures.menu", enabled); when(plugin.getConfig()).thenReturn(config);
            var messages = Messages.from(QuestsGuiVisualsTest.class.getResourceAsStream("/messages.yml"), null, Logger.getAnonymousLogger());
            when(plugin.messages()).thenReturn(messages);
            var service = mock(QuestService.class); var store = mock(QuestStore.class); var questConfig = mock(QuestConfig.class);
            var money = mock(QuestMoneyCoordinator.class); when(money.preview(any())).thenReturn("preview");
            when(plugin.service()).thenReturn(service); when(plugin.store()).thenReturn(store); when(plugin.config()).thenReturn(questConfig); when(plugin.money()).thenReturn(money);
            var player = mock(Player.class); var owner = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(owner);
            var quest = new Quest("dig", "npc", "Dig", "", QuestType.MINE, List.of(), List.of(Material.STONE), 3, new QuestReward(10, 0, List.of()), 0);
            var npc = new QuestNpc("npc", "NPC", "", Material.DIAMOND, "", List.of(quest));
            assertEquals(List.of(quest), npc.quests());
            when(questConfig.quest("dig")).thenReturn(quest); when(questConfig.npc("npc")).thenReturn(npc);
            var faction = new QuestFaction("faction", "Faction", "", Material.EMERALD, "", null);
            when(questConfig.factionList()).thenReturn(List.of(faction)); when(questConfig.npcList()).thenReturn(List.of(npc));
            when(service.factionId(owner)).thenReturn("");
            when(service.availability(eq(owner), eq(quest), anyInt())).thenReturn(QuestService.Availability.READY);
            var inventory = mock(Inventory.class); var holder = new AtomicReference<InventoryHolder>(); when(inventory.getHolder()).thenAnswer(c -> holder.get());
            Map<Integer, ItemStack> slots = new HashMap<>();
            doAnswer(c -> { slots.put(c.getArgument(0), c.getArgument(1)); return null; }).when(inventory).setItem(anyInt(), nullable(ItemStack.class));
            var gui = new QuestsGui(plugin);
            try (var bukkit = mockStatic(Bukkit.class); var items = mockConstruction(ItemStack.class, (item, context) -> {
                var meta = mock(ItemMeta.class); when(item.getItemMeta()).thenReturn(meta);
            })) {
                bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(Component.class)))
                        .thenAnswer(c -> { holder.set(c.getArgument(0)); return inventory; });
                gui.open(player);
                assertEquals(java.util.Set.of(4, 19, 28, 49), slots.keySet());
                checkModel(slots.get(4), QuestMenuVisuals.Control.INFO.model(), enabled);
                verify(slots.get(19).getItemMeta(), never()).setItemModel(any()); // configured faction icon
                verify(slots.get(28).getItemMeta(), never()).setItemModel(any()); // configured NPC icon
                slots.clear(); gui.openNpc(player, npc);
                assertEquals(java.util.Set.of(4, 10, 45, 49), slots.keySet());
                verify(slots.get(4).getItemMeta(), never()).setItemModel(any()); // configured NPC icon
                checkModel(slots.get(10), QuestMenuVisuals.quest(QuestType.MINE, QuestService.Availability.READY), enabled);
                checkModel(slots.get(45), QuestMenuVisuals.Control.BACK.model(), enabled);
                checkModel(slots.get(49), QuestMenuVisuals.Control.CLOSE.model(), enabled);
                var stranger = mock(Player.class); when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
                var foreign = click(inventory, stranger, 10); gui.onClick(foreign); verify(foreign).setCancelled(true);
                var lower = click(inventory, player, 54); gui.onClick(lower); verify(lower).setCancelled(true);
                verify(plugin, never()).claim(any(), any());
                var drag = mock(InventoryDragEvent.class); when(drag.getInventory()).thenReturn(inventory); gui.onDrag(drag); verify(drag).setCancelled(true);
                var click = click(inventory, player, 10); gui.onClick(click); verify(click).setCancelled(true);
                verify(plugin).claim(player, quest); // actual validation and award are not bypassed
                verify(player, never()).getInventory();
                for (var item : items.constructed()) verify(item.getItemMeta(), never()).getPersistentDataContainer();
            }
        }
    }
    private static void checkModel(ItemStack item, org.bukkit.NamespacedKey model, boolean enabled) {
        assertNotNull(item); if (enabled) verify(item.getItemMeta()).setItemModel(model);
        else verify(item.getItemMeta(), never()).setItemModel(any());
    }
    private static InventoryClickEvent click(Inventory inventory, Player player, int slot) {
        var click = mock(InventoryClickEvent.class); when(click.getInventory()).thenReturn(inventory);
        when(click.getWhoClicked()).thenReturn(player); when(click.getRawSlot()).thenReturn(slot); return click;
    }
}
