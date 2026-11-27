package ru.cherdakmd.chrdkregister;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfileMenuGuiVisualsTest {
    @TempDir Path dir;
    @Test void ownAndOtherProfilesKeepHeadsAddonSectionsAndPrivateDataBoundaries() throws Exception {
        for(boolean enabled:new boolean[]{false,true})for(boolean self:new boolean[]{false,true}) {
            var plugin=mock(RegisterPlugin.class);when(plugin.getDataFolder()).thenReturn(dir.toFile());when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());var config=new YamlConfiguration();config.set("core-gui-textures.profile-menu",enabled);when(plugin.getConfig()).thenReturn(config);
            var messages=Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger());when(plugin.messages()).thenReturn(messages);
            var bank=mock(BankStore.class);var access=mock(AccessManager.class);var registry=mock(ProfileRegistry.class);
            var addon=new ChrdkRegisterApi.ProfileSection("Addon",Material.DIAMOND,List.of(Component.text("Data")));when(registry.sections(any())).thenReturn(List.of(addon));
            var player=mock(Player.class);var id=UUID.randomUUID();var targetId=self?id:UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);when(player.hasPermission("chrdkregister.labels")).thenReturn(true);
            var inv=mock(Inventory.class);var holder=new AtomicReference<InventoryHolder>();when(inv.getHolder()).thenAnswer(c->holder.get());var slots=new HashMap<Integer,ItemStack>();
            doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inv).setItem(anyInt(),any(ItemStack.class));
            var offline=mock(OfflinePlayer.class);var manager=mock(PluginManager.class);
            try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,ctx)->{
                var meta=ctx.arguments().get(0)==Material.PLAYER_HEAD?mock(SkullMeta.class):mock(ItemMeta.class);when(item.getItemMeta()).thenReturn(meta);
            })) {
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{holder.set(c.getArgument(0));return inv;});
                bukkit.when(()->Bukkit.getOfflinePlayer(targetId)).thenReturn(offline);bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
                var gui=new ProfileService(plugin,access,bank,registry);var target=new ProfileService.Target(targetId,"Alice",true,self,null);
                Method open=ProfileService.class.getDeclaredMethod("open",Player.class,ProfileService.Target.class);open.setAccessible(true);open.invoke(gui,player,target);
                assertNotNull(slots.get(4));verify(slots.get(4).getItemMeta(),never()).setItemModel(any());verify((SkullMeta)slots.get(4).getItemMeta()).setOwningPlayer(offline);
                assertNotNull(slots.get(20));verify(slots.get(20).getItemMeta(),never()).setItemModel(any());check(slots.get(45),ProfileMenuVisuals.Button.CHAT,enabled);check(slots.get(49),ProfileMenuVisuals.Button.REFRESH,enabled);check(slots.get(53),ProfileMenuVisuals.Button.CLOSE,enabled);
                if(self){check(slots.get(47),ProfileMenuVisuals.Button.LABELS,enabled);assertFalse(slots.containsKey(50));verify(bank).balanceMinor(targetId);}
                else{assertFalse(slots.containsKey(47));check(slots.get(50),ProfileMenuVisuals.Button.FRIEND,enabled);verifyNoInteractions(bank);}
                verify(player,never()).getInventory();for(var item:items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item,ProfileMenuVisuals.Button button,boolean enabled){assertNotNull(item);if(enabled)verify(item.getItemMeta()).setItemModel(button.model());else verify(item.getItemMeta(),never()).setItemModel(any());}
}
