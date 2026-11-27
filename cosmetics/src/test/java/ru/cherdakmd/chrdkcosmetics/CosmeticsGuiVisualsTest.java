package ru.cherdakmd.chrdkcosmetics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CosmeticsGuiVisualsTest {
    @Test void stockStatesCustomFallbackAndDeferredSelectionPreserveGuards() throws Exception {
        var stock = CosmeticsMenuVisualsTest.stock();
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(CosmeticsPlugin.class); var configFile = new YamlConfiguration(); configFile.set("textures.menu", enabled);
            when(plugin.getConfig()).thenReturn(configFile);
            when(plugin.messages()).thenReturn(Messages.from(CosmeticsGuiVisualsTest.class.getResourceAsStream("/messages.yml"), null, Logger.getAnonymousLogger()));
            var defs = new ArrayList<>(stock);
            defs.add(new CosmeticsDefs.TrailDef("custom", "<aqua>Custom</aqua>", "flame", 1, 0, 0, "", 0));
            var snapshot = new CosmeticsPlugin.CosmeticsConfig(true, true, java.util.List.of(), defs, 4, CosmeticsDefs.JoinEffect.disabled(), 4);
            when(plugin.config()).thenReturn(snapshot);
            var player = mock(Player.class); var owner = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(owner); when(player.getName()).thenReturn("Player");
            when(plugin.eligible(player)).thenReturn(true);
            var store = mock(SelectionStore.class); when(plugin.store()).thenReturn(store); when(store.selectionOf(owner)).thenReturn("hearts");
            when(plugin.unlocked(eq("Player"), any())).thenReturn(true);
            when(plugin.unlocked("Player", stock.get(1))).thenReturn(false);
            when(plugin.unlocked("Player", stock.get(3))).thenReturn(false);
            when(plugin.resolveParticle(anyString())).thenReturn(Particle.FLAME);
            when(plugin.resolveParticle("witch")).thenReturn(null);
            var inventory = mock(Inventory.class); var holder = new AtomicReference<InventoryHolder>(); when(inventory.getHolder()).thenAnswer(c -> holder.get());
            Map<Integer, ItemStack> slots = new HashMap<>();
            doAnswer(c -> { slots.put(c.getArgument(0), c.getArgument(1)); return null; }).when(inventory).setItem(anyInt(), nullable(ItemStack.class));
            when(inventory.getItem(anyInt())).thenAnswer(c -> slots.get(c.getArgument(0)));
            var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inventory); when(player.getOpenInventory()).thenReturn(view);
            var scheduler = mock(BukkitScheduler.class); var task = mock(BukkitTask.class); var scheduled = new AtomicReference<Runnable>();
            doAnswer(c -> { scheduled.set(c.getArgument(1)); return task; }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
            var manager = mock(PluginManager.class); var gui = new CosmeticsGui(plugin);
            try (var bukkit = mockStatic(Bukkit.class); var items = mockConstruction(ItemStack.class, (item, context) -> {
                var meta = mock(ItemMeta.class); when(item.getItemMeta()).thenReturn(meta);
            })) {
                bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(Component.class)))
                        .thenAnswer(c -> { slots.clear(); holder.set(c.getArgument(0)); return inventory; });
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler); bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
                gui.open(player);
                checkModel(slots.get(4), CosmeticsMenuVisuals.Control.SUMMARY.model(), enabled);
                checkModel(slots.get(10), CosmeticsMenuVisuals.Control.TRAILS.model(), enabled);
                checkModel(slots.get(49), CosmeticsMenuVisuals.Control.CLOSE.model(), enabled);
                gui.trails(player);
                checkModel(slots.get(0), CosmeticsMenuVisuals.trail(stock.get(0), true, false, true), enabled);
                checkModel(slots.get(1), CosmeticsMenuVisuals.trail(stock.get(1), false, true, true), enabled);
                checkModel(slots.get(3), CosmeticsMenuVisuals.trail(stock.get(3), false, false, true), enabled);
                checkModel(slots.get(4), CosmeticsMenuVisuals.trail(stock.get(4), true, false, false), enabled);
                verify(slots.get(7).getItemMeta(), never()).setItemModel(any()); // owner-custom definition
                checkModel(slots.get(46), CosmeticsMenuVisuals.Control.BACK.model(), enabled);
                var stranger = mock(Player.class); when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
                var foreign = click(view, stranger, 2); gui.click(foreign); verify(foreign).setCancelled(true); assertNull(scheduled.get());
                var drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(view); gui.drag(drag); verify(drag).setCancelled(true);
                var cancelled = click(view, player, 2); when(cancelled.isCancelled()).thenReturn(true); gui.click(cancelled);
                when(plugin.eligible(player)).thenReturn(false); gui.click(click(view, player, 2));
                when(plugin.eligible(player)).thenReturn(true); gui.click(click(view, player, 54));
                assertNull(scheduled.get());
                var choose = click(view, player, 2); gui.click(choose); verify(choose).setCancelled(true);
                assertNotNull(scheduled.get()); verify(plugin, never()).select(any(), any());
                gui.click(click(view, player, 0)); // another pending click must not replace the first
                verify(scheduler, times(1)).runTask(eq(plugin), any(Runnable.class));
                var replaced = new CosmeticsPlugin.CosmeticsConfig(true, true, java.util.List.of(), defs, 5, CosmeticsDefs.JoinEffect.disabled(), 4);
                when(plugin.config()).thenReturn(replaced); scheduled.getAndSet(null).run(); verify(plugin, never()).select(any(), any());
                when(plugin.config()).thenReturn(snapshot); gui.click(click(view, player, 2));
                var otherInventory = mock(Inventory.class); var otherView = mock(InventoryView.class);
                when(otherView.getTopInventory()).thenReturn(otherInventory); when(player.getOpenInventory()).thenReturn(otherView);
                scheduled.getAndSet(null).run(); verify(plugin, never()).select(any(), any());
                when(player.getOpenInventory()).thenReturn(view); gui.click(click(view, player, 2));
                when(store.selectionOf(owner)).thenReturn("notes"); scheduled.getAndSet(null).run();
                verify(plugin).select(player, "notes");
                checkModel(slots.get(2), CosmeticsMenuVisuals.trail(stock.get(2), true, true, true), enabled);
                verify(slots.get(2).getItemMeta()).setEnchantmentGlintOverride(true);
                verify(player, never()).getInventory();
                for (var item : items.constructed()) verify(item.getItemMeta(), never()).getPersistentDataContainer();
            }
        }
    }
    private static void checkModel(ItemStack item, org.bukkit.NamespacedKey model, boolean enabled) {
        assertNotNull(item); assertNotNull(model);
        if (enabled) verify(item.getItemMeta()).setItemModel(model); else verify(item.getItemMeta(), never()).setItemModel(any());
    }
    private static InventoryClickEvent click(InventoryView view, Player player, int slot) {
        var event = mock(InventoryClickEvent.class); when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player); when(event.getRawSlot()).thenReturn(slot); return event;
    }
}
