package ru.cherdakmd.chrdkregister.api.exploration;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExplorationAtlasGuiVisualsTest {
    @Test void actualListAndDeferredNavigationPreserveScopeAndProgress() {
        for (boolean enabled:new boolean[]{false,true}) for (String scope:List.of("lifetime","season:1")) {
            var owner=mock(JavaPlugin.class);var config=new YamlConfiguration();config.set("textures.atlas-menu",enabled);when(owner.getConfig()).thenReturn(config);
            var store=mock(ExplorationStore.class);when(store.healthy()).thenReturn(true);var registry=List.of("minecraft:plains","minecraft:desert","minecraft:forest");when(store.catalog(eq(scope),anyList())).thenReturn(registry);
            var player=mock(Player.class);var id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);when(player.isOnline()).thenReturn(true);
            when(store.visited(scope,id,"minecraft:desert")).thenReturn(true);when(store.visited(scope,id,"minecraft:forest")).thenReturn(true);when(store.done(scope,id,"minecraft:forest")).thenReturn(5);
            var messages=Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger());
            var inventory=mock(Inventory.class);var slots=new HashMap<Integer,ItemStack>();var holder=new AtomicReference<InventoryHolder>();when(inventory.getHolder()).thenAnswer(c->holder.get());
            doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inventory).setItem(anyInt(),any(ItemStack.class));
            var view=mock(InventoryView.class);when(view.getTopInventory()).thenReturn(inventory);when(player.getOpenInventory()).thenReturn(view);
            var scheduler=mock(BukkitScheduler.class);var task=new AtomicReference<Runnable>();when(scheduler.runTaskTimer(eq(owner),any(Runnable.class),anyLong(),anyLong())).thenReturn(mock(BukkitTask.class));when(scheduler.runTaskTimerAsynchronously(eq(owner),any(Runnable.class),anyLong(),anyLong())).thenReturn(mock(BukkitTask.class));
            doAnswer(c->{task.set(c.getArgument(1));return mock(BukkitTask.class);}).when(scheduler).runTask(eq(owner),any(Runnable.class));
            var actualScope=new AtomicReference<>(scope);var access=new AtomicReference<>(true);var backCount=new int[]{0};
            try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{holder.set(c.getArgument(0));slots.clear();return inventory;});
                var atlas=new ExplorationAtlas(owner,store,()->messages,actualScope::get,p->access.get(),()->true,p->backCount[0]++,registry);
                atlas.open(player,0);check(slots.get(0),ExplorationAtlasVisuals.Icon.UNKNOWN,enabled);check(slots.get(1),ExplorationAtlasVisuals.Icon.VISITED,enabled);check(slots.get(2),ExplorationAtlasVisuals.Icon.COMPLETE,enabled);
                check(slots.get(46),ExplorationAtlasVisuals.Icon.SUMMARY,enabled);check(slots.get(48),ExplorationAtlasVisuals.Icon.BACK,enabled);check(slots.get(49),ExplorationAtlasVisuals.Icon.CLOSE,enabled);
                verify(store,never()).visit(anyString(),any(),anyString());verify(store,never()).add(anyString(),any(),anyString(),any(),anyLong(),any());
                var event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);when(event.getRawSlot()).thenReturn(48);when(event.getClick()).thenReturn(ClickType.LEFT);
                atlas.click(event);verify(event).setCancelled(true);assertNotNull(task.get());atlas.click(event);task.getAndSet(null).run();assertEquals(1,backCount[0]);
                atlas.open(player,0);atlas.click(event);access.set(false);task.getAndSet(null).run();assertEquals(1,backCount[0]);
                access.set(true);atlas.open(player,0);atlas.click(event);actualScope.set("season:2");task.getAndSet(null).run();assertEquals(1,backCount[0]);
                verify(player,never()).getInventory();for(var item:items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item,ExplorationAtlasVisuals.Icon icon,boolean enabled) {
        assertNotNull(item);if(enabled)verify(item.getItemMeta()).setItemModel(icon.model());else verify(item.getItemMeta(),never()).setItemModel(any());
    }
}
