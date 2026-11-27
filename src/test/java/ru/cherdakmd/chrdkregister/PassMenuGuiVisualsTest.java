package ru.cherdakmd.chrdkregister;

import java.util.HashMap;
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

class PassMenuGuiVisualsTest {
    @Test void actualLayoutsAndCommandsDoNotChangeWithModels() {
        for(boolean enabled:new boolean[]{false,true})for(boolean authenticated:new boolean[]{false,true})for(boolean admin:new boolean[]{false,true}) {
            var access=mock(AccessManager.class);var config=mock(PluginConfig.class);when(access.config()).thenReturn(config);when(config.passGuiMenu()).thenReturn(true);
            var player=mock(Player.class);when(player.hasPermission("chrdkregister.admin")).thenReturn(admin);when(access.getStage(player)).thenReturn(authenticated?AccessManager.PlayerStage.AUTHENTICATED:AccessManager.PlayerStage.NO_PASS);
            var messages=Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger());var gui=new PassMenuService(access,messages);gui.setVisualModels(()->enabled);
            var inventory=mock(Inventory.class);var holder=new AtomicReference<InventoryHolder>();when(inventory.getHolder()).thenAnswer(c->holder.get());var slots=new HashMap<Integer,ItemStack>();
            doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inventory).setItem(anyInt(),any(ItemStack.class));
            try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(27),any(Component.class))).thenAnswer(c->{holder.set(c.getArgument(0));return inventory;});
                gui.openMenu(player);var layout=PassMenuService.layout(authenticated,admin);assertEquals(layout.size(),slots.size());
                for(var entry:layout){var meta=slots.get(entry.slot()).getItemMeta();if(enabled)verify(meta).setItemModel(PassMenuVisuals.model(entry.action()));else verify(meta,never()).setItemModel(any());}
                assertEquals(authenticated,layout.stream().anyMatch(e->e.action()==PassMenuService.Action.TWO_FACTOR));
                assertEquals(authenticated&&admin,layout.stream().anyMatch(e->e.action()==PassMenuService.Action.STATS));
                int slot=layout.stream().filter(e->e.action()==PassMenuService.Action.STATUS).findFirst().orElseThrow().slot();var event=mock(InventoryClickEvent.class);
                when(event.getInventory()).thenReturn(inventory);when(event.getClickedInventory()).thenReturn(inventory);when(event.getWhoClicked()).thenReturn(player);when(event.getRawSlot()).thenReturn(slot);
                gui.onInventoryClick(event);verify(event).setCancelled(true);verify(player).performCommand("pass");verify(player,never()).getInventory();
                for(var item:items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
}
