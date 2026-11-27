package ru.cherdakmd.diamondshop;

import java.util.*;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopMarketGuiVisualsTest {
    @Test void marketStatesAreOptInAndProductCardsRemainUnchanged() throws Exception {
        for(boolean enabled:new boolean[]{false,true}) {
            var plugin=mock(DiamondShopPlugin.class);var yaml=new YamlConfiguration();yaml.set("textures.market-menu",enabled);when(plugin.getConfig()).thenReturn(yaml);when(plugin.messages()).thenReturn(Messages.from(java.nio.file.Files.newInputStream(java.nio.file.Path.of("src/main/resources/messages.yml")),null,Logger.getAnonymousLogger()));
            var product=new ShopConfig.ShopItem("STONE","misc",1,100,0.5,2);var service=mock(ShopService.class);var store=mock(ShopStore.class);when(plugin.shopService()).thenReturn(service);when(service.store()).thenReturn(store);when(service.config()).thenReturn(new ShopConfig.ShopConfigData(Map.of(),0,0,1,2,List.of(product),0,0,1,1000));when(service.batchSize(product)).thenReturn(1);when(service.askPrice(product)).thenReturn(100L);when(service.bidPrice(product)).thenReturn(90L);when(service.balanceText(any())).thenReturn("0");
            var player=mock(Player.class);var uuid=UUID.randomUUID();when(player.getUniqueId()).thenReturn(uuid);when(player.hasPermission("chrdkshop.admin")).thenReturn(true);when(service.ready(player)).thenReturn(true);
            var promo=mock(ShopPromoService.class);when(service.promos()).thenReturn(promo);when(promo.enabled()).thenReturn(true);when(promo.typeLabel(anyLong())).thenReturn("promo");when(promo.featuredLabel(anyLong())).thenReturn("stone");var gift=mock(ShopWelcomeGift.class);when(plugin.welcomeGift()).thenReturn(gift);when(gift.enabled()).thenReturn(true);
            var inv=mock(Inventory.class);when(inv.getSize()).thenReturn(54);var slots=new HashMap<Integer,ItemStack>();doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inv).setItem(anyInt(),any(ItemStack.class));
            try(var bukkit=mockStatic(Bukkit.class);var icons=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{slots.clear();return inv;});var gui=new ShopMarketGui(plugin);gui.open(player,0);
                check(slots.get(10),ShopMarketVisuals.category(ShopCategory.MISC,false),enabled);check(slots.get(45),ShopMarketVisuals.control("market.home.all"),enabled);check(slots.get(48),ShopMarketVisuals.control("market.home.sellall"),enabled);check(slots.get(49),ShopMarketVisuals.control("market.balance.name"),enabled);check(slots.get(50),ShopMarketVisuals.control("market.home.guide"),enabled);check(slots.get(31),ShopMarketVisuals.control("market.home.promo"),enabled);check(slots.get(33),ShopMarketVisuals.control("market.home.gift"),enabled);check(slots.get(17),ShopMarketVisuals.control("trading.gui.close-name"),enabled);check(slots.get(16),ShopMarketVisuals.control("trading.gui.admin-edit"),enabled);
                when(gift.claimed(uuid)).thenReturn(true);gui.open(player,0);check(slots.get(33),ShopMarketVisuals.control("market.home.gift-claimed"),enabled);
                when(service.promos()).thenReturn(null);gui.open(player,ShopCategory.MISC,0);check(slots.get(0),ShopMarketVisuals.category(ShopCategory.ALL,false),enabled);check(slots.get(1),ShopMarketVisuals.category(ShopCategory.MISC,true),enabled);check(slots.get(45),ShopMarketVisuals.control("market.home.back"),enabled);var item=slots.get(18);assertNotNull(item);verify(item.getItemMeta(),never()).setItemModel(any());
                verify(service,never()).quoted(any(),any(),anyLong(),anyBoolean(),anyInt(),anyLong());verify(player,never()).getInventory();for(var icon:icons.constructed())verify(icon.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item,org.bukkit.NamespacedKey key,boolean enabled){assertNotNull(item);if(enabled)verify(item.getItemMeta()).setItemModel(key);else verify(item.getItemMeta(),never()).setItemModel(any());}
}
