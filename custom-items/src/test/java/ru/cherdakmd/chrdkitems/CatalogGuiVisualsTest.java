package ru.cherdakmd.chrdkitems;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogGuiVisualsTest {
    @Test void virtualButtonsPreserveCatalogPreviewsPagesAndClickGuards() {
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(CustomItemsPlugin.class); var cfg = new YamlConfiguration(); cfg.set("textures.catalog-menu", enabled);
            when(plugin.getConfig()).thenReturn(cfg);
            Map<String, ItemCatalog.Definition> catalog = new LinkedHashMap<>();
            for (int i = 0; i < 9; i++) {
                var def = new ItemCatalog.Definition("a" + i, ItemCatalog.Category.WEAPON, Material.IRON_SWORD, "chrdk_items:item/a" + i, "Owner " + i, java.util.List.of("Description"));
                catalog.put(def.id(), def);
            }
            catalog.put(BuiltinItems.SHOVEL.id(), BuiltinItems.SHOVEL);
            var player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.isOnline()).thenReturn(true); when(player.hasPermission("chrdkcustomitems.use")).thenReturn(true);
            var slots = new HashMap<Integer, ItemStack>(); var holder = new AtomicReference<InventoryHolder>();
            var inventory = mock(Inventory.class); when(inventory.getHolder()).thenAnswer(c -> holder.get());
            doAnswer(c -> { slots.put(c.getArgument(0), c.getArgument(1)); return null; }).when(inventory).setItem(anyInt(), any(ItemStack.class));
            var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inventory); when(player.getOpenInventory()).thenReturn(view);
            var scheduler = mock(BukkitScheduler.class); var scheduled = new AtomicReference<Runnable>();
            doAnswer(c -> { scheduled.set(c.getArgument(1)); return mock(BukkitTask.class); }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
            var gui = new CatalogGui(plugin, catalog);
            try (var bukkit = mockStatic(Bukkit.class); var items = mockConstruction(ItemStack.class, (item, context) -> {
                var meta = mock(ItemMeta.class); when(item.getItemMeta()).thenReturn(meta);
            })) {
                bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(Component.class))).thenAnswer(c -> {
                    holder.set(c.getArgument(0)); slots.clear(); return inventory;
                });
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                gui.open(player, null, 1);
                check(slots.get(9), CatalogMenuVisuals.Control.ALL.model(true), enabled);
                check(slots.get(10), CatalogMenuVisuals.Control.WEAPON.model(false), enabled);
                check(slots.get(53), CatalogMenuVisuals.Control.NEXT.model(false), enabled);
                assertFalse(slots.containsKey(45));
                var preview = slots.get(19); verify(preview.getItemMeta()).displayName(Component.text("Owner 0"));
                verify(preview.getItemMeta(), never()).setItemModel(any()); verify(preview.getItemMeta(), never()).setCustomModelData(any());
                verify(preview.getItemMeta()).lore(argThat(l -> l.contains(Component.text("Description"))));
                var other = mock(Player.class); when(other.getUniqueId()).thenReturn(UUID.randomUUID());
                var invalidOwner = click(view, other, 53, ClickType.LEFT); gui.click(invalidOwner); verify(invalidOwner).setCancelled(true);
                var alreadyCancelled = click(view, player, 53, ClickType.LEFT); when(alreadyCancelled.isCancelled()).thenReturn(true); gui.click(alreadyCancelled);
                gui.click(click(view, player, 53, ClickType.SHIFT_LEFT));
                gui.click(click(view, player, 19, ClickType.LEFT));
                when(player.hasPermission("chrdkcustomitems.use")).thenReturn(false); gui.click(click(view, player, 53, ClickType.LEFT));
                when(player.hasPermission("chrdkcustomitems.use")).thenReturn(true); assertNull(scheduled.get());
                var drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(view); gui.drag(drag); verify(drag).setCancelled(true);
                gui.click(click(view, player, 53, ClickType.LEFT)); assertNotNull(scheduled.get());
                var foreignView = mock(InventoryView.class); var foreignInventory = mock(Inventory.class);
                when(foreignView.getTopInventory()).thenReturn(foreignInventory); when(player.getOpenInventory()).thenReturn(foreignView);
                scheduled.getAndSet(null).run(); assertEquals(preview, slots.get(19));
                when(player.getOpenInventory()).thenReturn(view); gui.click(click(view, player, 53, ClickType.RIGHT)); scheduled.getAndSet(null).run();
                check(slots.get(45), CatalogMenuVisuals.Control.PREV.model(false), enabled); assertFalse(slots.containsKey(53));
                var builtin = slots.get(20); verify(builtin.getItemMeta()).setCustomModelData(810003);
                verify(builtin.getItemMeta(), never()).setItemModel(any());
                gui.click(click(view, player, 10, ClickType.LEFT)); scheduled.getAndSet(null).run();
                check(slots.get(10), CatalogMenuVisuals.Control.WEAPON.model(true), enabled);
                check(slots.get(9), CatalogMenuVisuals.Control.ALL.model(false), enabled);
                gui.click(click(view, player, 49, ClickType.LEFT)); scheduled.getAndSet(null).run(); verify(player).closeInventory();
                verify(player, never()).getInventory();
                for (var item : items.constructed()) verify(item.getItemMeta(), never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item, org.bukkit.NamespacedKey key, boolean enabled) {
        assertNotNull(item); if (enabled) verify(item.getItemMeta()).setItemModel(key);
        else verify(item.getItemMeta(), never()).setItemModel(any());
    }
    private static InventoryClickEvent click(InventoryView view, Player player, int slot, ClickType type) {
        var e = mock(InventoryClickEvent.class); when(e.getView()).thenReturn(view); when(e.getWhoClicked()).thenReturn(player);
        when(e.getRawSlot()).thenReturn(slot); when(e.getClick()).thenReturn(type); return e;
    }
}
