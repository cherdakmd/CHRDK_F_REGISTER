package ru.cherdakmd.chrdkenchants;

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
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EnchantCatalogGuiVisualsTest {
    @Test void pagesFiltersAndEmptyResultsUseModelsWithoutGivingItems() throws Exception {
        for(boolean enabled:new boolean[]{false,true}) {
            var plugin=mock(EnchantsPlugin.class);var yaml=new YamlConfiguration();yaml.set("textures.catalog-menu",enabled);when(plugin.getConfig()).thenReturn(yaml);
            when(plugin.config()).thenReturn(new EnchantsConfig(true,30,15,true,35,3,1,true,10,EnchantRegistry.all()));
            when(plugin.messages()).thenReturn(Messages.from(java.nio.file.Files.newInputStream(java.nio.file.Path.of("src/main/resources/messages.yml")),null,Logger.getAnonymousLogger()));
            var api=mock(ChrdkRegisterApi.class);when(plugin.coreApi()).thenReturn(api);var player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.isOnline()).thenReturn(true);when(player.hasPermission("chrdkenchants.use")).thenReturn(true);when(api.isAuthenticated(player)).thenReturn(true);
            var inv=mock(Inventory.class);var slots=new HashMap<Integer,ItemStack>();doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inv).setItem(anyInt(),any(ItemStack.class));
            try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{slots.clear();return inv;});var gui=new EnchantGui(plugin);
                var open=EnchantGui.class.getDeclaredMethod("open",Player.class,EnchantCatalog.Query.class,int.class);open.setAccessible(true);
                var query=EnchantCatalog.Query.all().sort(EnchantCatalog.Sort.ID);var selected=EnchantCatalog.select(query,Map.of(),Set.of());
                open.invoke(gui,player,query,0);for(int i=0;i<45;i++)check(slots.get(i),EnchantCatalogVisuals.entry(selected.get(i)),enabled);check(slots.get(53),EnchantCatalogVisuals.control("next"),enabled);check(slots.get(46),EnchantCatalogVisuals.group(null),enabled);check(slots.get(47),EnchantCatalogVisuals.rarity(null),enabled);check(slots.get(48),EnchantCatalogVisuals.polarity(null),enabled);check(slots.get(49),EnchantCatalogVisuals.control("result"),enabled);check(slots.get(50),EnchantCatalogVisuals.hand(false),enabled);check(slots.get(51),EnchantCatalogVisuals.sort(EnchantCatalog.Sort.ID),enabled);check(slots.get(52),EnchantCatalogVisuals.control("clear"),enabled);
                open.invoke(gui,player,query,1);check(slots.get(45),EnchantCatalogVisuals.control("prev"),enabled);for(int i=45;i<selected.size();i++)check(slots.get(i-45),EnchantCatalogVisuals.entry(selected.get(i)),enabled);
                var filtered=query.group(ItemGroup.SWORD).rarity(EnchantDef.Rarity.RARE).polarity(EnchantDef.Polarity.CURSE).sort(EnchantCatalog.Sort.RARITY);open.invoke(gui,player,filtered,0);check(slots.get(46),EnchantCatalogVisuals.group(ItemGroup.SWORD),enabled);check(slots.get(47),EnchantCatalogVisuals.rarity(EnchantDef.Rarity.RARE),enabled);check(slots.get(48),EnchantCatalogVisuals.polarity(EnchantDef.Polarity.CURSE),enabled);check(slots.get(51),EnchantCatalogVisuals.sort(EnchantCatalog.Sort.RARITY),enabled);
                var empty=new EnchantCatalog.Query("no-such-enchantment-xyz",null,null,null,false,EnchantCatalog.Sort.NAME);open.invoke(gui,player,empty,0);check(slots.get(22),EnchantCatalogVisuals.control("empty"),enabled);
                verify(player,never()).getInventory();verify(player,never()).performCommand(anyString());for(var item:items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item,org.bukkit.NamespacedKey model,boolean enabled){assertNotNull(item);if(enabled)verify(item.getItemMeta()).setItemModel(model);else verify(item.getItemMeta(),never()).setItemModel(any());}
}
