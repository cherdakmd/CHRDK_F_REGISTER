package ru.cherdakmd.chrdkenchants;

import java.util.*;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EnchantHubGuiVisualsTest {
    @Test void screensKeepRealItemSnapshotsAndUseOptInModels() throws Exception {
        for(boolean enabled:new boolean[]{false,true}) {
            var plugin=mock(EnchantsPlugin.class);var config=new YamlConfiguration();config.set("textures.hub-menu",enabled);when(plugin.getConfig()).thenReturn(config);
            when(plugin.messages()).thenReturn(Messages.from(java.nio.file.Files.newInputStream(java.nio.file.Path.of("src/main/resources/messages.yml")),null,Logger.getAnonymousLogger()));
            var player=mock(Player.class);var world=mock(World.class);var playerInv=mock(PlayerInventory.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(world.getUID()).thenReturn(UUID.randomUUID());when(player.getWorld()).thenReturn(world);when(player.getInventory()).thenReturn(playerInv);when(player.hasPermission("chrdkenchants.admin")).thenReturn(true);
            var hand=mock(ItemStack.class);var off=mock(ItemStack.class);var handSnapshot=mock(ItemStack.class);var offSnapshot=mock(ItemStack.class);var handShown=mock(ItemStack.class);var offShown=mock(ItemStack.class);
            when(playerInv.getItemInMainHand()).thenReturn(hand);when(playerInv.getItemInOffHand()).thenReturn(off);when(hand.clone()).thenReturn(handSnapshot);when(off.clone()).thenReturn(offSnapshot);when(handSnapshot.clone()).thenReturn(handShown);when(offSnapshot.clone()).thenReturn(offShown);
            var inv=mock(Inventory.class);var slots=new HashMap<Integer,ItemStack>();doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inv).setItem(anyInt(),any(ItemStack.class));
            try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{slots.clear();return inv;});var hub=new EnchantHub(plugin);
                var show=EnchantHub.class.getDeclaredMethod("show",Player.class,EnchantHub.Screen.class,Map.class);show.setAccessible(true);
                show.invoke(hub,player,EnchantHub.Screen.MAIN,Map.of());for(var button:EnchantHub.buttons())check(slots.get(button.slot()),button.key(),enabled);check(slots.get(34),"admin",enabled);check(slots.get(49),"close",enabled);
                show.invoke(hub,player,EnchantHub.Screen.GUARD,Map.of());check(slots.get(10),"guard-auto",enabled);check(slots.get(11),"guard-briar_guard",enabled);check(slots.get(12),"guard-faltering_guard",enabled);check(slots.get(13),"guard-signal_guard",enabled);check(slots.get(14),"guard-pve_anchor",enabled);check(slots.get(49),"back",enabled);
                show.invoke(hub,player,EnchantHub.Screen.CLEANSE,Map.of(10,"curse"));check(slots.get(10),"cleanse-choice",enabled);check(slots.get(49),"back",enabled);
                show.invoke(hub,player,EnchantHub.Screen.CONFIRM_CLEANSE,Map.of(0,"curse"));check(slots.get(22),"cleanse-effect",enabled);check(slots.get(30),"confirm",enabled);
                show.invoke(hub,player,EnchantHub.Screen.CONFIRM_COMBINE,Map.of());assertSame(handShown,slots.get(20));assertSame(offShown,slots.get(24));check(slots.get(22),"combine-effect",enabled);check(slots.get(30),"confirm",enabled);
                verify(hand,never()).getItemMeta();verify(off,never()).getItemMeta();verify(handShown,never()).getItemMeta();verify(offShown,never()).getItemMeta();verify(player,never()).performCommand(anyString());
                for(var item:items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item,String id,boolean enabled){assertNotNull(item);if(enabled)verify(item.getItemMeta()).setItemModel(EnchantHubVisuals.key(id));else verify(item.getItemMeta(),never()).setItemModel(any());}
}
