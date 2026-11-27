package ru.cherdakmd.chrdkregister;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DonateMenuGuiVisualsTest {
    @Test void tierLinksAndPrivateHistoryRemainUnchanged() {
        for(boolean enabled:new boolean[]{false,true})for(boolean admin:new boolean[]{false,true}) {
            var access=mock(AccessManager.class);var config=mock(PluginConfig.class);when(access.config()).thenReturn(config);when(config.passGuiMenu()).thenReturn(true);
            var tiers=new ArrayList<PluginConfig.PassTier>();for(int i=0;i<7;i++)tiers.add(new PluginConfig.PassTier(100+i*100,30+i,"Tier"+i));when(config.passTiers()).thenReturn(tiers);
            var store=mock(RegistrationStore.class);when(access.store()).thenReturn(store);var player=mock(Player.class);when(player.getName()).thenReturn("Alice");when(player.hasPermission("chrdkregister.admin")).thenReturn(admin);
            var granted=new RegistrationStore.DonationRecord();granted.status="GRANTED";granted.amountKopecks=15000;granted.days=30;
            var pending=new RegistrationStore.DonationRecord();pending.status="PENDING_REVIEW";var rejected=new RegistrationStore.DonationRecord();rejected.status="REJECTED";
            when(store.listDonationsOf("Alice",8)).thenReturn(List.of(granted,pending,rejected));
            var messages=Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger());var gui=new DonateMenuService(access,messages);gui.setVisualModels(()->enabled);
            var inventory=mock(Inventory.class);var holder=new AtomicReference<InventoryHolder>();when(inventory.getHolder()).thenAnswer(c->holder.get());var slots=new HashMap<Integer,ItemStack>();
            doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inventory).setItem(anyInt(),any(ItemStack.class));
            try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(27),any(Component.class))).thenAnswer(c->{holder.set(c.getArgument(0));slots.clear();return inventory;});
                gui.openMenu(player);check(slots.get(4),DonateMenuVisuals.Icon.PAY,enabled);for(int i=0;i<7;i++)check(slots.get(10+i),DonateMenuVisuals.tier(i),enabled);
                check(slots.get(18),DonateMenuVisuals.Icon.GRANTED,enabled);check(slots.get(19),DonateMenuVisuals.Icon.PENDING,enabled);check(slots.get(20),DonateMenuVisuals.Icon.REJECTED,enabled);
                if(admin)check(slots.get(8),DonateMenuVisuals.Icon.ADMIN,enabled);else assertFalse(slots.containsKey(8));
                var event=mock(InventoryClickEvent.class);when(event.getInventory()).thenReturn(inventory);when(event.getClickedInventory()).thenReturn(inventory);when(event.getWhoClicked()).thenReturn(player);when(event.getRawSlot()).thenReturn(10);
                gui.onInventoryClick(event);verify(event).setCancelled(true);verify(access).sendNoPassPrompt(player,false);
                when(event.getRawSlot()).thenReturn(18);gui.onInventoryClick(event);verify(access,times(1)).sendNoPassPrompt(player,false);verify(player,never()).getInventory();
                when(store.listDonationsOf("Alice",8)).thenReturn(List.of());gui.openMenu(player);check(slots.get(21),DonateMenuVisuals.Icon.EMPTY,enabled);
                for(var item:items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item,DonateMenuVisuals.Icon icon,boolean enabled){assertNotNull(item);if(enabled)verify(item.getItemMeta()).setItemModel(icon.model());else verify(item.getItemMeta(),never()).setItemModel(any());}
}
