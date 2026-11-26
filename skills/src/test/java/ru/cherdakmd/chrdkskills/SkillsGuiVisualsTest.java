package ru.cherdakmd.chrdkskills;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SkillsGuiVisualsTest {
    @Test void navigationAndResetConfirmationKeepExistingBehavior() {
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(SkillsPlugin.class); var service = mock(SkillService.class);
            var fileConfig = new YamlConfiguration(); fileConfig.set("textures.menu", enabled); when(plugin.getConfig()).thenReturn(fileConfig);
            var config = mock(SkillsConfig.class); when(config.enabled()).thenReturn(true); when(plugin.config()).thenReturn(config);
            when(plugin.messages()).thenReturn(Messages.from(SkillsGuiVisualsTest.class.getResourceAsStream("/messages.yml"), null, Logger.getAnonymousLogger()));
            var effects = mock(SkillEffects.class); when(plugin.effects()).thenReturn(effects);
            var player = mock(Player.class); var owner = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(owner);
            var inventory = mock(Inventory.class); var holder = new AtomicReference<InventoryHolder>();
            when(inventory.getHolder()).thenAnswer(c -> holder.get()); when(inventory.getSize()).thenReturn(54);
            Map<Integer, ItemStack> slots = new HashMap<>();
            doAnswer(c -> { slots.clear(); return null; }).when(inventory).clear();
            doAnswer(c -> { slots.put(c.getArgument(0), c.getArgument(1)); return null; }).when(inventory).setItem(anyInt(), nullable(ItemStack.class));
            when(inventory.getItem(anyInt())).thenAnswer(c -> slots.get(c.getArgument(0)));
            var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inventory);
            var gui = new SkillsGui(plugin, service);
            try (var bukkit = mockStatic(Bukkit.class); var items = mockConstruction(ItemStack.class, (item, context) -> {
                var meta = mock(ItemMeta.class); when(item.getItemMeta()).thenReturn(meta);
            })) {
                bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(Component.class)))
                        .thenAnswer(c -> { holder.set(c.getArgument(0)); return inventory; });
                gui.open(player, SkillsMenuApi.Tab.ATTRIBUTES, 0);
                assertEquals(54, slots.size());
                checkModel(slots.get(1), SkillsMenuVisuals.tab(SkillsMenuApi.Tab.ATTRIBUTES, true), enabled);
                checkModel(slots.get(7), SkillsMenuVisuals.tab(SkillsMenuApi.Tab.BUILDS, false), enabled);
                checkModel(slots.get(3), SkillsMenuVisuals.Control.GUIDE.model(), enabled);
                checkModel(slots.get(53), SkillsMenuVisuals.Control.RESET_ATTRIBUTES.model(), enabled);
                var stranger = mock(Player.class); when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
                var foreign = click(view, stranger, 53); gui.onClick(foreign); verify(foreign).setCancelled(true);
                var outside = click(view, player, 54); gui.onClick(outside); verify(outside).setCancelled(true);
                verify(service, never()).resetAttributes(any());
                var drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(view); gui.onDrag(drag); verify(drag).setCancelled(true);
                gui.onClick(click(view, player, 53));
                checkModel(slots.get(53), SkillsMenuVisuals.Control.CONFIRM_ATTRIBUTES.model(), enabled);
                verify(service, never()).resetAttributes(any());
                gui.onClick(click(view, player, 53)); verify(service).resetAttributes(player); verify(effects).apply(player);
                checkModel(slots.get(53), SkillsMenuVisuals.Control.RESET_ATTRIBUTES.model(), enabled);
                gui.onClick(click(view, player, 7));
                checkModel(slots.get(7), SkillsMenuVisuals.tab(SkillsMenuApi.Tab.BUILDS, true), enabled);
                checkModel(slots.get(53), SkillsMenuVisuals.Control.RESET_BUILDS.model(), enabled);
                gui.onClick(click(view, player, 53));
                checkModel(slots.get(53), SkillsMenuVisuals.Control.CONFIRM_BUILDS.model(), enabled);
                verify(service, never()).resetBranches(any()); verify(plugin, never()).xpShop();
                gui.onClick(click(view, player, 1)); gui.onClick(click(view, player, 7));
                checkModel(slots.get(53), SkillsMenuVisuals.Control.RESET_BUILDS.model(), enabled);
                verify(player, never()).getInventory();
                for (var item : items.constructed()) verify(item.getItemMeta(), never()).getPersistentDataContainer();
            }
        }
    }
    private static void checkModel(ItemStack item, org.bukkit.NamespacedKey model, boolean enabled) {
        assertNotNull(item); if (enabled) verify(item.getItemMeta()).setItemModel(model);
        else verify(item.getItemMeta(), never()).setItemModel(any());
    }
    private static InventoryClickEvent click(InventoryView view, Player player, int slot) {
        var event = mock(InventoryClickEvent.class); when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player); when(event.getRawSlot()).thenReturn(slot); return event;
    }
}
