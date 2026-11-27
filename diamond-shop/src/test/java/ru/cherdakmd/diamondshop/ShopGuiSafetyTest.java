package ru.cherdakmd.diamondshop;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopGuiSafetyTest {
    @Test void marketClickTypesAndOwnerAreCheckedBeforeMoneyActions() throws Exception {
        for(var click:ClickType.values())assertEquals(click==ClickType.LEFT||click==ClickType.RIGHT,ShopMarketGui.ordinaryClick(click));
        var plugin=mock(DiamondShopPlugin.class);var service=mock(ShopService.class);when(plugin.shopService()).thenReturn(service);when(plugin.isEnabled()).thenReturn(true);
        var player=mock(Player.class);var owner=UUID.randomUUID();when(player.getUniqueId()).thenReturn(owner);when(player.isOnline()).thenReturn(true);when(service.ready(player)).thenReturn(true);
        var inv=mock(Inventory.class);var view=mock(InventoryView.class);when(view.getTopInventory()).thenReturn(inv);when(player.getOpenInventory()).thenReturn(view);
        var klass=Class.forName("ru.cherdakmd.diamondshop.ShopMarketGui$Holder");var ctor=klass.getDeclaredConstructor(UUID.class,ShopCategory.class,int.class,ShopService.class,boolean.class);ctor.setAccessible(true);
        var holder=(InventoryHolder)ctor.newInstance(owner,null,0,service,true);when(inv.getHolder()).thenReturn(holder);var e=mock(InventoryClickEvent.class);when(e.getInventory()).thenReturn(inv);when(e.getView()).thenReturn(view);when(e.getClickedInventory()).thenReturn(inv);when(e.getWhoClicked()).thenReturn(player);when(e.getRawSlot()).thenReturn(48);
        var gui=new ShopMarketGui(plugin);when(e.getClick()).thenReturn(ClickType.SHIFT_LEFT);gui.onInventoryClick(e);verify(player,never()).performCommand(anyString());
        when(e.getClick()).thenReturn(ClickType.LEFT);when(e.isCancelled()).thenReturn(true);gui.onInventoryClick(e);verify(player,never()).performCommand(anyString());
        when(e.isCancelled()).thenReturn(false);when(player.getUniqueId()).thenReturn(UUID.randomUUID());gui.onInventoryClick(e);verify(player,never()).performCommand(anyString());
        when(player.getUniqueId()).thenReturn(owner);when(plugin.shopService()).thenReturn(mock(ShopService.class));gui.onInventoryClick(e);verify(player,never()).performCommand(anyString());
        when(plugin.shopService()).thenReturn(service);gui.onInventoryClick(e);verify(player).performCommand("shop sellall");
    }

    @Test void quantityConfirmRejectsNonOrdinaryOrCancelledClicksBeforeSubmission() throws Exception {
        var plugin=mock(DiamondShopPlugin.class);var service=mock(ShopService.class);var player=mock(Player.class);var owner=UUID.randomUUID();when(player.getUniqueId()).thenReturn(owner);
        var item=new ShopConfig.ShopItem("STONE","misc",1,100,0.5,2);var klass=Class.forName("ru.cherdakmd.diamondshop.ShopQuantityGui$Holder");
        var ctor=klass.getDeclaredConstructor(UUID.class,ShopConfig.ShopItem.class,boolean.class,ShopCategory.class,int.class,ShopService.class,int.class,long.class);ctor.setAccessible(true);var holder=ctor.newInstance(owner,item,true,ShopCategory.ALL,0,service,1,100L);
        var inv=mock(Inventory.class);var view=mock(InventoryView.class);when(inv.getHolder()).thenReturn((InventoryHolder)holder);when(view.getTopInventory()).thenReturn(inv);var field=klass.getDeclaredField("inventory");field.setAccessible(true);field.set(holder,inv);
        var event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);when(event.getClickedInventory()).thenReturn(inv);when(event.getRawSlot()).thenReturn(40);var gui=new ShopQuantityGui(plugin);
        when(event.getClick()).thenReturn(ClickType.SHIFT_LEFT);gui.click(event);when(event.getClick()).thenReturn(ClickType.NUMBER_KEY);gui.click(event);when(event.getClick()).thenReturn(ClickType.LEFT);when(event.isCancelled()).thenReturn(true);gui.click(event);verify(plugin,never()).getServer();verifyNoInteractions(service);
    }

    @Test void adminDraftCannotOverwriteNewerChangesAndRequiresOwnerAndOrdinaryClick() throws Exception {
        try(var f=new Fixture()) {
            f.open();when(f.event.getClick()).thenReturn(ClickType.NUMBER_KEY);f.click();verify(f.plugin,never()).applyOverrides(any());
            when(f.event.getClick()).thenReturn(ClickType.LEFT);when(f.event.isCancelled()).thenReturn(true);f.click();verify(f.plugin,never()).applyOverrides(any());
            when(f.event.isCancelled()).thenReturn(false);when(f.player.hasPermission("chrdkshop.admin")).thenReturn(false);f.click();verify(f.plugin,never()).applyOverrides(any());
            when(f.player.hasPermission("chrdkshop.admin")).thenReturn(true);when(f.player.getUniqueId()).thenReturn(UUID.randomUUID());f.click();verify(f.plugin,never()).applyOverrides(any());
            when(f.player.getUniqueId()).thenReturn(f.owner);f.open();assertTrue(f.live.set("STONE","base-price","7"));f.click();verify(f.plugin,never()).applyOverrides(any());verify(f.player,atLeastOnce()).closeInventory();
            f.open();when(f.plugin.shopService()).thenReturn(mock(ShopService.class));f.click();verify(f.plugin,never()).applyOverrides(any());
            when(f.plugin.shopService()).thenReturn(f.service);f.open();f.click();verify(f.plugin).applyOverrides(any());
        }
    }
    private static final class Fixture implements AutoCloseable {
        final DiamondShopPlugin plugin=mock(DiamondShopPlugin.class);final ShopService service=mock(ShopService.class);final ShopOverrides live=ShopOverrides.EMPTY.copy();final Player player=mock(Player.class);final UUID owner=UUID.randomUUID();final Inventory inv=mock(Inventory.class);final InventoryView view=mock(InventoryView.class);final InventoryClickEvent event=mock(InventoryClickEvent.class);final AtomicReference<InventoryHolder> holder=new AtomicReference<>();final org.mockito.MockedStatic<Bukkit> bukkit;final org.mockito.MockedConstruction<ItemStack> items;final ShopAdminGui gui;
        Fixture() throws Exception {
            when(plugin.overrides()).thenReturn(live);when(plugin.shopService()).thenReturn(service);when(plugin.isEnabled()).thenReturn(true);when(service.config()).thenReturn(new ShopConfig.ShopConfigData(Map.of(),0,0,1,2,List.of(),0,0,1,1000));
            when(plugin.messages()).thenReturn(Messages.from(java.nio.file.Files.newInputStream(java.nio.file.Path.of("src/main/resources/messages.yml")),null,Logger.getAnonymousLogger()));
            when(player.getUniqueId()).thenReturn(owner);when(player.isOnline()).thenReturn(true);when(player.hasPermission("chrdkshop.admin")).thenReturn(true);when(player.getOpenInventory()).thenReturn(view);when(view.getTopInventory()).thenReturn(inv);when(inv.getHolder()).thenAnswer(c->holder.get());
            when(event.getInventory()).thenReturn(inv);when(event.getView()).thenReturn(view);when(event.getClickedInventory()).thenReturn(inv);when(event.getWhoClicked()).thenReturn(player);when(event.getRawSlot()).thenReturn(49);when(event.getClick()).thenReturn(ClickType.LEFT);
            bukkit=mockStatic(Bukkit.class);bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{holder.set(c.getArgument(0));return inv;});items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)));gui=new ShopAdminGui(plugin);
        }
        void open(){gui.open(player,0);}
        void click(){gui.onInventoryClick(event);}
        public void close(){items.close();bukkit.close();}
    }
}
