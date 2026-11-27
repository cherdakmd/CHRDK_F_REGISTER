package ru.cherdakmd.chrdkguilds;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
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
import ru.cherdakmd.chrdkregister.api.PaidActions;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuildRecruitGuiVisualsTest {
    @Test void listAndConsentKeepTermsAndOnlyExplicitConfirmationCanJoin() throws Exception {
        assertTrue(GuildRecruitmentGui.class.getMethod("click",InventoryClickEvent.class).getAnnotation(EventHandler.class).ignoreCancelled());
        for(boolean enabled:new boolean[]{false,true}) {
            var plugin=mock(GuildsPlugin.class); var yaml=new YamlConfiguration(); yaml.set("textures.recruit-menu",enabled); when(plugin.getConfig()).thenReturn(yaml); when(plugin.config()).thenReturn(GuildConfig.defaults());
            when(plugin.messages()).thenReturn(Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger()));
            var player=mock(Player.class); UUID playerId=UUID.randomUUID(),owner=UUID.randomUUID(); when(player.getUniqueId()).thenReturn(playerId); when(player.isOnline()).thenReturn(true);
            when(player.hasPermission("chrdkguilds.use")).thenReturn(true); when(plugin.visible(player)).thenReturn(true);
            var payments=mock(PaidActions.class); when(plugin.payments()).thenReturn(payments); var store=mock(GuildStore.class); when(plugin.store()).thenReturn(store);
            var finance=mock(GuildFinance.class); when(plugin.finance()).thenReturn(finance); when(finance.join(eq(player),any(),anyLong(),anyLong())).thenReturn(new GuildResult(false,"recruit-changed",List.of()));
            var open=new Guild("open","Alpha","AA",owner,1000); open.recruitment(true); var closed=new Guild("closed","Beta","BB",UUID.randomUUID(),1000);
            when(store.guild("open")).thenReturn(open); when(store.guilds()).thenReturn(List.of(closed,open));
            when(store.inviteOf(playerId)).thenReturn(new GuildInvite("closed","Beta",playerId,owner,1,System.currentTimeMillis()+60_000));
            var inv=mock(Inventory.class); var holder=new AtomicReference<InventoryHolder>(); var slots=new HashMap<Integer,ItemStack>(); when(inv.getHolder()).thenAnswer(c->holder.get());
            doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inv).setItem(anyInt(),any(ItemStack.class));
            var view=mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inv); when(player.getOpenInventory()).thenReturn(view);
            var scheduler=mock(BukkitScheduler.class); var task=new AtomicReference<Runnable>(); doAnswer(c->{task.set(c.getArgument(1));return mock(BukkitTask.class);}).when(scheduler).runTask(eq(plugin),any(Runnable.class));
            try(var bukkit=mockStatic(Bukkit.class); var preview=mockStatic(GuildFinance.class); var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{holder.set(c.getArgument(0));slots.clear();return inv;}); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                preview.when(()->GuildFinance.preview(eq(store),any(GuildConfig.class),any())).thenReturn(new GuildFinance.Preview(new GuildResult(true,"ok",List.of()),"domain",open.duesRate(),0));
                var gui=new GuildRecruitmentGui(plugin); gui.browse(player,0);
                check(slots.get(4),GuildRecruitVisuals.Control.HEADING,enabled); check(slots.get(10),GuildRecruitVisuals.Control.OPEN,enabled);
                check(slots.get(11),GuildRecruitVisuals.Control.CLOSED_INVITED,enabled); check(slots.get(49),GuildRecruitVisuals.Control.CANCEL,enabled);
                verifyNoInteractions(finance);
                Method confirm=GuildRecruitmentGui.class.getDeclaredMethod("confirm",Player.class,String.class); confirm.setAccessible(true); confirm.invoke(gui,player,"open");
                check(slots.get(13),GuildRecruitVisuals.Control.TERMS,enabled); check(slots.get(30),GuildRecruitVisuals.Control.CONFIRM,enabled); check(slots.get(32),GuildRecruitVisuals.Control.CANCEL,enabled);
                verify(slots.get(13).getItemMeta()).lore(argThat(lore->lore!=null && lore.size()>=8)); verifyNoInteractions(finance);
                var snapshot=(GuildRecruitmentGui.Holder)holder.get(); assertEquals(open.duesRate(),snapshot.terms.fee()); assertFalse(snapshot.used);
                var event=mock(InventoryClickEvent.class); when(event.getView()).thenReturn(view); when(event.getWhoClicked()).thenReturn(player); when(event.getClick()).thenReturn(ClickType.LEFT); when(event.getRawSlot()).thenReturn(30);
                gui.click(event); verify(event).setCancelled(true); assertTrue(snapshot.used); assertNotNull(task.get()); verifyNoInteractions(finance);
                gui.click(event); task.getAndSet(null).run(); verify(finance,times(1)).join(eq(player),eq(snapshot.terms),eq(snapshot.expires),anyLong());
                verify(player,never()).getInventory(); for(var item:items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item,GuildRecruitVisuals.Control control,boolean enabled) {
        assertNotNull(item); if(enabled)verify(item.getItemMeta()).setItemModel(control.model()); else verify(item.getItemMeta(),never()).setItemModel(any());
    }
}
