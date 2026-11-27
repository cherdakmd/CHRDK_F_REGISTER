package ru.cherdakmd.chrdkregister;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HonorMenuGuiVisualsTest {
    @Test void pagesUseOptInModelsWithoutReplacingHeadsOrChangingActions() {
        for(boolean enabled:new boolean[]{false,true}) {
            var plugin=mock(RegisterPlugin.class);var config=new YamlConfiguration();config.set("core-gui-textures.honor-menu",enabled);when(plugin.getConfig()).thenReturn(config);
            when(plugin.messages()).thenReturn(Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger()));
            var ledger=HonorLedger.withDefaults();ledger.grant(new HonorGrant("Alice","","old-citrus","Admin",1,"reason"));
            var service=mock(HonorService.class);when(service.ledger()).thenReturn(ledger);when(service.canViewGui(any())).thenReturn(true);
            var player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getName()).thenReturn("Admin");
            var inv=mock(Inventory.class);var holder=new AtomicReference<InventoryHolder>();when(inv.getHolder()).thenAnswer(c->holder.get());var slots=new HashMap<Integer,ItemStack>();
            doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inv).setItem(anyInt(),any(ItemStack.class));
            try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{holder.set(c.getArgument(0));slots.clear();return inv;});var gui=new HonorGui(plugin,service);
                gui.open(player,HonorGui.Page.DEFINITIONS,"");
                assertModels(slots,enabled,Map.ofEntries(Map.entry(0,HonorMenuVisuals.Icon.DEFINITIONS),Map.entry(1,HonorMenuVisuals.Icon.GRANTS),Map.entry(4,HonorMenuVisuals.Icon.INFO),Map.entry(6,HonorMenuVisuals.Icon.FILTER),Map.entry(8,HonorMenuVisuals.Icon.REFRESH),Map.entry(10,HonorMenuVisuals.Icon.DEFINITION),Map.entry(45,HonorMenuVisuals.Icon.PREV),Map.entry(46,HonorMenuVisuals.Icon.PAGES),Map.entry(47,HonorMenuVisuals.Icon.NEXT),Map.entry(48,HonorMenuVisuals.Icon.CREATE),Map.entry(52,HonorMenuVisuals.Icon.BACK),Map.entry(53,HonorMenuVisuals.Icon.CLOSE)));
                noModel(slots.get(49)); // generic player head
                gui.openForPlayer(player,"Alice");noModel(slots.get(4));check(slots.get(10),HonorMenuVisuals.Icon.DEFINITION,enabled);
                gui.open(player,HonorGui.Page.DETAIL,"old-citrus");noModel(slots.get(4));noModel(slots.get(10));
                assertModels(slots,enabled,Map.of(45,HonorMenuVisuals.Icon.DELETE,46,HonorMenuVisuals.Icon.JOIN,47,HonorMenuVisuals.Icon.DESCRIPTION,48,HonorMenuVisuals.Icon.NAME,49,HonorMenuVisuals.Icon.FORMAT,50,HonorMenuVisuals.Icon.BADGE,51,HonorMenuVisuals.Icon.LEVEL));
                gui.open(player,HonorGui.Page.GRANTS,"");check(slots.get(10),HonorMenuVisuals.Icon.GRANT_ENTRY,enabled);
                ledger.revoke("Alice","old-citrus");gui.open(player,HonorGui.Page.GRANTS,"");check(slots.get(HonorGui.ENTRY_SLOTS[HonorGui.ENTRY_SLOTS.length/2]),HonorMenuVisuals.Icon.EMPTY_GRANTS,enabled);
                gui.open(player,HonorGui.Page.DETAIL,"old-citrus");noModel(slots.get(HonorGui.ENTRY_SLOTS[HonorGui.ENTRY_SLOTS.length/2]));
                ledger.remove("old-citrus");gui.open(player,HonorGui.Page.DEFINITIONS,"");check(slots.get(HonorGui.ENTRY_SLOTS[HonorGui.ENTRY_SLOTS.length/2]),HonorMenuVisuals.Icon.EMPTY_DEFINITIONS,enabled);
                verify(service,never()).revoke(any(),anyString(),anyString());verify(service,never()).grant(any(),anyString(),anyString(),anyString());verify(player,never()).getInventory();
                for(var item:items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static void assertModels(Map<Integer,ItemStack> slots,boolean enabled,Map<Integer,HonorMenuVisuals.Icon> expected){expected.forEach((slot,icon)->check(slots.get(slot),icon,enabled));}
    private static void check(ItemStack item,HonorMenuVisuals.Icon icon,boolean enabled){assertNotNull(item);if(enabled)verify(item.getItemMeta()).setItemModel(HonorMenuVisuals.key(icon));else noModel(item);}
    private static void noModel(ItemStack item){assertNotNull(item);verify(item.getItemMeta(),never()).setItemModel(any());}
}
