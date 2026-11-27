package ru.cherdakmd.chrdkenchants;

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

class BookWorkshopGuiVisualsTest {
    @Test void realInputsStayUntouchedAndPreparationAndConfirmationRemainSeparate() throws Exception {
        for(boolean enabled:new boolean[]{false,true}) {
            var plugin=mock(EnchantsPlugin.class);var config=new YamlConfiguration();config.set("textures.workshop-menu",enabled);when(plugin.getConfig()).thenReturn(config);when(plugin.isEnabled()).thenReturn(true);
            when(plugin.messages()).thenReturn(Messages.from(java.nio.file.Files.newInputStream(java.nio.file.Path.of("src/main/resources/messages.yml")),null,Logger.getAnonymousLogger()));
            var workshop=mock(EnchantWorkshop.class);when(plugin.workshop()).thenReturn(workshop);when(workshop.allowed(any(),anyString())).thenReturn(true);when(workshop.permitted(any(),anyString())).thenReturn(true);
            var player=mock(Player.class);var world=mock(World.class);var playerInv=mock(PlayerInventory.class);var view=mock(InventoryView.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(world.getUID()).thenReturn(UUID.randomUUID());when(player.getWorld()).thenReturn(world);when(player.getInventory()).thenReturn(playerInv);when(player.isOnline()).thenReturn(true);when(player.getOpenInventory()).thenReturn(view);when(playerInv.getStorageContents()).thenReturn(new ItemStack[0]);
            var inv=mock(Inventory.class);var holder=new AtomicReference<InventoryHolder>();var slots=new HashMap<Integer,ItemStack>();when(inv.getHolder()).thenAnswer(c->holder.get());when(inv.getItem(anyInt())).thenAnswer(c->slots.get(c.getArgument(0)));when(view.getTopInventory()).thenReturn(inv);
            doAnswer(c->{int i=c.getArgument(0);ItemStack item=c.getArgument(1);if(item==null)slots.remove(i);else slots.put(i,item);return null;}).when(inv).setItem(anyInt(),nullable(ItemStack.class));
            var input1=mock(ItemStack.class);var input2=mock(ItemStack.class);when(input1.getAmount()).thenReturn(1);when(input1.getType()).thenReturn(Material.DIAMOND_SWORD);when(input1.clone()).thenReturn(input1);
            try(var bukkit=mockStatic(Bukkit.class);var icons=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{slots.clear();holder.set(c.getArgument(0));return inv;});var gui=new BookWorkshopGui(plugin,item -> item == input1 ? Map.of("crippling_edge",1) : null);
                var holderClass=Class.forName("ru.cherdakmd.chrdkenchants.BookWorkshopGui$Holder");var render=BookWorkshopGui.class.getDeclaredMethod("render",holderClass);render.setAccessible(true);
                gui.open(player,BookWorkshopGui.Mode.CLEANSE);check(slots.get(13),"cost_cleanse",enabled);
                gui.open(player,BookWorkshopGui.Mode.COMBINE);check(slots.get(1),"left",enabled);check(slots.get(4),"instructions",enabled);check(slots.get(7),"right",enabled);check(slots.get(13),"cost_combine",enabled);check(slots.get(22),"combine",enabled);check(slots.get(49),"prepare",enabled);check(slots.get(50),"close",enabled);
                slots.put(10,input1);slots.put(16,input2);render.invoke(gui,holder.get());assertSame(input1,slots.get(10));assertSame(input2,slots.get(16));verify(input1,never()).getItemMeta();verify(input2,never()).getItemMeta();
                gui.open(player,BookWorkshopGui.Mode.SCRIBE);slots.put(10,input1);render.invoke(gui,holder.get());assertSame(input1,slots.get(10));check(slots.get(13),"cost_scribe",enabled);check(slots.get(27),"choice",enabled);
                var event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);when(event.getClick()).thenReturn(ClickType.LEFT);when(event.getRawSlot()).thenReturn(27);gui.click(event);check(slots.get(27),"selected",enabled);
                when(event.getRawSlot()).thenReturn(49);gui.click(event);check(slots.get(49),"confirm",enabled);verify(workshop,never()).applyGui(any(),anyString(),any(),any(),anyInt(),anyInt());
                when(playerInv.getStorageContents()).thenReturn(new ItemStack[]{mock(ItemStack.class)});gui.click(event);check(slots.get(49),"prepare",enabled);verify(workshop,never()).applyGui(any(),anyString(),any(),any(),anyInt(),anyInt());assertSame(input1,slots.get(10));
                for(var icon:icons.constructed())verify(icon.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item,String id,boolean enabled){assertNotNull(item);if(enabled)verify(item.getItemMeta()).setItemModel(BookWorkshopVisuals.key(id));else verify(item.getItemMeta(),never()).setItemModel(any());}
}
