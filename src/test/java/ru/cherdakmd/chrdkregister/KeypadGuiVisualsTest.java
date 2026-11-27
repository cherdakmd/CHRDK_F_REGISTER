package ru.cherdakmd.chrdkregister;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KeypadGuiVisualsTest {
    @Test void staticControlsNeverEncodeKeysOrInputAndExistingActionsRemain() throws Exception {
        for(boolean enabled:new boolean[]{false,true}) {
            var plugin=mock(JavaPlugin.class);var yaml=new YamlConfiguration();yaml.set("core-gui-textures.keypad-controls",enabled);when(plugin.getConfig()).thenReturn(yaml);when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
            var access=mock(AccessManager.class);var config=mock(PluginConfig.class);when(access.config()).thenReturn(config);when(config.buttonLogin()).thenReturn(true);when(config.maxPasswordLength()).thenReturn(16);when(config.buttonLoginSymbols()).thenReturn("!@");
            var player=mock(Player.class);var id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);when(access.getStage(player)).thenReturn(AccessManager.PlayerStage.NEEDS_LOGIN);
            var messages=Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger());var inv=mock(Inventory.class);var holder=new AtomicReference<InventoryHolder>();when(inv.getHolder()).thenAnswer(c->holder.get());
            var slots=new HashMap<Integer,ItemStack>();doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inv).setItem(anyInt(),any(ItemStack.class));
            var view=mock(InventoryView.class);when(view.getTopInventory()).thenReturn(inv);
            try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{holder.set(c.getArgument(0));return inv;});
                var gui=new PasswordKeypadService(plugin,access,messages);assertTrue(gui.openIfEligible(player));
                check(slots.get(45),KeypadVisuals.Control.BACKSPACE,enabled);check(slots.get(46),KeypadVisuals.Control.CLEAR,enabled);check(slots.get(47),KeypadVisuals.Control.PAGE,enabled);check(slots.get(49),KeypadVisuals.Control.PROGRESS,enabled);check(slots.get(51),KeypadVisuals.Control.CANCEL,enabled);check(slots.get(53),KeypadVisuals.Control.CONFIRM,enabled);
                for(int i=0;i<26;i++){assertNotNull(slots.get(i));verify(slots.get(i).getItemMeta(),never()).setItemModel(any());}
                Field opened=PasswordKeypadService.class.getDeclaredField("open");opened.setAccessible(true);var state=((Map<?,?>)opened.get(gui)).get(id);Field layout=state.getClass().getDeclaredField("layout");layout.setAccessible(true);Field bufferField=state.getClass().getDeclaredField("buffer");bufferField.setAccessible(true);var buffer=(KeypadRules.Buffer)bufferField.get(state);
                var before=((Character[])layout.get(state)).clone();assertEquals(new HashSet<>(KeypadRules.pageChars(KeypadRules.Page.LOWER,"!@")),new HashSet<>(Arrays.asList(before).subList(0,26)));
                var event=mock(InventoryClickEvent.class);when(event.getInventory()).thenReturn(inv);when(event.getClickedInventory()).thenReturn(inv);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);when(event.getRawSlot()).thenReturn(0);
                gui.onInventoryClick(event);verify(event).setCancelled(true);assertEquals(1,buffer.size());var after=(Character[])layout.get(state);assertEquals(new HashSet<>(Arrays.asList(before).subList(0,26)),new HashSet<>(Arrays.asList(after).subList(0,26)));
                check(slots.get(49),KeypadVisuals.Control.PROGRESS,enabled);when(event.getRawSlot()).thenReturn(46);gui.onInventoryClick(event);assertEquals(0,buffer.size());
                when(event.getRawSlot()).thenReturn(51);gui.onInventoryClick(event);assertFalse(((Map<?,?>)opened.get(gui)).containsKey(id));verify(player).closeInventory();verify(player,never()).getInventory();
                for(var item:items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item,KeypadVisuals.Control control,boolean enabled){assertNotNull(item);if(enabled)verify(item.getItemMeta()).setItemModel(control.model());else verify(item.getItemMeta(),never()).setItemModel(any());}
}
