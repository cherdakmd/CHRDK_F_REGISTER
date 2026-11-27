package ru.cherdakmd.diamondshop;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopAdminGuiVisualsTest {
    @Test void editorShowsStatesButDoesNotAlterProductOrSaveDraft() throws Exception {
        for(boolean enabled:new boolean[]{false,true}) {
            var plugin=mock(DiamondShopPlugin.class);var yaml=new YamlConfiguration();yaml.set("textures.admin-menu",enabled);when(plugin.getConfig()).thenReturn(yaml);when(plugin.isEnabled()).thenReturn(true);
            when(plugin.messages()).thenReturn(Messages.from(java.nio.file.Files.newInputStream(java.nio.file.Path.of("src/main/resources/messages.yml")),null,Logger.getAnonymousLogger()));
            var service=mock(ShopService.class);var product=new ShopConfig.ShopItem("STONE","misc",1,100,0.5,2);when(plugin.shopService()).thenReturn(service);when(service.config()).thenReturn(new ShopConfig.ShopConfigData(Map.of(),0,0,1,2,List.of(product),0,0,1,1000));var live=ShopOverrides.EMPTY.copy();when(plugin.overrides()).thenReturn(live);
            var player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.isOnline()).thenReturn(true);when(player.hasPermission("chrdkshop.admin")).thenReturn(true);
            var inv=mock(Inventory.class);var view=mock(InventoryView.class);when(view.getTopInventory()).thenReturn(inv);when(player.getOpenInventory()).thenReturn(view);var holder=new AtomicReference<InventoryHolder>();when(inv.getHolder()).thenAnswer(c->holder.get());var slots=new HashMap<Integer,ItemStack>();doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inv).setItem(anyInt(),any(ItemStack.class));doAnswer(c->{slots.clear();return null;}).when(inv).clear();
            var event=mock(InventoryClickEvent.class);when(event.getInventory()).thenReturn(inv);when(event.getView()).thenReturn(view);when(event.getClickedInventory()).thenReturn(inv);when(event.getWhoClicked()).thenReturn(player);when(event.getClick()).thenReturn(ClickType.LEFT);
            try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),anyInt(),any(Component.class))).thenAnswer(c->{holder.set(c.getArgument(0));slots.clear();return inv;});var gui=new ShopAdminGui(plugin,(name,fallback)->Material.matchMaterial(name));gui.open(player,0);
                check(slots.get(17),"close-name",Material.BARRIER,enabled);check(slots.get(49),"save-name",Material.DIAMOND,enabled);verify(slots.get(18).getItemMeta(),never()).setItemModel(any());
                when(event.getRawSlot()).thenReturn(18);gui.onInventoryClick(event);verify(slots.get(10).getItemMeta(),never()).setItemModel(any());
                check(slots.get(11),"price-down-small",Material.RED_CONCRETE,enabled);check(slots.get(12),"price-down",Material.RED_WOOL,enabled);check(slots.get(14),"price-up",Material.LIME_WOOL,enabled);check(slots.get(15),"price-up-small",Material.LIME_CONCRETE,enabled);check(slots.get(19),"stock-down",Material.BLUE_WOOL,enabled);check(slots.get(20),"stock-up",Material.LIGHT_BLUE_WOOL,enabled);check(slots.get(21),"field-sell",Material.LIME_DYE,enabled);check(slots.get(22),"field-category",Material.BOOKSHELF,enabled);check(slots.get(23),"tariff-down",Material.ORANGE_WOOL,enabled);check(slots.get(24),"tariff-up",Material.YELLOW_WOOL,enabled);check(slots.get(16),"remove-name",Material.BARRIER,enabled);check(slots.get(17),"restore-name",Material.WATER_BUCKET,enabled);check(slots.get(18),"back-name",Material.ARROW,enabled);check(slots.get(26),"save-name",Material.DIAMOND,enabled);
                when(event.getRawSlot()).thenReturn(21);gui.onInventoryClick(event);check(slots.get(21),"field-sell",Material.GRAY_DYE,enabled);
                when(event.getRawSlot()).thenReturn(16);gui.onInventoryClick(event);check(slots.get(16),"remove-confirm",Material.TNT,enabled);verify(plugin,never()).applyOverrides(any());assertTrue(live.changes().isEmpty());assertTrue(live.removed().isEmpty());
                for(var item:items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item,String key,Material material,boolean enabled){assertNotNull(item);if(enabled)verify(item.getItemMeta()).setItemModel(ShopAdminVisuals.key("admin.gui."+key,material));else verify(item.getItemMeta(),never()).setItemModel(any());}
}
