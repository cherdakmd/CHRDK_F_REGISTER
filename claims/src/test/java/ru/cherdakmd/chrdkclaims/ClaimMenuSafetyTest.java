package ru.cherdakmd.chrdkclaims;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClaimMenuSafetyTest {
    @Test void ownerAndDeferredActionsRecheckCurrentViewAndAccess() {
        var plugin = mock(ClaimsPlugin.class); var yaml = new YamlConfiguration();
        var activeConfig = ClaimsConfig.parse(yaml, Logger.getAnonymousLogger());
        when(plugin.getConfig()).thenReturn(yaml); when(plugin.config()).thenReturn(activeConfig);
        when(plugin.messages()).thenReturn(Messages.from(getClass().getResourceAsStream("/messages.yml"), null, Logger.getAnonymousLogger()));
        var service = mock(ClaimsService.class); var store = mock(ClaimStore.class); var teleports = mock(ClaimTeleportService.class);
        when(plugin.service()).thenReturn(service); when(plugin.store()).thenReturn(store); when(plugin.teleports()).thenReturn(teleports);
        var player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getName()).thenReturn("Player");
        when(player.isOnline()).thenReturn(true); when(player.hasPermission("chrdkclaims.menu")).thenReturn(true); when(service.isAllowed(player)).thenReturn(true);
        var world = mock(World.class); when(world.getName()).thenReturn("world"); when(player.getWorld()).thenReturn(world); when(player.getLocation()).thenReturn(mock(Location.class));
        var claim = new Claim("world", 0, 0, "Player", 0); var currentClaim = new AtomicReference<>(claim);
        when(service.ownedSorted("Player")).thenReturn(List.of(claim)); when(store.all()).thenReturn(List.of(claim));
        when(store.claimAt("world", 0, 0)).thenAnswer(c -> currentClaim.get()); when(store.territory(any(Claim.class))).thenAnswer(c -> List.of((Claim)c.getArgument(0)));
        when(service.formatDate(anyLong())).thenReturn("date");
        var holder = new AtomicReference<InventoryHolder>(); var inv = mock(Inventory.class); when(inv.getHolder()).thenAnswer(c -> holder.get());
        var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inv);
        var otherInv = mock(Inventory.class); var otherView = mock(InventoryView.class); when(otherView.getTopInventory()).thenReturn(otherInv);
        var currentView = new AtomicReference<>(view); when(player.getOpenInventory()).thenAnswer(c -> currentView.get());
        doAnswer(c -> { currentView.set(otherView); return null; }).when(player).closeInventory();
        var server = mock(Server.class); var scheduler = mock(BukkitScheduler.class); when(plugin.getServer()).thenReturn(server); when(server.getScheduler()).thenReturn(scheduler);
        var tasks = new ArrayList<Runnable>(); doAnswer(c -> { tasks.add(c.getArgument(1)); return mock(BukkitTask.class); }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        var gui = new ClaimMenuGui(plugin);
        try (var bukkit = mockStatic(Bukkit.class); var items = mockConstruction(ItemStack.class, (item, context) -> when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(Component.class))).thenAnswer(c -> { holder.set(c.getArgument(0)); return inv; });
            gui.open(player, ClaimMenuModel.Tab.MINE, 1);
            var stranger = mock(Player.class); when(stranger.isOnline()).thenReturn(true); when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
            var foreign = click(view, inv, stranger, 48); gui.onInventoryClick(foreign); verify(foreign).setCancelled(true); assertTrue(tasks.isEmpty());
            var cancelled = click(view, inv, player, 48); when(cancelled.isCancelled()).thenReturn(true); gui.onInventoryClick(cancelled); assertTrue(tasks.isEmpty());
            var bottom = click(view, otherInv, player, 48); gui.onInventoryClick(bottom); assertTrue(tasks.isEmpty());
            gui.onInventoryClick(click(view, inv, player, 48)); currentView.set(otherView); run(tasks);
            verify(player, never()).performCommand(anyString()); verify(player, never()).closeInventory(); currentView.set(view);
            gui.onInventoryClick(click(view, inv, player, 48)); when(player.hasPermission("chrdkclaims.menu")).thenReturn(false); run(tasks);
            verify(player, never()).performCommand(anyString()); when(player.hasPermission("chrdkclaims.menu")).thenReturn(true);
            gui.onInventoryClick(click(view, inv, player, 48)); when(service.isAllowed(player)).thenReturn(false); run(tasks);
            verify(player, never()).performCommand(anyString()); when(service.isAllowed(player)).thenReturn(true);
            gui.onInventoryClick(click(view, inv, player, 48)); yaml.set("menu.enabled", false); when(plugin.config()).thenReturn(ClaimsConfig.parse(yaml, Logger.getAnonymousLogger())); run(tasks);
            verify(player, never()).performCommand(anyString()); yaml.set("menu.enabled", true); when(plugin.config()).thenReturn(activeConfig);
            gui.onInventoryClick(click(view, inv, player, 48)); when(player.isOnline()).thenReturn(false); run(tasks);
            verify(player, never()).performCommand(anyString()); when(player.isOnline()).thenReturn(true);
            // A redraw invalidates another action queued against the previous revision.
            gui.onInventoryClick(click(view, inv, player, 46)); gui.onInventoryClick(click(view, inv, player, 48));
            assertEquals(2, tasks.size()); run(tasks); run(tasks); verify(player, never()).performCommand(anyString());
            gui.open(player, ClaimMenuModel.Tab.MINE, 1); currentView.set(view);
            // The same coordinate can now refer to a different claim; no paid request is sent.
            gui.onInventoryClick(click(view, inv, player, 10)); currentClaim.set(new Claim("world", 0, 0, "Other", 0)); run(tasks);
            verify(teleports, never()).request(any(), any()); currentClaim.set(claim); currentView.set(view);
            // Ownership changed while the same Claim object remained in the store.
            gui.onInventoryClick(click(view, inv, player, 10)); claim.owner = "Other"; run(tasks);
            verify(teleports, never()).request(any(), any()); claim.owner = "Player"; currentView.set(view);
            gui.open(player, ClaimMenuModel.Tab.MINE, 1); gui.onInventoryClick(click(view, inv, player, 10)); run(tasks);
            verify(teleports).request(player, claim); verify(player, never()).performCommand(anyString());
        }
    }
    private static void run(List<Runnable> tasks) { assertFalse(tasks.isEmpty()); tasks.removeFirst().run(); }
    private static InventoryClickEvent click(InventoryView view, Inventory clicked, Player player, int slot) {
        var event = mock(InventoryClickEvent.class); when(event.getView()).thenReturn(view); when(event.getClickedInventory()).thenReturn(clicked);
        when(event.getWhoClicked()).thenReturn(player); when(event.getSlot()).thenReturn(slot); when(event.isLeftClick()).thenReturn(true); return event;
    }
}
