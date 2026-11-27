package ru.cherdakmd.chrdkbp;

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
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BattlePassMissionMenuGuiTest {
    @Test void openingMenuRendersActualMissionAndFilterStatesWithoutClaiming() {
        for (boolean enabled : new boolean[]{false,true}) {
            var plugin=mock(BattlePassPlugin.class); var config=new YamlConfiguration(); config.set("textures.mission-menu",enabled); when(plugin.getConfig()).thenReturn(config);
            var messages=Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger()); when(plugin.messages()).thenReturn(messages);
            when(plugin.settings()).thenReturn(BattlePassSettings.defaults()); when(plugin.screen()).thenReturn(new BattlePassScreen(messages));
            var store=mock(BattlePassStore.class); when(plugin.store()).thenReturn(store); var claims=mock(BattlePassClaims.class); when(plugin.claims()).thenReturn(claims);
            var player=mock(Player.class); var id=UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id); when(plugin.canUse(player)).thenReturn(true);
            var ready=new BattlePassConfig.MissionDef("ready","Ready",BattlePassConfig.MissionType.MINE,"stone",10,1,0);
            var future=new BattlePassConfig.MissionDef("future","Future",BattlePassConfig.MissionType.FISH,"any",10,1,0);
            var season=new BattlePassConfig.Season("Season",System.currentTimeMillis()-1000,List.of(new BattlePassConfig.WeekDef(0,List.of(ready)),new BattlePassConfig.WeekDef(100,List.of(future))));
            when(plugin.season()).thenReturn(season); when(store.progressOf(id,"ready")).thenReturn(10);
            when(store.progressSnapshot()).thenReturn(Map.of()); when(store.claimsSnapshot()).thenReturn(Map.of());
            var slots=new HashMap<Integer,ItemStack>(); var inv=mock(Inventory.class);
            doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inv).setItem(anyInt(),any(ItemStack.class));
            try(var bukkit=mockStatic(Bukkit.class); var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenReturn(inv);
                var gui=new BattlePassMenu(plugin); gui.missions(player,0,BattlePassFilter.ALL);
                check(slots.get(0),BattlePassMenuVisuals.mission(ready,true,BattlePassView.State.READY),enabled);
                check(slots.get(1),BattlePassMenuVisuals.mission(future,false,BattlePassView.State.PROGRESS),enabled);
                check(slots.get(46),BattlePassMenuVisuals.button("menu-claim-all"),enabled); check(slots.get(48),BattlePassMenuVisuals.summary(),enabled);
                check(slots.get(51),BattlePassMenuVisuals.filter(BattlePassFilter.ALL),enabled);
                verify(slots.get(0).getItemMeta()).setEnchantmentGlintOverride(true); verifyNoInteractions(claims); verify(player,never()).getInventory();
                for(var item:items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item,org.bukkit.NamespacedKey key,boolean enabled) {
        assertNotNull(item); if(enabled)verify(item.getItemMeta()).setItemModel(key); else verify(item.getItemMeta(),never()).setItemModel(any());
    }
    @Test void claimAllRemainsDeferredAndRejectsStaleWindowsAndAccessChanges() throws Exception {
        assertTrue(BattlePassMenu.class.getMethod("onClick",InventoryClickEvent.class).getAnnotation(EventHandler.class).ignoreCancelled());
        var plugin=mock(BattlePassPlugin.class); var claims=mock(BattlePassClaims.class); when(plugin.claims()).thenReturn(claims);
        when(plugin.settings()).thenReturn(BattlePassSettings.defaults()); var player=mock(Player.class); var id=UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id); when(player.isOnline()).thenReturn(true); when(plugin.canUse(player)).thenReturn(true);
        var mission=new BattlePassConfig.MissionDef("a","desc",BattlePassConfig.MissionType.MINE,"stone",1,1,0);
        var season=new BattlePassConfig.Season("season",0,List.of(new BattlePassConfig.WeekDef(0,List.of(mission)))); when(plugin.season()).thenReturn(season);
        var holder=new BattlePassMenu.Holder(id,0,season); var inv=mock(Inventory.class); holder.bind(inv,List.of("a"),1); when(inv.getHolder()).thenReturn(holder);
        var view=mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inv); when(player.getOpenInventory()).thenReturn(view);
        var scheduler=mock(BukkitScheduler.class); var task=new AtomicReference<Runnable>(); doAnswer(c->{task.set(c.getArgument(1));return mock(BukkitTask.class);}).when(scheduler).runTask(eq(plugin),any(Runnable.class));
        var gui=new BattlePassMenu(plugin);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            var event=mock(InventoryClickEvent.class); when(event.getView()).thenReturn(view); when(event.getWhoClicked()).thenReturn(player); when(event.getRawSlot()).thenReturn(46);
            when(event.getClick()).thenReturn(ClickType.SHIFT_LEFT); gui.onClick(event); assertNull(task.get());
            when(event.getClick()).thenReturn(ClickType.LEFT); gui.onClick(event); assertNotNull(task.get());
            var other=mock(InventoryView.class); var otherInv=mock(Inventory.class); when(other.getTopInventory()).thenReturn(otherInv); when(player.getOpenInventory()).thenReturn(other); task.getAndSet(null).run(); verifyNoInteractions(claims);
            when(player.getOpenInventory()).thenReturn(view); gui.onClick(event); when(plugin.canUse(player)).thenReturn(false); task.getAndSet(null).run(); verifyNoInteractions(claims);
            when(plugin.canUse(player)).thenReturn(true); gui.onClick(event); when(player.isOnline()).thenReturn(false); task.getAndSet(null).run(); verifyNoInteractions(claims);
            when(player.isOnline()).thenReturn(true); doThrow(new IllegalStateException("claim route")).when(claims).claimAll(player); gui.onClick(event);
            assertThrows(IllegalStateException.class,()->task.getAndSet(null).run()); verify(claims).claimAll(player);
        }
    }
}
