package ru.cherdakmd.chrdkfishing.bukkit;

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
import ru.cherdakmd.chrdkfishing.FishSaleJournal;
import ru.cherdakmd.chrdkfishing.FishToken;
import ru.cherdakmd.chrdkfishing.FishingModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FishCookingGuiVisualsTest {
    @Test void visualFlagPreservesSelectionQuoteAndConfirmationGuards() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(Plugin.class); var config = new YamlConfiguration(); config.set("textures.cooking-menu", enabled);
            when(plugin.getConfig()).thenReturn(config);
            var player = mock(Player.class); UUID owner = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(owner); when(player.isOnline()).thenReturn(true);
            var engine = mock(FishSaleEngine.class); when(engine.available(player)).thenReturn(true);
            when(engine.recover(player)).thenReturn(FishSaleJournal.Result.DONE);
            var codec = mock(FishItemCodec.class); var fish = mock(ItemStack.class); var storage = new ItemStack[36]; storage[3] = fish;
            var token = FishToken.issue(new byte[32], UUID.randomUUID(), new FishingModel.Catch("pike", FishingModel.Size.NORMAL, 40), 70);
            when(codec.read(fish)).thenReturn(Optional.of(token));
            var inventory = mock(PlayerInventory.class); when(player.getInventory()).thenReturn(inventory); when(inventory.getStorageContents()).thenReturn(storage);
            var fried = mock(FishCookingPlanner.Plan.class); var soup = mock(FishCookingPlanner.Plan.class);
            when(fried.recipe()).thenReturn(FishCookingPlanner.Recipe.FRIED); when(soup.recipe()).thenReturn(FishCookingPlanner.Recipe.SOUP);
            when(engine.quoteCook(player, 3, FishCookingPlanner.Recipe.FRIED)).thenReturn(fried);
            when(engine.quoteCook(player, 3, FishCookingPlanner.Recipe.SOUP)).thenReturn(soup);
            when(engine.confirmCook(player, soup)).thenReturn(FishSaleJournal.Result.DONE);
            var top = mock(Inventory.class); var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(top);
            when(player.getOpenInventory()).thenReturn(view);
            var holder = new AtomicReference<InventoryHolder>(); when(top.getHolder()).thenAnswer(c -> holder.get());
            var scheduler = mock(BukkitScheduler.class); var scheduled = new AtomicReference<Runnable>();
            doAnswer(c -> { scheduled.set(c.getArgument(1)); return null; }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
            var gui = new FishCookingGui(plugin, engine, codec); gui.setBack(p -> {});
            List<ItemMeta> metadata = new ArrayList<>();
            try (var bukkit = mockStatic(Bukkit.class);
                 var items = mockConstruction(ItemStack.class, (item, context) -> {
                     var meta = mock(ItemMeta.class); metadata.add(meta); when(item.getItemMeta()).thenReturn(meta);
                 })) {
                bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(Component.class)))
                        .thenAnswer(c -> { holder.set(c.getArgument(0)); return top; });
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                gui.open(player); verify(player).openInventory(top); assertEquals(7, metadata.size());
                verify(top).setItem(eq(3), same(items.constructed().get(0)));
                verify(top).setItem(eq(39), same(items.constructed().get(1)));
                verify(top).setItem(eq(40), same(items.constructed().get(2)));
                verify(top).setItem(eq(41), same(items.constructed().get(3)));
                verify(top, never()).setItem(eq(49), any());
                verify(metadata.get(3)).lore(argThat(lines -> lines != null && lines.stream().anyMatch(line ->
                        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line).contains("Нужна миска."))));
                if (enabled) {
                    verify(metadata.get(0)).setItemModel(FishingVisuals.fish("pike"));
                    verify(metadata.get(1)).setItemModel(FishCookingVisuals.recipe(FishCookingPlanner.Recipe.FRIED, true));
                    verify(metadata.get(3)).setItemModel(FishCookingVisuals.recipe(FishCookingPlanner.Recipe.SOUP, false));
                } else for (var meta : metadata) verify(meta, never()).setItemModel(any());
                var stranger = mock(Player.class); when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
                var wrong = click(view, stranger, 3); gui.click(wrong); verify(wrong).setCancelled(true);
                verify(engine, never()).quoteCook(any(), anyInt(), any()); verify(engine, never()).confirmCook(any(), any());
                var drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(view); gui.drag(drag); verify(drag).setCancelled(true);
                var chooseFish = click(view, player, 3); gui.click(chooseFish); verify(chooseFish).setCancelled(true);
                verify(engine).quoteCook(player, 3, FishCookingPlanner.Recipe.FRIED); verify(engine, never()).confirmCook(any(), any());
                assertEquals(15, metadata.size());
                if (enabled) verify(metadata.get(12)).setItemModel(FishCookingVisuals.Icon.CONFIRM.model());
                var chooseSoup = click(view, player, 41); gui.click(chooseSoup); verify(chooseSoup).setCancelled(true);
                assertEquals(22, metadata.size());
                if (enabled) verify(metadata.get(18)).setItemModel(FishCookingVisuals.recipe(FishCookingPlanner.Recipe.SOUP, true));
                var confirm = click(view, player, 49); gui.click(confirm); verify(confirm).setCancelled(true);
                assertNull(scheduled.get(), "changing recipe invalidates the old quote"); verify(engine, never()).confirmCook(any(), any());
                gui.click(chooseFish); verify(engine).quoteCook(player, 3, FishCookingPlanner.Recipe.SOUP);
                gui.click(confirm); verify(engine, never()).confirmCook(any(), any());
                var original = holder.get(); holder.set(mock(InventoryHolder.class)); scheduled.get().run();
                verify(engine, never()).confirmCook(any(), any());
                holder.set(original); gui.click(chooseFish); gui.click(confirm); scheduled.get().run();
                verify(engine, times(1)).confirmCook(player, soup); verify(player).closeInventory();
                verify(inventory, never()).setItem(anyInt(), any()); verify(inventory, never()).setStorageContents(any());
                assertSame(fish, storage[3]);
                for (var meta : metadata) verify(meta, never()).getPersistentDataContainer();
            }
        }
    }
    private static InventoryClickEvent click(InventoryView view, Player player, int slot) {
        var event = mock(InventoryClickEvent.class); when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player); when(event.getRawSlot()).thenReturn(slot); return event;
    }
}
