package ru.cherdakmd.chrdkclaims;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import org.bukkit.event.inventory.InventoryDragEvent;
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

class ClaimMenuGuiVisualsTest {
    @Test void virtualStatesPagesAndTeleportRequestsPreserveBehavior() {
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(ClaimsPlugin.class); var yaml = new YamlConfiguration(); yaml.set("textures.menu", enabled);
            when(plugin.getConfig()).thenReturn(yaml); when(plugin.config()).thenReturn(ClaimsConfig.parse(yaml, Logger.getAnonymousLogger()));
            when(plugin.messages()).thenReturn(Messages.from(getClass().getResourceAsStream("/messages.yml"), null, Logger.getAnonymousLogger()));
            var service = mock(ClaimsService.class); var store = mock(ClaimStore.class); var teleports = mock(ClaimTeleportService.class);
            when(plugin.service()).thenReturn(service); when(plugin.store()).thenReturn(store); when(plugin.teleports()).thenReturn(teleports);
            var player = mock(Player.class); when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID()); when(player.isOnline()).thenReturn(true); when(player.getName()).thenReturn("Player"); when(player.hasPermission("chrdkclaims.menu")).thenReturn(true);
            when(service.isAllowed(player)).thenReturn(true); when(service.formatDate(anyLong())).thenReturn("date");
            var world = mock(World.class); when(world.getName()).thenReturn("world"); when(player.getWorld()).thenReturn(world);
            var location = mock(Location.class); when(player.getLocation()).thenReturn(location);
            var claims = new ArrayList<Claim>();
            for (int i=0; i<29; i++) claims.add(new Claim("world", i*2, 0, "Player", 1000));
            var nether = new Claim("nether", 0, 0, "Player", 1000); claims.add(nether);
            var sale = new Claim("world", 1, 3, "Other", 1000); sale.salePrice = 12; sale.members.add("Player");
            var access = new Claim("world", 1, 6, "Other", 1000); access.members.add("Player");
            when(service.ownedSorted("Player")).thenReturn(List.copyOf(claims));
            var all = new ArrayList<>(claims); all.add(sale); all.add(access); when(store.all()).thenReturn(all);
            when(store.claimAt(anyString(), anyInt(), anyInt())).thenAnswer(c -> all.stream().filter(cl -> cl.world.equals(c.getArgument(0)) && cl.x == (int)c.getArgument(1) && cl.z == (int)c.getArgument(2)).findFirst().orElse(null));
            when(store.territory(any(Claim.class))).thenAnswer(c -> List.of((Claim) c.getArgument(0)));
            var server = mock(Server.class); var scheduler = mock(BukkitScheduler.class); when(plugin.getServer()).thenReturn(server); when(server.getScheduler()).thenReturn(scheduler);
            var scheduled = new AtomicReference<Runnable>(); doAnswer(c -> { scheduled.set(c.getArgument(1)); return mock(BukkitTask.class); }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
            var slots = new HashMap<Integer, ItemStack>(); var holder = new AtomicReference<InventoryHolder>();
            var inventory = mock(Inventory.class); when(inventory.getHolder()).thenAnswer(c -> holder.get());
            doAnswer(c -> { slots.put(c.getArgument(0), c.getArgument(1)); return null; }).when(inventory).setItem(anyInt(), any(ItemStack.class));
            var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inventory); when(player.getOpenInventory()).thenReturn(view);
            var gui = new ClaimMenuGui(plugin);
            try (var bukkit = mockStatic(Bukkit.class); var items = mockConstruction(ItemStack.class, (item, context) -> when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(Component.class))).thenAnswer(c -> { holder.set(c.getArgument(0)); return inventory; });
                gui.open(player, ClaimMenuModel.Tab.MINE, 1);
                check(slots.get(4), ClaimMenuVisuals.Icon.INFO.model(), enabled);
                check(slots.get(10), ClaimMenuVisuals.Icon.ENTRY_MINE.model(), enabled);
                check(slots.get(8), ClaimMenuVisuals.Icon.NEXT.model(), enabled);
                check(slots.get(45), ClaimMenuVisuals.tab(ClaimMenuModel.Tab.MINE, true), enabled);
                check(slots.get(46), ClaimMenuVisuals.tab(ClaimMenuModel.Tab.FOR_SALE, false), enabled);
                check(slots.get(47), ClaimMenuVisuals.tab(ClaimMenuModel.Tab.ACCESSIBLE, false), enabled);
                verify(slots.get(45).getItemMeta()).setEnchantmentGlintOverride(true);
                var drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(view); gui.onInventoryDrag(drag); verify(drag).setCancelled(true);
                var right = click(view, inventory, player, 10, false, true); gui.onInventoryClick(right); verify(right).setCancelled(true);
                assertNull(scheduled.get()); verify(teleports, never()).request(any(), any());
                gui.onInventoryClick(click(view, inventory, player, 10, true, false)); assertNotNull(scheduled.get());
                verify(teleports, never()).request(any(), any()); scheduled.getAndSet(null).run(); verify(teleports).request(player, claims.getFirst());
                gui.onInventoryClick(click(view, inventory, player, 8, true, false)); scheduled.getAndSet(null).run();
                check(slots.get(0), ClaimMenuVisuals.Icon.PREV.model(), enabled); check(slots.get(11), ClaimMenuVisuals.Icon.ENTRY_OTHER_WORLD.model(), enabled);
                gui.onInventoryClick(click(view, inventory, player, 46, true, false)); scheduled.getAndSet(null).run();
                check(slots.get(46), ClaimMenuVisuals.tab(ClaimMenuModel.Tab.FOR_SALE, true), enabled);
                check(slots.get(10), ClaimMenuVisuals.Icon.ENTRY_SALE.model(), enabled);
                gui.onInventoryClick(click(view, inventory, player, 47, true, false)); scheduled.getAndSet(null).run();
                check(slots.get(47), ClaimMenuVisuals.tab(ClaimMenuModel.Tab.ACCESSIBLE, true), enabled);
                check(slots.get(11), ClaimMenuVisuals.Icon.ENTRY_ACCESS.model(), enabled);
                when(service.isAllowed(player)).thenReturn(false); gui.onInventoryClick(click(view, inventory, player, 10, true, false));
                assertNull(scheduled.get()); verify(player, atLeastOnce()).closeInventory();
                when(service.ownedSorted("Player")).thenReturn(List.of()); when(store.all()).thenReturn(List.of());
                gui.open(player, ClaimMenuModel.Tab.MINE, 1); check(slots.get(22), ClaimMenuVisuals.Icon.EMPTY_MINE.model(), enabled);
                gui.open(player, ClaimMenuModel.Tab.FOR_SALE, 1); check(slots.get(22), ClaimMenuVisuals.Icon.EMPTY_SALE.model(), enabled);
                gui.open(player, ClaimMenuModel.Tab.ACCESSIBLE, 1); check(slots.get(22), ClaimMenuVisuals.Icon.EMPTY_MINE.model(), enabled);
                verify(player, never()).getInventory();
                for (var item : items.constructed()) verify(item.getItemMeta(), never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item, org.bukkit.NamespacedKey key, boolean enabled) {
        assertNotNull(item); if (enabled) verify(item.getItemMeta()).setItemModel(key); else verify(item.getItemMeta(), never()).setItemModel(any());
    }
    private static InventoryClickEvent click(InventoryView view, Inventory inventory, Player player, int slot, boolean left, boolean right) {
        var e = mock(InventoryClickEvent.class); when(e.getView()).thenReturn(view); when(e.getClickedInventory()).thenReturn(inventory);
        when(e.getWhoClicked()).thenReturn(player); when(e.getSlot()).thenReturn(slot); when(e.isLeftClick()).thenReturn(left); when(e.isRightClick()).thenReturn(right); return e;
    }
}
