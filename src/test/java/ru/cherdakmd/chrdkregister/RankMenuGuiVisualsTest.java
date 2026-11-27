package ru.cherdakmd.chrdkregister;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RankMenuGuiVisualsTest {
    @Test void cardsFollowOwnershipAndDoNotGrantOrChangePrices() {
        for(boolean enabled:new boolean[]{false,true})for(int current:new int[]{0,3}) {
            var plugin=mock(RegisterPlugin.class);var yaml=new YamlConfiguration();yaml.set("core-gui-textures.rank-menu",enabled);when(plugin.getConfig()).thenReturn(yaml);
            var access=mock(AccessManager.class);var config=mock(PluginConfig.class);when(access.config()).thenReturn(config);when(config.ranks()).thenReturn(RankSettings.defaults());
            var store=mock(RegistrationStore.class);when(access.store()).thenReturn(store);when(store.rankLevel(eq("Alice"),anyLong())).thenReturn(current);
            var shop=mock(ShopService.class);when(plugin.shop()).thenReturn(shop);when(shop.catalog()).thenReturn(ShopCatalog.empty(true));
            var player=mock(Player.class);var id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);when(player.getName()).thenReturn("Alice");
            var messages=Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger());var gui=new RankMenuService(plugin,access,messages);
            var inv=mock(Inventory.class);var holder=new AtomicReference<InventoryHolder>();when(inv.getHolder()).thenAnswer(c->holder.get());var slots=new HashMap<Integer,ItemStack>();
            doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inv).setItem(anyInt(),any(ItemStack.class));
            var view=mock(InventoryView.class);when(view.getTopInventory()).thenReturn(inv);
            try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(45),any(Component.class))).thenAnswer(c->{holder.set(c.getArgument(0));return inv;});gui.open(player);
                for(int level=1;level<=5;level++)check(slots.get(9+level),RankMenuVisuals.card(level,current),enabled);
                check(slots.get(29),RankMenuVisuals.Button.MY.model(),enabled);check(slots.get(31),RankMenuVisuals.Button.TOP.model(),enabled);check(slots.get(33),RankMenuVisuals.Button.RULES.model(),enabled);check(slots.get(40),RankMenuVisuals.Button.CLOSE.model(),enabled);
                var event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);when(event.getRawSlot()).thenReturn(14);when(event.getClick()).thenReturn(ClickType.LEFT);
                gui.onClick(event);verify(event).setCancelled(true);verify(player).closeInventory();verify(player,never()).getInventory();
                for(var item:items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item,org.bukkit.NamespacedKey key,boolean enabled){assertNotNull(item);if(enabled)verify(item.getItemMeta()).setItemModel(key);else verify(item.getItemMeta(),never()).setItemModel(any());}
}
