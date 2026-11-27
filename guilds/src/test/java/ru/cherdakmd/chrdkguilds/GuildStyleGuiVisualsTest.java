package ru.cherdakmd.chrdkguilds;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuildStyleGuiVisualsTest {
    @Test void actualPreviewsShowPersonalAndActiveStatesWithoutChangingChoices() {
        for(boolean enabled:new boolean[]{false,true}) {
            var plugin=mock(GuildsPlugin.class);var yaml=new YamlConfiguration();yaml.set("textures.style-menu",enabled);when(plugin.getConfig()).thenReturn(yaml);when(plugin.config()).thenReturn(GuildConfig.defaults());
            when(plugin.messages()).thenReturn(Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger()));when(plugin.coreApi()).thenReturn(mock(ChrdkRegisterApi.class));
            var service=mock(GuildService.class);when(plugin.service()).thenReturn(service);var styles=mock(GuildStyleController.class);when(plugin.styles()).thenReturn(styles);
            var player=mock(Player.class);var id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);when(player.getName()).thenReturn("Alice");when(player.isOnline()).thenReturn(true);when(player.hasPermission("chrdkguilds.use")).thenReturn(true);when(plugin.visible(player)).thenReturn(true);
            var guild=new Guild("clan","Alpha","AA",id,1000);when(service.guildOf(id)).thenReturn(guild);when(styles.name(guild)).thenReturn("Alpha");when(styles.active(guild,"name")).thenReturn("blue");
            var row=new GuildStyleStore.Row(id,"Alice","clan",0,Map.of("name",new GuildStyleStore.Choice("gold",1)));when(styles.selection(id)).thenReturn(row);
            var inventory=mock(Inventory.class);var slots=new HashMap<Integer,ItemStack>();var holder=new AtomicReference<GuildStyleGui.Holder>();
            doAnswer(c->{slots.clear();return null;}).when(inventory).clear();doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inventory).setItem(anyInt(),any(ItemStack.class));
            try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{holder.set(c.getArgument(0));return inventory;});
                var gui=new GuildStyleGui(plugin);gui.open(player,"name",0);
                check(slots.get(4),GuildStyleVisuals.Control.INFO.model(),enabled);check(slots.get(10),GuildStyleVisuals.category("name",true),enabled);check(slots.get(11),GuildStyleVisuals.category("emblem",false),enabled);
                var names=GuildStyleDefs.options("name");check(slots.get(GuildStyleGui.OPTIONS[names.indexOf("gold")]),GuildStyleVisuals.preset("name","gold",true,false),enabled);
                check(slots.get(GuildStyleGui.OPTIONS[names.indexOf("blue")]),GuildStyleVisuals.preset("name","blue",false,true),enabled);check(slots.get(46),GuildStyleVisuals.Control.OFF.model(),enabled);
                assertEquals(names.size(),holder.get().actions.size());verify(styles,never()).command(any(),any());verify(styles).frame(inventory,guild);
                when(styles.active(guild,"name")).thenReturn("gold");gui.open(player,"name",0);check(slots.get(GuildStyleGui.OPTIONS[names.indexOf("gold")]),GuildStyleVisuals.preset("name","gold",true,true),enabled);
                UUID leader=UUID.randomUUID();var memberGuild=new Guild("clan","Alpha","AA",leader,1000);memberGuild.addMember(id,GuildRank.MEMBER);when(service.guildOf(id)).thenReturn(memberGuild);when(styles.name(memberGuild)).thenReturn("Alpha");
                gui.open(player,"frame",0);assertFalse(holder.get().actions.containsKey(19));assertFalse(slots.containsKey(46));check(slots.get(12),GuildStyleVisuals.category("frame",true),enabled);
                verify(styles,never()).command(any(),any());verify(player,never()).getInventory();for(var item:items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item,org.bukkit.NamespacedKey model,boolean enabled) { assertNotNull(item);if(enabled)verify(item.getItemMeta()).setItemModel(model);else verify(item.getItemMeta(),never()).setItemModel(any()); }
}
