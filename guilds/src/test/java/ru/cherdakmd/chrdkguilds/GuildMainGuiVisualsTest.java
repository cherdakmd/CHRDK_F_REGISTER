package ru.cherdakmd.chrdkguilds;

import java.util.HashMap;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuildMainGuiVisualsTest {
    @Test void ownedAndEmptyMenusKeepPlayerHeadsAndDoNotMutateGuild() {
        for(boolean enabled:new boolean[]{false,true}) {
            var plugin=mock(GuildsPlugin.class); var yaml=new YamlConfiguration();yaml.set("textures.guild-menu",enabled);when(plugin.getConfig()).thenReturn(yaml);when(plugin.config()).thenReturn(GuildConfig.defaults());
            when(plugin.messages()).thenReturn(Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger()));
            var service=mock(GuildService.class);when(plugin.service()).thenReturn(service);var store=mock(GuildStore.class);when(plugin.store()).thenReturn(store);
            var styles=mock(GuildStyleController.class);when(plugin.styles()).thenReturn(styles);
            var player=mock(Player.class);var id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);when(player.getName()).thenReturn("Alice");when(plugin.visible(player)).thenReturn(true);
            var guild=new Guild("clan","Alpha","AA",id,1000);when(styles.name(guild)).thenReturn("Alpha");
            var sanction=mock(GuildSanction.class);when(store.sanction(id)).thenReturn(sanction);
            var inv=mock(Inventory.class);var slots=new HashMap<Integer,ItemStack>();doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inv).setItem(anyInt(),any(ItemStack.class));
            var offline=mock(OfflinePlayer.class);when(offline.getName()).thenReturn("Alice");
            try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{slots.clear();return inv;});
                bukkit.when(()->Bukkit.getOfflinePlayer(id)).thenReturn(offline);
                var gui=new GuildsGui(plugin);gui.open(player);
                check(slots.get(4),GuildMainVisuals.Control.INFO_NONE,enabled);check(slots.get(7),GuildMainVisuals.Control.RECRUIT_LIST,enabled);
                check(slots.get(52),GuildMainVisuals.Control.CLAIMS,enabled);check(slots.get(49),GuildMainVisuals.Control.CLOSE,enabled);
                when(service.guildOf(id)).thenReturn(guild);gui.open(player);
                check(slots.get(4),GuildMainVisuals.Control.INFO_BATTLE,enabled);check(slots.get(8),GuildMainVisuals.Control.RECRUIT_CLOSED,enabled);
                check(slots.get(46),GuildMainVisuals.Control.TREASURY,enabled);check(slots.get(47),GuildMainVisuals.Control.UPGRADE_AVAILABLE,enabled);
                check(slots.get(48),GuildMainVisuals.Control.WAR_NONE,enabled);check(slots.get(53),GuildMainVisuals.Control.DUES,enabled);check(slots.get(50),GuildMainVisuals.Control.MODE_BATTLE,enabled);
                assertNotNull(slots.get(10));verify(slots.get(10).getItemMeta(),never()).setItemModel(any());
                guild.recruitment(true);guild.mode(GuildMode.PEACEFUL,1001);gui.open(player);
                check(slots.get(4),GuildMainVisuals.Control.INFO_PEACEFUL,enabled);check(slots.get(8),GuildMainVisuals.Control.RECRUIT_OPEN,enabled);check(slots.get(50),GuildMainVisuals.Control.MODE_PEACEFUL,enabled);
                verify(service,never()).upgrade(any());verify(player,never()).getInventory();
                for(var item:items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item,GuildMainVisuals.Control control,boolean enabled) { assertNotNull(item);if(enabled)verify(item.getItemMeta()).setItemModel(control.model());else verify(item.getItemMeta(),never()).setItemModel(any()); }
}
