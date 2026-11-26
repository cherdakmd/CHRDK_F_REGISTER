package ru.cherdakmd.chrdkachievements;

import java.util.ArrayList;
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
import ru.cherdakmd.chrdkregister.api.MoneyMilestoneBridge;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AchievementGuiVisualsTest {
    @Test void pagesBadgeAndOwnershipRetainExistingBehavior() {
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(AchievementsPlugin.class); var service = mock(AchievementService.class);
            var file = new YamlConfiguration(); file.set("textures.menu", enabled); when(plugin.getConfig()).thenReturn(file);
            var config = mock(AchievementsConfig.class); when(config.badges()).thenReturn(true);
            when(config.rewardOf(any())).thenReturn(new AchievementsConfig.Reward(0, 0)); when(plugin.config()).thenReturn(config);
            var money = mock(MoneyMilestoneBridge.class); when(plugin.money()).thenReturn(money);
            when(plugin.messages()).thenReturn(Messages.from(AchievementGuiVisualsTest.class.getResourceAsStream("/messages.yml"), null, Logger.getAnonymousLogger()));
            when(service.enabled()).thenReturn(true);
            var player = mock(Player.class); var owner = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(owner);
            var defs = new ArrayList<AchievementDef>();
            for (int i = 0; i < 46; i++) defs.add(new AchievementDef("id" + i, AchievementDef.Category.PATH,
                    AchievementMetric.JOINS, 1, 0, 0, i < 2 ? "badge" : null));
            when(service.byCategory(isNull())).thenReturn(defs);
            when(service.byCategory(AchievementDef.Category.PATH)).thenReturn(defs.subList(0, 2));
            when(service.isUnlocked(player, "id0")).thenReturn(true);
            var inventory = mock(Inventory.class); var holder = new AtomicReference<InventoryHolder>();
            when(inventory.getHolder()).thenAnswer(c -> holder.get()); when(inventory.getSize()).thenReturn(54);
            Map<Integer, ItemStack> slots = new HashMap<>();
            doAnswer(c -> { slots.put(c.getArgument(0), c.getArgument(1)); return null; }).when(inventory).setItem(anyInt(), nullable(ItemStack.class));
            var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inventory);
            var gui = new AchievementGui(plugin, service);
            try (var bukkit = mockStatic(Bukkit.class); var items = mockConstruction(ItemStack.class, (item, context) -> {
                var meta = mock(ItemMeta.class); when(item.getItemMeta()).thenReturn(meta);
            })) {
                bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(Component.class)))
                        .thenAnswer(c -> { slots.clear(); holder.set(c.getArgument(0)); return inventory; });
                gui.open(player, null);
                assertEquals(54, slots.size());
                checkModel(slots.get(0), AchievementMenuVisuals.entry(AchievementDef.Category.PATH, true), enabled);
                checkModel(slots.get(1), AchievementMenuVisuals.entry(AchievementDef.Category.PATH, false), enabled);
                checkModel(slots.get(44), AchievementMenuVisuals.Control.ATLAS.model(), enabled);
                checkModel(slots.get(49), AchievementMenuVisuals.tab("tab-all", true), enabled);
                checkModel(slots.get(45), AchievementMenuVisuals.tab("tab-path", false), enabled);
                checkModel(slots.get(50), AchievementMenuVisuals.Control.PROGRESS.model(), enabled);
                checkModel(slots.get(51), AchievementMenuVisuals.Control.PREV.model(), enabled);
                checkModel(slots.get(52), AchievementMenuVisuals.Control.NEXT.model(), enabled);
                checkModel(slots.get(53), AchievementMenuVisuals.Control.CLOSE.model(), enabled);
                var stranger = mock(Player.class); when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
                var foreign = click(view, stranger, 0); gui.onClick(foreign); verify(foreign).setCancelled(true);
                var outside = click(view, player, 54); gui.onClick(outside); verify(outside).setCancelled(true);
                var drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(view); gui.onDrag(drag); verify(drag).setCancelled(true);
                gui.onClick(click(view, player, 1)); verify(service, never()).setBadge(any(), any());
                gui.onClick(click(view, player, 0)); verify(service).setBadge(player, "id0");
                gui.onClick(click(view, player, 52));
                assertEquals(12, slots.size()); // two entries plus the ten bottom/navigation items
                checkModel(slots.get(0), AchievementMenuVisuals.entry(AchievementDef.Category.PATH, false), enabled);
                gui.onClick(click(view, player, 52));
                checkModel(slots.get(0), AchievementMenuVisuals.entry(AchievementDef.Category.PATH, true), enabled);
                gui.onClick(click(view, player, 45));
                checkModel(slots.get(45), AchievementMenuVisuals.tab("tab-path", true), enabled);
                assertFalse(slots.containsKey(51)); assertFalse(slots.containsKey(52));
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
