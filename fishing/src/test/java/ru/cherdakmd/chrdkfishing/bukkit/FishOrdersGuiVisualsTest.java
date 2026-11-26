package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkfishing.FishCatalog;
import ru.cherdakmd.chrdkfishing.FishSaleJournal;
import ru.cherdakmd.chrdkfishing.FishToken;
import ru.cherdakmd.chrdkfishing.FishingModel;
import ru.cherdakmd.chrdkfishing.FishingOrders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FishOrdersGuiVisualsTest {
    @Test void virtualIconsPreserveSelectionAndExplicitConfirmation() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(Plugin.class); var config = new YamlConfiguration(); config.set("textures.orders-menu", enabled);
            when(plugin.getConfig()).thenReturn(config);
            var player = mock(Player.class); UUID owner = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(owner);
            var engine = mock(FishSaleEngine.class); when(engine.available(player)).thenReturn(true);
            when(engine.recover(player)).thenReturn(FishSaleJournal.Result.DONE);
            when(engine.orderClaimed(eq(player), any())).thenAnswer(c -> ((FishingOrders.Order)c.getArgument(1)).slot() == 2);
            var order = FishingOrders.forDay(FishingOrders.utcDay()).get(0);
            int length = FishCatalog.byId(order.speciesId()).lengths().get(order.size().ordinal()).min();
            var token = FishToken.issue(new byte[32], UUID.randomUUID(), new FishingModel.Catch(order.speciesId(), order.size(), length), 70);
            var codec = mock(FishItemCodec.class); var fish = mock(ItemStack.class); var storage = new ItemStack[36]; storage[3] = fish;
            when(codec.read(nullable(ItemStack.class))).thenReturn(Optional.empty()); when(codec.read(fish)).thenReturn(Optional.of(token));
            var inventory = mock(PlayerInventory.class); when(player.getInventory()).thenReturn(inventory); when(inventory.getStorageContents()).thenReturn(storage);
            var quote = mock(FishSalePlanner.Plan.class); when(engine.quoteOrder(player, 3, order)).thenReturn(quote);
            when(engine.confirmOrder(player, quote, order)).thenReturn(FishSaleJournal.Result.DONE);
            var top = mock(Inventory.class); var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(top);
            when(player.getOpenInventory()).thenReturn(view);
            var holder = new AtomicReference<InventoryHolder>(); when(top.getHolder()).thenAnswer(c -> holder.get());
            Map<Integer, ItemStack> shown = new HashMap<>();
            doAnswer(c -> { shown.clear(); return null; }).when(top).clear();
            doAnswer(c -> { shown.put(c.getArgument(0), c.getArgument(1)); return null; }).when(top).setItem(anyInt(), nullable(ItemStack.class));
            var scheduler = mock(BukkitScheduler.class); var scheduled = new AtomicReference<Runnable>();
            doAnswer(c -> { scheduled.set(c.getArgument(1)); return null; }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
            var gui = new FishOrdersGui(plugin, engine, codec, p -> {});
            try (var bukkit = mockStatic(Bukkit.class); var items = mockConstruction(ItemStack.class, (item, context) -> {
                var meta = mock(ItemMeta.class); when(item.getItemMeta()).thenReturn(meta);
            })) {
                bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(Component.class)))
                        .thenAnswer(c -> { holder.set(c.getArgument(0)); return top; });
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                gui.open(player); assertEquals(java.util.Set.of(36, 38, 40, 45, 52, 53), shown.keySet());
                checkModel(shown.get(36), FishOrdersVisuals.Icon.ORDER_AVAILABLE.model(), enabled);
                checkModel(shown.get(40), FishOrdersVisuals.Icon.ORDER_DONE.model(), enabled);
                var stranger = mock(Player.class); when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
                var foreign = click(view, stranger, 36); gui.click(foreign); verify(foreign).setCancelled(true);
                assertNull(shown.get(3)); verify(engine, never()).quoteOrder(any(), anyInt(), any());
                var drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(view); gui.drag(drag); verify(drag).setCancelled(true);
                var select = click(view, player, 36); gui.click(select); verify(select).setCancelled(true);
                checkModel(shown.get(36), FishOrdersVisuals.Icon.ORDER_SELECTED.model(), enabled);
                checkModel(shown.get(3), FishingVisuals.fish(order.speciesId()), enabled); assertNull(shown.get(49));
                var choose = click(view, player, 3); gui.click(choose); verify(engine).quoteOrder(player, 3, order);
                checkModel(shown.get(49), FishOrdersVisuals.Icon.CONFIRM.model(), enabled);
                verify(engine, never()).confirmOrder(any(), any(), any());
                var confirm = click(view, player, 49); gui.click(confirm); assertNotNull(scheduled.get());
                verify(engine, never()).confirmOrder(any(), any(), any());
                scheduled.get().run(); verify(engine).confirmOrder(player, quote, order); verify(player).closeInventory();
                verify(inventory, never()).setItem(anyInt(), any()); verify(inventory, never()).setStorageContents(any()); assertSame(fish, storage[3]);
                for (var item : items.constructed()) verify(item.getItemMeta(), never()).getPersistentDataContainer();
            }
        }
    }
    private static void checkModel(ItemStack item, org.bukkit.NamespacedKey key, boolean enabled) {
        assertNotNull(item); if (enabled) verify(item.getItemMeta()).setItemModel(key);
        else verify(item.getItemMeta(), never()).setItemModel(any());
    }
    private static InventoryClickEvent click(InventoryView view, Player player, int slot) {
        var event = mock(InventoryClickEvent.class); when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player); when(event.getRawSlot()).thenReturn(slot); return event;
    }
}
