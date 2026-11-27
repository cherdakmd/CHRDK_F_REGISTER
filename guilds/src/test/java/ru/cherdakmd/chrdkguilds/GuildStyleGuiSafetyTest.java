package ru.cherdakmd.chrdkguilds;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

class GuildStyleGuiSafetyTest {
    @Test void cancelledForeignAndNonStandardClicksDoNotScheduleAndDeferredRoleIsRechecked() throws Exception {
        var plugin=mock(GuildsPlugin.class); when(plugin.config()).thenReturn(GuildConfig.defaults());
        when(plugin.messages()).thenReturn(Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger()));
        var service=mock(GuildService.class); when(plugin.service()).thenReturn(service); var styles=mock(GuildStyleController.class); when(plugin.styles()).thenReturn(styles);
        var player=mock(Player.class); var owner=UUID.randomUUID(); when(player.getUniqueId()).thenReturn(owner); when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("chrdkguilds.use")).thenReturn(true); when(plugin.visible(player)).thenReturn(true);
        var guild=new Guild("clan","Alpha","TAG",owner,1000); var replacement=UUID.randomUUID(); guild.addMember(replacement,GuildRank.OFFICER); when(service.guildOf(owner)).thenReturn(guild);
        var inventory=mock(Inventory.class); var holder=new GuildStyleGui.Holder(owner,"clan","name",0); holder.inventory=inventory; holder.actions.put(19,new String[]{"style","name","gold"}); when(inventory.getHolder()).thenReturn(holder);
        var view=mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inventory); when(player.getOpenInventory()).thenReturn(view);
        var scheduler=mock(BukkitScheduler.class); var task=new AtomicReference<Runnable>(); doAnswer(c->{task.set(c.getArgument(1));return mock(BukkitTask.class);}).when(scheduler).runTask(eq(plugin),any(Runnable.class));
        var event=mock(InventoryClickEvent.class); when(event.getView()).thenReturn(view); when(event.getWhoClicked()).thenReturn(player); when(event.getRawSlot()).thenReturn(19); when(event.getClick()).thenReturn(ClickType.LEFT);
        var gui=new GuildStyleGui(plugin);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(event.isCancelled()).thenReturn(true); gui.click(event); verify(event).setCancelled(true); assertNull(task.get());
            when(event.isCancelled()).thenReturn(false); when(event.getClick()).thenReturn(ClickType.SHIFT_LEFT); gui.click(event); assertNull(task.get());
            var foreign=mock(Player.class); when(foreign.getUniqueId()).thenReturn(UUID.randomUUID()); when(event.getWhoClicked()).thenReturn(foreign); when(event.getClick()).thenReturn(ClickType.LEFT); gui.click(event); assertNull(task.get());
            when(event.getWhoClicked()).thenReturn(player); gui.click(event); assertNotNull(task.get()); when(player.isOnline()).thenReturn(false); task.getAndSet(null).run(); verifyNoInteractions(styles);
            when(player.isOnline()).thenReturn(true); gui.click(event); var other=mock(InventoryView.class); var otherInv=mock(Inventory.class); when(other.getTopInventory()).thenReturn(otherInv); when(player.getOpenInventory()).thenReturn(other); task.getAndSet(null).run(); verifyNoInteractions(styles);
            when(player.getOpenInventory()).thenReturn(view); gui.click(event); assertTrue(guild.transferLeader(replacement)); task.getAndSet(null).run(); verifyNoInteractions(styles);
            assertTrue(guild.transferLeader(owner)); gui.click(event); task.getAndSet(null).run(); verify(styles).command(eq(player),aryEq(new String[]{"style","name","gold"}));
        }
    }
    @Test void mainMenuStyleOpenRequiresSameWindowAccessAndGuildOnNextTick() throws Exception {
        assertTrue(GuildsGui.class.getMethod("onClick",InventoryClickEvent.class).getAnnotation(EventHandler.class).ignoreCancelled());
        var plugin=mock(GuildsPlugin.class); when(plugin.config()).thenReturn(GuildConfig.defaults()); var service=mock(GuildService.class); when(plugin.service()).thenReturn(service);
        var styles=mock(GuildStyleController.class); when(plugin.styles()).thenReturn(styles); var player=mock(Player.class); var owner=UUID.randomUUID(); when(player.getUniqueId()).thenReturn(owner);
        when(player.isOnline()).thenReturn(true); when(player.hasPermission("chrdkguilds.use")).thenReturn(true); when(plugin.visible(player)).thenReturn(true); when(plugin.authenticated(player)).thenReturn(true);
        var guild=new Guild("clan","Alpha","TAG",owner,1000); when(service.guildOf(owner)).thenReturn(guild);
        var inv=mock(Inventory.class); var holder=new GuildsGui.Holder(owner); when(inv.getHolder()).thenReturn(holder); var view=mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inv); when(player.getOpenInventory()).thenReturn(view);
        var event=mock(InventoryClickEvent.class); when(event.getInventory()).thenReturn(inv); when(event.getWhoClicked()).thenReturn(player); when(event.getRawSlot()).thenReturn(0); when(event.getClick()).thenReturn(ClickType.LEFT);
        var task=new AtomicReference<Runnable>(); var scheduler=mock(BukkitScheduler.class); doAnswer(c->{task.set(c.getArgument(1));return mock(BukkitTask.class);}).when(scheduler).runTask(eq(plugin),any(Runnable.class));
        var gui=new GuildsGui(plugin); try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            gui.onClick(event); when(player.isOnline()).thenReturn(false); task.getAndSet(null).run(); verifyNoInteractions(styles);
            when(player.isOnline()).thenReturn(true); gui.onClick(event); when(plugin.visible(player)).thenReturn(false); task.getAndSet(null).run(); verifyNoInteractions(styles);
            when(plugin.visible(player)).thenReturn(true); gui.onClick(event); var other=mock(InventoryView.class); var otherInv=mock(Inventory.class); when(other.getTopInventory()).thenReturn(otherInv); when(player.getOpenInventory()).thenReturn(other); task.getAndSet(null).run(); verifyNoInteractions(styles);
            when(player.getOpenInventory()).thenReturn(view); gui.onClick(event); when(service.guildOf(owner)).thenReturn(new Guild("other","Other","OT",owner,1000)); task.getAndSet(null).run(); verifyNoInteractions(styles);
            when(service.guildOf(owner)).thenReturn(guild); gui.onClick(event); task.getAndSet(null).run(); verify(styles).command(eq(player),aryEq(new String[]{"style"}));
        }
    }
}
