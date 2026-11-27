package ru.cherdakmd.diamondshop;

import java.util.*;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopQuantityGuiVisualsTest {
    @Test void buyAndSellStatesPreserveItemAndQuoteAndNeverTradeOnRender() throws Exception {
        for(boolean enabled:new boolean[]{false,true}) {
            var plugin=mock(DiamondShopPlugin.class);var config=new YamlConfiguration();config.set("textures.quantity-menu",enabled);when(plugin.getConfig()).thenReturn(config);
            when(plugin.messages()).thenReturn(Messages.from(java.nio.file.Files.newInputStream(java.nio.file.Path.of("src/main/resources/messages.yml")),null,Logger.getAnonymousLogger()));var service=mock(ShopService.class);when(service.balanceText(any())).thenReturn("0");
            var player=mock(Player.class);var pInv=mock(PlayerInventory.class);when(player.getInventory()).thenReturn(pInv);when(pInv.getStorageContents()).thenReturn(new ItemStack[0]);
            var inv=mock(Inventory.class);var slots=new HashMap<Integer,ItemStack>();doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inv).setItem(anyInt(),any(ItemStack.class));doAnswer(c->{slots.clear();return null;}).when(inv).clear();
            var klass=Class.forName("ru.cherdakmd.diamondshop.ShopQuantityGui$Holder");var ctor=klass.getDeclaredConstructor(UUID.class,ShopConfig.ShopItem.class,boolean.class,ShopCategory.class,int.class,ShopService.class,int.class,long.class);ctor.setAccessible(true);var inventoryField=klass.getDeclaredField("inventory");inventoryField.setAccessible(true);var units=klass.getDeclaredField("units");units.setAccessible(true);
            var render=ShopQuantityGui.class.getDeclaredMethod("render",Player.class,klass);render.setAccessible(true);var gui=new ShopQuantityGui(plugin);var product=new ShopConfig.ShopItem("STONE","misc",1,100,0.5,2);
            try(var icons=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                var buy=ctor.newInstance(UUID.randomUUID(),product,true,ShopCategory.ALL,0,service,1,100L);inventoryField.set(buy,inv);units.setInt(buy,1);render.invoke(gui,player,buy);
                int[] positions={10,11,12,14,15,16};String[] deltas={"minus_64","minus_8","minus_1","plus_1","plus_8","plus_64"};for(int i=0;i<positions.length;i++)check(slots.get(positions[i]),deltas[i],enabled);
                int[] values={1,8,16,32,64};for(int i=0;i<values.length;i++)check(slots.get(28+i),"preset_"+values[i],enabled);check(slots.get(22),"total_buy",enabled);check(slots.get(40),"confirm",enabled);check(slots.get(45),"back",enabled);var preview=slots.get(4);assertNotNull(preview);verify(preview,never()).getItemMeta();
                var sell=ctor.newInstance(UUID.randomUUID(),product,false,ShopCategory.ALL,0,service,100,1L);inventoryField.set(sell,inv);units.setInt(sell,1);render.invoke(gui,player,sell);check(slots.get(22),"total_sell",enabled);check(slots.get(33),"minimum",enabled);check(slots.get(40),"unavailable",enabled);check(slots.get(49),"maximum",enabled);verify(slots.get(4),never()).getItemMeta();
                verify(service,never()).quoted(any(),any(),anyLong(),anyBoolean(),anyInt(),anyLong());verify(pInv,never()).addItem(any());
            }
        }
    }
    private static void check(ItemStack item,String id,boolean enabled){assertNotNull(item);if(enabled)verify(item.getItemMeta()).setItemModel(ShopQuantityVisuals.key(id));else verify(item.getItemMeta(),never()).setItemModel(any());}
}
