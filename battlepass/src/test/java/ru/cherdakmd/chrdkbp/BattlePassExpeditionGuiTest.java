package ru.cherdakmd.chrdkbp;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
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
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.api.exploration.ExplorationStore;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BattlePassExpeditionGuiTest {
    @Test void windowsReflectRealStatesAndDoNotAlterStoredData() throws Exception {
        for(boolean enabled : new boolean[]{false,true}) {
            var plugin=mock(BattlePassPlugin.class); var yaml=new YamlConfiguration(); yaml.set("textures.expedition-menu",enabled); when(plugin.getConfig()).thenReturn(yaml);
            when(plugin.messages()).thenReturn(Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger()));
            var api=mock(ChrdkRegisterApi.class); when(plugin.coreApi()).thenReturn(api);
            var store=mock(ExplorationStore.class); when(store.healthy()).thenReturn(true);
            var player=mock(Player.class); var id=UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id); when(plugin.canUse(player)).thenReturn(true);
            var season=new BattlePassConfig.Season("Season",0,List.of(new BattlePassConfig.WeekDef(0,List.of(new BattlePassConfig.MissionDef("a","a",BattlePassConfig.MissionType.FISH,"any",1,1,0))))); when(plugin.season()).thenReturn(season);
            var inv=mock(Inventory.class); var slots=new HashMap<Integer,ItemStack>(); doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inv).setItem(anyInt(),any(ItemStack.class));
            var scheduler=mock(BukkitScheduler.class); when(scheduler.runTaskTimer(eq(plugin),any(Runnable.class),eq(20L),eq(20L))).thenReturn(mock(BukkitTask.class)); var manager=mock(PluginManager.class);
            try(var bukkit=mockStatic(Bukkit.class); var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(Bukkit::getPluginManager).thenReturn(manager); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler); bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenReturn(inv);
                var gui=new BattlePassExpeditions(plugin,store);
                for(int level=0;level<=2;level++) {
                    slots.clear(); when(store.license(id)).thenReturn(level); when(store.effect(id)).thenReturn(level>0); when(store.title(id)).thenReturn(level==0?"off":level==1?"free":"premium"); gui.open(player);
                    check(slots.get(11),BattlePassExpeditionVisuals.license(false,level>=1),enabled); check(slots.get(15),BattlePassExpeditionVisuals.license(true,level==2),enabled);
                    check(slots.get(30),BattlePassExpeditionVisuals.title(level==0?"off":level==1?"free":"premium"),enabled);
                    check(slots.get(32),BattlePassExpeditionVisuals.effect(level>0),enabled);
                    check(slots.get(20),BattlePassExpeditionVisuals.button("exp-mark-current",null),enabled); check(slots.get(40),BattlePassExpeditionVisuals.button("exp-claim",null),enabled);
                }
                var world=UUID.randomUUID(); when(store.marks(id)).thenReturn(Map.of("point",new ExplorationStore.Waypoint(world.toString(),1,64,2)));
                when(store.routes(id)).thenReturn(Map.of("route",List.of("point")));
                slots.clear(); invoke(gui,"marksMenu",player); check(slots.get(0),BattlePassExpeditionVisuals.button("exp-point-name","exp-point-lore"),enabled);
                check(slots.get(45),BattlePassExpeditionVisuals.button("atlas-back",null),enabled);
                slots.clear(); invoke(gui,"routesMenu",player); check(slots.get(0),BattlePassExpeditionVisuals.button("exp-point-name","exp-saved-route-lore"),enabled);
                check(slots.get(40),BattlePassExpeditionVisuals.button("exp-route-create",null),enabled);
                verify(store,never()).award(any(),anyInt()); verify(store,never()).toggleEffect(any()); verify(store,never()).setTitle(any(),any());
                verify(store,never()).removeMark(any(),any()); verify(store,never()).removeRoute(any(),any()); verify(store,never()).route(any(),any(),any()); verify(player,never()).getInventory();
                for(var item:items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    @Test void leftNavigationAndRightDeletionRemainDistinctAndDeferred() throws Exception {
        assertTrue(BattlePassExpeditions.class.getMethod("click",InventoryClickEvent.class).getAnnotation(EventHandler.class).ignoreCancelled());
        var plugin=mock(BattlePassPlugin.class); when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.messages()).thenReturn(Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger())); when(plugin.coreApi()).thenReturn(mock(ChrdkRegisterApi.class));
        var store=mock(ExplorationStore.class); when(store.healthy()).thenReturn(true); var player=mock(Player.class); var id=UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id);
        when(plugin.canUse(player)).thenReturn(true); when(store.license(id)).thenReturn(1);
        var world=UUID.randomUUID(); when(store.marks(id)).thenReturn(Map.of("point",new ExplorationStore.Waypoint(world.toString(),1,64,2)));
        when(store.routes(id)).thenReturn(Map.of("route",List.of("point")));
        var season=new BattlePassConfig.Season("Season",0,List.of()); when(plugin.season()).thenReturn(season);
        var scheduler=mock(BukkitScheduler.class); var task=new AtomicReference<Runnable>(); when(scheduler.runTaskTimer(eq(plugin),any(Runnable.class),eq(20L),eq(20L))).thenReturn(mock(BukkitTask.class));
        doAnswer(c->{task.set(c.getArgument(1));return mock(BukkitTask.class);}).when(scheduler).runTask(eq(plugin),any(Runnable.class));
        var current=new AtomicReference<Inventory>(); var view=mock(InventoryView.class); when(view.getTopInventory()).thenAnswer(c->current.get()); when(player.getOpenInventory()).thenReturn(view);
        when(player.openInventory(any(Inventory.class))).thenAnswer(c->{current.set(c.getArgument(0));return view;});
        try(var bukkit=mockStatic(Bukkit.class); var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class)); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler); bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{var inventory=mock(Inventory.class); when(inventory.getHolder()).thenReturn(c.getArgument(0));return inventory;});
            var gui=new BattlePassExpeditions(plugin,store); var event=mock(InventoryClickEvent.class); when(event.getView()).thenReturn(view); when(event.getWhoClicked()).thenReturn(player); when(event.getRawSlot()).thenReturn(0);
            invoke(gui,"marksMenu",player); when(event.getClick()).thenReturn(ClickType.LEFT); gui.click(event); assertNotNull(task.get()); task.getAndSet(null).run(); verify(store,never()).removeMark(any(),any());
            invoke(gui,"marksMenu",player); when(event.getClick()).thenReturn(ClickType.RIGHT); gui.click(event); gui.click(event); assertNotNull(task.get());
            when(plugin.canUse(player)).thenReturn(false); task.getAndSet(null).run(); verify(store,never()).removeMark(any(),any());
            when(plugin.canUse(player)).thenReturn(true); invoke(gui,"marksMenu",player); gui.click(event); task.getAndSet(null).run(); verify(store,times(1)).removeMark(id,"point");
            invoke(gui,"routesMenu",player); when(event.getClick()).thenReturn(ClickType.LEFT); gui.click(event); task.getAndSet(null).run(); verify(store,never()).removeRoute(any(),any());
            invoke(gui,"routesMenu",player); when(event.getClick()).thenReturn(ClickType.RIGHT); gui.click(event); task.getAndSet(null).run(); verify(store,times(1)).removeRoute(id,"route");
        }
    }
    private static void check(ItemStack item,org.bukkit.NamespacedKey model,boolean enabled) { assertNotNull(item); if(enabled)verify(item.getItemMeta()).setItemModel(model); else verify(item.getItemMeta(),never()).setItemModel(any()); }
    private static void invoke(BattlePassExpeditions gui,String name,Player player) throws Exception { Method method=BattlePassExpeditions.class.getDeclaredMethod(name,Player.class); method.setAccessible(true); method.invoke(gui,player); }
}
