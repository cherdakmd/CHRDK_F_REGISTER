package ru.cherdakmd.chrdkregister;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminDonationGuiVisualsTest {
    @Test void journalShowsOriginalStatusesAndFilterWithoutExecutingDecisions() {
        for(boolean enabled:new boolean[]{false,true})for(DonatePulse.State health:DonatePulse.State.values()) {
            var plugin=mock(Plugin.class);var yaml=new YamlConfiguration();yaml.set("core-gui-textures.admin-donations",enabled);when(plugin.getConfig()).thenReturn(yaml);
            var access=mock(AccessManager.class);var config=mock(PluginConfig.class);when(access.config()).thenReturn(config);var store=mock(RegistrationStore.class);when(access.store()).thenReturn(store);
            var rows=new ArrayList<RegistrationStore.DonationRecord>();for(var status:RegistrationStore.DonationStatus.values()){var row=new RegistrationStore.DonationRecord();row.id=rows.size()+1;row.status=status.name();row.amountKopecks=10000;row.days=30;rows.add(row);}when(store.listDonations(isNull(),eq(0))).thenReturn(rows);
            var card=mock(AccessManager.DonateHealthCard.class);when(card.ready()).thenReturn(true);when(card.state()).thenReturn(health);when(card.serviceName()).thenReturn("test");when(card.serviceKey()).thenReturn("other");when(access.donateHealthCards()).thenReturn(List.of(card));
            var player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.hasPermission("chrdkregister.admin")).thenReturn(true);
            var messages=Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger());var inv=mock(Inventory.class);var holder=new AtomicReference<InventoryHolder>();when(inv.getHolder()).thenAnswer(c->holder.get());var slots=new HashMap<Integer,ItemStack>();
            doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inv).setItem(anyInt(),any(ItemStack.class));
            try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{holder.set(c.getArgument(0));slots.clear();return inv;});var gui=new AdminDonationsGuiService(access,messages,plugin);gui.openMenu(player);
                check(slots.get(0),AdminDonationVisuals.control(AdminDonationVisuals.Control.PREV),enabled);check(slots.get(1),AdminDonationVisuals.filter(AdminDonationsGuiService.Filter.ALL),enabled);check(slots.get(2),AdminDonationVisuals.control(AdminDonationVisuals.Control.REFRESH),enabled);
                check(slots.get(4),AdminDonationVisuals.control(AdminDonationVisuals.health(true,health==DonatePulse.State.FLAKY,health==DonatePulse.State.NO_CONTACT)),enabled);check(slots.get(8),AdminDonationVisuals.control(AdminDonationVisuals.Control.NEXT),enabled);
                for(int i=0;i<rows.size();i++)check(slots.get(9+i),AdminDonationVisuals.status(RegistrationStore.DonationStatus.parse(rows.get(i).status)),enabled);
                var event=mock(InventoryClickEvent.class);when(event.getInventory()).thenReturn(inv);when(event.getClickedInventory()).thenReturn(inv);when(event.getWhoClicked()).thenReturn(player);when(event.getRawSlot()).thenReturn(1);when(event.getClick()).thenReturn(ClickType.LEFT);
                gui.onInventoryClick(event);verify(event).setCancelled(true);check(slots.get(1),AdminDonationVisuals.filter(AdminDonationsGuiService.Filter.PENDING),enabled);verify(player,never()).performCommand(anyString());verify(player,never()).getInventory();
                for(var item:items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item,org.bukkit.NamespacedKey model,boolean enabled){assertNotNull(item);if(enabled)verify(item.getItemMeta()).setItemModel(model);else verify(item.getItemMeta(),never()).setItemModel(any());}
}
