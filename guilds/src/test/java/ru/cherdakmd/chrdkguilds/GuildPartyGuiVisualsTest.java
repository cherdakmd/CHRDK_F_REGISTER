package ru.cherdakmd.chrdkguilds;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
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
import ru.cherdakmd.chrdkregister.api.PaidActions;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

class GuildPartyGuiVisualsTest {
    @Test void controlsChangeAppearanceButPlayerEntriesAndActionsDoNot() {
        for(boolean enabled:new boolean[]{false,true}) {
            var plugin=mock(GuildsPlugin.class); var yaml=new YamlConfiguration(); yaml.set("textures.party-menu",enabled); when(plugin.getConfig()).thenReturn(yaml);
            var payments=mock(PaidActions.class); when(plugin.payments()).thenReturn(payments); var groups=mock(AdventureGroupController.class); when(plugin.adventureGroups()).thenReturn(groups);
            var player=mock(Player.class); var other=mock(Player.class); var id=UUID.randomUUID(); var otherId=UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(id); when(other.getUniqueId()).thenReturn(otherId); when(player.getName()).thenReturn("Alice"); when(other.getName()).thenReturn("Bob");
            when(player.isOnline()).thenReturn(true); when(plugin.visible(player)).thenReturn(true); when(plugin.visible(other)).thenReturn(true); when(player.canSee(other)).thenReturn(true);
            when(groups.menuMembers(id)).thenReturn(List.of(otherId));
            var holder=new AtomicReference<InventoryHolder>(); var slots=new HashMap<Integer,ItemStack>(); var inv=mock(Inventory.class); when(inv.getHolder()).thenAnswer(c->holder.get());
            doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inv).setItem(anyInt(),any(ItemStack.class));
            var view=mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inv); when(player.getOpenInventory()).thenReturn(view);
            var scheduler=mock(BukkitScheduler.class); var task=new AtomicReference<Runnable>(); doAnswer(c->{task.set(c.getArgument(1));return mock(BukkitTask.class);}).when(scheduler).runTask(eq(plugin),any(Runnable.class));
            try(var bukkit=mockStatic(Bukkit.class); var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{holder.set(c.getArgument(0));slots.clear();return inv;});
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler); bukkit.when(()->Bukkit.getPlayer(otherId)).thenReturn(other); bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player,other));
                var gui=new AdventureGroupGui(plugin); gui.open(player);
                check(slots.get(4),GuildPartyVisuals.Control.GROUP,enabled); check(slots.get(37),GuildPartyVisuals.Control.INVITE,enabled);
                check(slots.get(39),GuildPartyVisuals.Control.ACCEPT,enabled); check(slots.get(41),GuildPartyVisuals.Control.DECLINE,enabled); check(slots.get(43),GuildPartyVisuals.Control.LEAVE,enabled); check(slots.get(49),GuildPartyVisuals.Control.CLOSE,enabled);
                assertNotNull(slots.get(10)); verify(slots.get(10).getItemMeta(),never()).setItemModel(any());
                var event=mock(InventoryClickEvent.class); when(event.getView()).thenReturn(view); when(event.getWhoClicked()).thenReturn(player); when(event.getClick()).thenReturn(ClickType.LEFT); when(event.getRawSlot()).thenReturn(37);
                gui.click(event); assertNotNull(task.get()); task.getAndSet(null).run();
                assertNotNull(slots.get(10)); verify(slots.get(10).getItemMeta(),never()).setItemModel(any()); check(slots.get(49),GuildPartyVisuals.Control.BACK,enabled);
                when(event.getRawSlot()).thenReturn(10); gui.click(event); assertNotNull(task.get()); verify(groups,never()).command(any(),any()); task.getAndSet(null).run(); verify(groups).command(eq(player),aryEq(new String[]{"squad","invite","Bob"}));
                when(groups.menuMembers(id)).thenReturn(List.of()); gui.open(player); check(slots.get(4),GuildPartyVisuals.Control.EMPTY,enabled);
                verify(player,never()).getInventory(); for(var item:items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item,GuildPartyVisuals.Control control,boolean enabled) {
        assertNotNull(item);
        if(enabled) verify(item.getItemMeta()).setItemModel(control.model());
        else verify(item.getItemMeta(),never()).setItemModel(any());
    }
}
