package ru.cherdakmd.chrdkbp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BattlePassGuideGuiTest {
    @Test void rewardPreviewsReflectEachRealStateWithoutGrantingAnything() {
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin=mock(BattlePassPlugin.class); var yaml=new YamlConfiguration(); yaml.set("textures.guide-menu",enabled); when(plugin.getConfig()).thenReturn(yaml);
            var messages=Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger()); when(plugin.messages()).thenReturn(messages);
            when(plugin.settings()).thenReturn(BattlePassSettings.defaults()); var player=mock(Player.class); var id=UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id); when(plugin.canUse(player)).thenReturn(true);
            var api=mock(ChrdkRegisterApi.class); when(plugin.coreApi()).thenReturn(api); var store=mock(BattlePassStore.class); when(plugin.store()).thenReturn(store);
            var claims=mock(BattlePassClaims.class); when(plugin.claims()).thenReturn(claims); when(store.progressMap(id)).thenReturn(Map.of());
            var a=mission("ready",1,0); var b=mission("received",1,0); var c=mission("working",1,0); var d=mission("empty",0,0); var e=mission("vip",0,1); var f=mission("future",1,0);
            var season=new BattlePassConfig.Season("Season",System.currentTimeMillis()-1000,List.of(new BattlePassConfig.WeekDef(0,List.of(a,b,c,d,e)),new BattlePassConfig.WeekDef(100,List.of(f))));
            when(plugin.season()).thenReturn(season); for (String key : List.of("ready","received","vip","future")) when(store.progressOf(id,key)).thenReturn(10);
            when(store.progressOf(id,"working")).thenReturn(3); when(store.isClaimed(id,"received","FREE")).thenReturn(true);
            var inv=mock(Inventory.class); var slots=new HashMap<Integer,ItemStack>(); doAnswer(call->{slots.put(call.getArgument(0),call.getArgument(1));return null;}).when(inv).setItem(anyInt(),any(ItemStack.class));
            try(var bukkit=mockStatic(Bukkit.class); var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenReturn(inv);
                var guide=new BattlePassGuide(plugin); guide.rewards(player,0);
                check(slots.get(19),BattlePassGuideVisuals.track("FREE","guide-ready"),enabled);
                check(slots.get(20),BattlePassGuideVisuals.track("FREE","guide-received"),enabled);
                check(slots.get(21),BattlePassGuideVisuals.track("FREE","guide-in-progress"),enabled);
                check(slots.get(22),BattlePassGuideVisuals.track("FREE","guide-no-reward"),enabled);
                check(slots.get(24),BattlePassGuideVisuals.track("FREE","guide-future"),enabled);
                check(slots.get(32),BattlePassGuideVisuals.track("VIP","guide-access-locked"),enabled);
                check(slots.get(4),BattlePassGuideVisuals.button("guide-reward-page"),enabled);
                check(slots.get(53),BattlePassGuideVisuals.button("menu-claim-all"),enabled);
                slots.clear(); guide.detail(player,"ready");
                check(slots.get(20),BattlePassGuideVisuals.track("FREE","guide-ready"),enabled);
                check(slots.get(15),BattlePassGuideVisuals.button("guide-targets"),enabled);
                check(slots.get(31),BattlePassGuideVisuals.button("guide-claim-one"),enabled);
                slots.clear(); guide.home(player);
                check(slots.get(4),BattlePassGuideVisuals.button("guide-season"),enabled); check(slots.get(22),BattlePassGuideVisuals.button("guide-tasks"),enabled);
                slots.clear(); guide.help(player); check(slots.get(22),BattlePassGuideVisuals.button("guide-fair"),enabled);
                verifyNoInteractions(claims); verify(player,never()).getInventory(); for(var item:items.constructed()) verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static BattlePassConfig.MissionDef mission(String id,int free,int vip) { return new BattlePassConfig.MissionDef(id,"Mission",BattlePassConfig.MissionType.FISH,"any",10,free,vip); }
    private static void check(ItemStack item,org.bukkit.NamespacedKey model,boolean enabled) { assertNotNull(item); if(enabled)verify(item.getItemMeta()).setItemModel(model); else verify(item.getItemMeta(),never()).setItemModel(any()); }

    @Test void actionsAreOneShotAndDeferredWithAccessAndViewChecks() throws Exception {
        assertTrue(BattlePassGuide.class.getMethod("click",InventoryClickEvent.class).getAnnotation(EventHandler.class).ignoreCancelled());
        var plugin=mock(BattlePassPlugin.class); var player=mock(Player.class); var id=UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id); when(plugin.canUse(player)).thenReturn(true);
        var season=new BattlePassConfig.Season("Season",0,List.of(new BattlePassConfig.WeekDef(0,List.of(mission("test",1,0))))); when(plugin.season()).thenReturn(season);
        var inv=mock(Inventory.class); var holder=new BattlePassGuide.Holder(id,season); holder.inventory=inv; when(inv.getHolder()).thenReturn(holder);
        var view=mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inv); when(player.getOpenInventory()).thenReturn(view);
        var event=mock(InventoryClickEvent.class); when(event.getView()).thenReturn(view); when(event.getWhoClicked()).thenReturn(player); when(event.getRawSlot()).thenReturn(31);
        var actions=new AtomicInteger(); holder.actions.put(31,p->actions.incrementAndGet()); var scheduled=new AtomicReference<Runnable>(); var scheduler=mock(BukkitScheduler.class);
        doAnswer(c->{scheduled.set(c.getArgument(1));return mock(BukkitTask.class);}).when(scheduler).runTask(eq(plugin),any(Runnable.class)); var gui=new BattlePassGuide(plugin);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(event.getClick()).thenReturn(ClickType.SHIFT_LEFT); gui.click(event); assertNull(scheduled.get());
            when(event.getClick()).thenReturn(ClickType.LEFT); gui.click(event); gui.click(event); verify(scheduler,times(1)).runTask(eq(plugin),any(Runnable.class));
            when(plugin.canUse(player)).thenReturn(false); scheduled.getAndSet(null).run(); assertEquals(0,actions.get());
            holder.consumed=false; when(plugin.canUse(player)).thenReturn(true); gui.click(event);
            var other=mock(InventoryView.class); var otherInv=mock(Inventory.class); when(other.getTopInventory()).thenReturn(otherInv); when(player.getOpenInventory()).thenReturn(other);
            scheduled.getAndSet(null).run(); assertEquals(0,actions.get());
            holder.consumed=false; when(player.getOpenInventory()).thenReturn(view); gui.click(event); scheduled.getAndSet(null).run(); assertEquals(1,actions.get());
        }
    }
}
