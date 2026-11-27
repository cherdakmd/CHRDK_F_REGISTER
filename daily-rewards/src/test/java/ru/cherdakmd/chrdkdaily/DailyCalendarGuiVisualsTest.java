package ru.cherdakmd.chrdkdaily;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Server;
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
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DailyCalendarGuiVisualsTest {
    @Test void calendarStatesAndClaimRoutingStayUnchanged() {
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(DailyRewardsPlugin.class); var config = mock(DailyConfig.class);
            var file = new YamlConfiguration(); file.set("textures.menu", enabled); when(plugin.getConfig()).thenReturn(file);
            when(plugin.messages()).thenReturn(Messages.from(DailyCalendarGuiVisualsTest.class.getResourceAsStream("/messages.yml"), null, Logger.getAnonymousLogger()));
            when(plugin.config()).thenReturn(config);
            var rewards = List.of(new DailyConfig.DayReward(1, List.of(), List.of(), 0),
                    new DailyConfig.DayReward(2, List.of(), List.of(), 0), new DailyConfig.DayReward(3, List.of(), List.of(), 0));
            when(config.rewards()).thenReturn(rewards);
            var store = mock(DailyStore.class); when(plugin.store()).thenReturn(store);
            var progress = new DailyStore.Progress(); progress.streak = 1; progress.lastClaimDay = -1;
            var player = mock(Player.class); var owner = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(owner); when(player.getName()).thenReturn("Player");
            when(store.progressOf(owner)).thenReturn(progress);
            var api = mock(ChrdkRegisterApi.class); when(plugin.coreApi()).thenReturn(api);
            var service = mock(DailyService.class); when(plugin.service()).thenReturn(service);
            var scheduler = mock(BukkitScheduler.class); var server = mock(Server.class);
            when(server.getScheduler()).thenReturn(scheduler); when(plugin.getServer()).thenReturn(server);
            var scheduled = new AtomicReference<Runnable>();
            doAnswer(c -> { scheduled.set(c.getArgument(1)); return null; }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
            var inventory = mock(Inventory.class); var holder = new AtomicReference<InventoryHolder>(); when(inventory.getHolder()).thenAnswer(c -> holder.get());
            Map<Integer, ItemStack> slots = new HashMap<>();
            doAnswer(c -> { slots.put(c.getArgument(0), c.getArgument(1)); return null; }).when(inventory).setItem(anyInt(), nullable(ItemStack.class));
            var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inventory); when(player.getOpenInventory()).thenReturn(view);
            var gui = new DailyCalendarGui(plugin);
            try (var bukkit = mockStatic(Bukkit.class); var items = mockConstruction(ItemStack.class, (item, context) -> {
                var meta = mock(ItemMeta.class); when(item.getItemMeta()).thenReturn(meta);
            })) {
                bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(Component.class)))
                        .thenAnswer(c -> { slots.clear(); holder.set(c.getArgument(0)); return inventory; });
                gui.open(player); assertEquals(54, slots.size());
                checkModel(slots.get(4), DailyMenuVisuals.Icon.HEADER, enabled);
                checkModel(slots.get(9), DailyMenuVisuals.Icon.CLAIMED, enabled);
                checkModel(slots.get(10), DailyMenuVisuals.Icon.TODAY, enabled);
                checkModel(slots.get(11), DailyMenuVisuals.Icon.LOCKED, enabled);
                verify(slots.get(0).getItemMeta(), never()).setItemModel(any()); // existing filler
                progress.streak = 2; progress.lastClaimDay = Long.MAX_VALUE; gui.open(player);
                checkModel(slots.get(9), DailyMenuVisuals.Icon.CLAIMED, enabled);
                checkModel(slots.get(10), DailyMenuVisuals.Icon.CLAIMED_TODAY, enabled);
                checkModel(slots.get(11), DailyMenuVisuals.Icon.LOCKED, enabled);
                var unauthorized = click(inventory, view, player, inventory, ClickType.LEFT);
                gui.onInventoryClick(unauthorized); verify(unauthorized).setCancelled(true);
                verifyNoInteractions(scheduler); verify(service, never()).claim(any());
                var lower = mock(Inventory.class); var shift = click(inventory, view, player, lower, ClickType.SHIFT_LEFT);
                gui.onInventoryClick(shift); verify(shift).setCancelled(true);
                var drag = mock(InventoryDragEvent.class); when(drag.getInventory()).thenReturn(inventory);
                gui.onInventoryDrag(drag); verify(drag).setCancelled(true);
                when(api.isAuthenticated(player)).thenReturn(true); when(service.canClaim(player)).thenReturn(true);
                var topClick = click(inventory, view, player, inventory, ClickType.LEFT);
                when(topClick.getRawSlot()).thenReturn(11); // existing behavior: even a future-day cell routes to today's claim
                gui.onInventoryClick(topClick); verify(topClick).setCancelled(true);
                verify(service, never()).claim(any()); assertNotNull(scheduled.get());
                scheduled.getAndSet(null).run(); verify(service).claim(player); verify(player).closeInventory();
                when(service.canClaim(player)).thenReturn(false); gui.onInventoryClick(topClick);
                assertNotNull(scheduled.get()); scheduled.get().run(); verify(service, times(1)).claim(player);
                verify(player, never()).getInventory();
                for (var item : items.constructed()) verify(item.getItemMeta(), never()).getPersistentDataContainer();
            }
        }
    }
    private static void checkModel(ItemStack item, DailyMenuVisuals.Icon icon, boolean enabled) {
        assertNotNull(item); if (enabled) verify(item.getItemMeta()).setItemModel(icon.model());
        else verify(item.getItemMeta(), never()).setItemModel(any());
    }
    private static InventoryClickEvent click(Inventory top, InventoryView view, Player player, Inventory clicked, ClickType type) {
        var event = mock(InventoryClickEvent.class); when(event.getInventory()).thenReturn(top);
        when(event.getView()).thenReturn(view); when(event.getWhoClicked()).thenReturn(player);
        when(event.getClickedInventory()).thenReturn(clicked); when(event.getClick()).thenReturn(type); return event;
    }
}
