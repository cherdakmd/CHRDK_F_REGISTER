package ru.cherdakmd.chrdkgenerator;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeneratorGuiSafetyTest {
    @Test void rejectedClicksCannotInitiateUpgradesOrTakeAndLeftClickStillRoutes() throws Exception {
        var plugin = mock(GeneratorPlugin.class); var upgrades = mock(GeneratorUpgrades.class); when(plugin.upgrades()).thenReturn(upgrades);
        var take = mock(GeneratorTake.class); when(plugin.take()).thenReturn(take);
        var api = mock(ChrdkRegisterApi.class); when(plugin.coreApi()).thenReturn(api);
        var store = mock(GeneratorStore.class); when(plugin.store()).thenReturn(store);
        var player = mock(Player.class); UUID id = UUID.randomUUID(), core = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id); when(player.isOnline()).thenReturn(true); when(player.hasPermission("chrdkgenerator.use")).thenReturn(true);
        when(api.isAuthenticated(player)).thenReturn(true);
        var entry = new GeneratorStore.Entry(id, 0, core, "world", 0, 64, 0, 1, Map.of(), 0, 0, 0, 0, 0, 0);
        when(store.entry(id, 0)).thenReturn(entry);
        var inventory = mock(Inventory.class); var holder = holder(id, core, inventory); when(inventory.getHolder()).thenReturn(holder); when(inventory.getSize()).thenReturn(54);
        var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inventory); when(player.getOpenInventory()).thenReturn(view);
        var gui = new GeneratorGui(plugin);
        for (var type : new ClickType[]{ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT, ClickType.MIDDLE,
                ClickType.DOUBLE_CLICK, ClickType.NUMBER_KEY, ClickType.DROP, ClickType.SWAP_OFFHAND, ClickType.UNKNOWN}) {
            for (int slot : new int[]{12, 20, 21, 22, 23, 24, 49}) {
                var event = click(view, player, slot, type); gui.onClick(event); verify(event).setCancelled(true);
            }
        }
        var cancelled = click(view, player, 20, ClickType.LEFT); when(cancelled.isCancelled()).thenReturn(true); gui.onClick(cancelled); verify(cancelled).setCancelled(true);
        var foreign = mock(Player.class); when(foreign.isOnline()).thenReturn(true); when(foreign.getUniqueId()).thenReturn(UUID.randomUUID()); gui.onClick(click(view, foreign, 20, ClickType.LEFT));
        var otherInventory = mock(Inventory.class); var otherView = mock(InventoryView.class); when(otherView.getTopInventory()).thenReturn(otherInventory);
        when(player.getOpenInventory()).thenReturn(otherView); gui.onClick(click(view, player, 20, ClickType.LEFT)); when(player.getOpenInventory()).thenReturn(view);
        when(player.hasPermission("chrdkgenerator.use")).thenReturn(false); gui.onClick(click(view, player, 20, ClickType.LEFT)); when(player.hasPermission("chrdkgenerator.use")).thenReturn(true);
        when(api.isAuthenticated(player)).thenReturn(false); gui.onClick(click(view, player, 20, ClickType.LEFT)); when(api.isAuthenticated(player)).thenReturn(true);
        when(plugin.coreApi()).thenReturn(null); gui.onClick(click(view, player, 20, ClickType.LEFT)); when(plugin.coreApi()).thenReturn(api);
        when(store.entry(id, 0)).thenReturn(new GeneratorStore.Entry(id, 0, UUID.randomUUID(), "world", 0, 64, 0, 1, Map.of(), 0, 0, 0, 0, 0, 0));
        gui.onClick(click(view, player, 20, ClickType.LEFT)); when(store.entry(id, 0)).thenReturn(entry);
        verifyNoInteractions(upgrades); verifyNoInteractions(take);
        var drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(view); gui.onDrag(drag); verify(drag).setCancelled(true);
        // Avoid rendering a whole server inventory: stop the mock service at the routing boundary.
        doThrow(new IllegalStateException("route")).when(upgrades).upgradeTier(player, 0);
        assertThrows(IllegalStateException.class, () -> gui.onClick(click(view, player, 20, ClickType.LEFT))); verify(upgrades).upgradeTier(player, 0);
        doThrow(new IllegalStateException("route")).when(take).request(player, 0);
        assertThrows(IllegalStateException.class, () -> gui.onClick(click(view, player, 49, ClickType.LEFT))); verify(take).request(player, 0);
        gui.onClick(click(view, player, 8, ClickType.RIGHT)); verify(player).closeInventory();
    }

    @Test void deferredBunkerRechecksOwnerViewPermissionAuthenticationAndCore() throws Exception {
        var plugin = mock(GeneratorPlugin.class); var store = mock(GeneratorStore.class); when(plugin.store()).thenReturn(store);
        var container = mock(GeneratorContainerGui.class); when(plugin.containerGui()).thenReturn(container);
        var api = mock(ChrdkRegisterApi.class); when(plugin.coreApi()).thenReturn(api);
        var player = mock(Player.class); UUID id = UUID.randomUUID(), core = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true); when(player.hasPermission("chrdkgenerator.use")).thenReturn(true); when(api.isAuthenticated(player)).thenReturn(true);
        var entry = new GeneratorStore.Entry(id, 0, core, "world", 0, 64, 0, 1, Map.of(), 0, 0, 0, 0, 0, 0); when(store.entry(id, 0)).thenReturn(entry);
        var inventory = mock(Inventory.class); var holder = holder(id, core, inventory); when(inventory.getHolder()).thenReturn(holder); when(inventory.getSize()).thenReturn(54);
        var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inventory); when(player.getOpenInventory()).thenReturn(view);
        var scheduler = mock(BukkitScheduler.class); var scheduled = new AtomicReference<Runnable>();
        doAnswer(c -> { scheduled.set(c.getArgument(1)); return mock(BukkitTask.class); }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler); var gui = new GeneratorGui(plugin);
            gui.onClick(click(view, player, 12, ClickType.LEFT));
            var other = mock(InventoryView.class); when(other.getTopInventory()).thenReturn(mock(Inventory.class)); when(player.getOpenInventory()).thenReturn(other);
            scheduled.getAndSet(null).run(); verifyNoInteractions(container); when(player.getOpenInventory()).thenReturn(view);
            gui.onClick(click(view, player, 12, ClickType.LEFT)); when(player.hasPermission("chrdkgenerator.use")).thenReturn(false);
            scheduled.getAndSet(null).run(); verifyNoInteractions(container); when(player.hasPermission("chrdkgenerator.use")).thenReturn(true);
            gui.onClick(click(view, player, 12, ClickType.LEFT)); when(api.isAuthenticated(player)).thenReturn(false);
            scheduled.getAndSet(null).run(); verifyNoInteractions(container); when(api.isAuthenticated(player)).thenReturn(true);
            gui.onClick(click(view, player, 12, ClickType.LEFT)); when(store.entry(id, 0)).thenReturn(new GeneratorStore.Entry(id, 0, UUID.randomUUID(), "world", 0, 64, 0, 1, Map.of(), 0, 0, 0, 0, 0, 0));
            scheduled.getAndSet(null).run(); verifyNoInteractions(container); when(store.entry(id, 0)).thenReturn(entry);
            gui.onClick(click(view, player, 12, ClickType.LEFT)); scheduled.getAndSet(null).run(); verify(container).open(player, 0);
        }
    }
    private static InventoryHolder holder(UUID owner, UUID core, Inventory inventory) throws Exception {
        Class<?> type = Class.forName("ru.cherdakmd.chrdkgenerator.GeneratorGui$Holder");
        Constructor<?> c = type.getDeclaredConstructor(UUID.class, int.class, UUID.class); c.setAccessible(true);
        Object holder = c.newInstance(owner, 0, core); var field = type.getDeclaredField("inventory"); field.setAccessible(true); field.set(holder, inventory);
        return (InventoryHolder) holder;
    }
    private static InventoryClickEvent click(InventoryView view, Player player, int slot, ClickType type) {
        var event = mock(InventoryClickEvent.class); when(event.getView()).thenReturn(view); when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(slot); when(event.getClick()).thenReturn(type); return event;
    }
}
