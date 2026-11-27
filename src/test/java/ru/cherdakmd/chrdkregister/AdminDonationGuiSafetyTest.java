package ru.cherdakmd.chrdkregister;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminDonationGuiSafetyTest {
    @Test void displayedDonationIsVerifiedAgainstChangesAndCurrentPermissions() {
        for(String scenario:List.of("reordered","reject","changed","missing","duplicate","revoked","other","number","historical")) {
            var access=mock(AccessManager.class);var config=mock(PluginConfig.class);when(access.config()).thenReturn(config);when(access.donateHealthCards()).thenReturn(List.of());
            var store=mock(RegistrationStore.class);when(access.store()).thenReturn(store);
            var first=record(101,"PENDING_REVIEW");var second=record(102,"PENDING_AMOUNT");if(scenario.equals("historical"))first.status="GRANTED";
            var rows=new AtomicReference<List<RegistrationStore.DonationRecord>>(List.of(first,second));when(store.listDonations(isNull(),eq(0))).thenAnswer(c->rows.get());
            var messages=Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger());var plugin=mock(Plugin.class);
            var player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.hasPermission("chrdkregister.admin")).thenReturn(true);
            var inv=mock(Inventory.class);var holder=new AtomicReference<InventoryHolder>();when(inv.getHolder()).thenAnswer(c->holder.get());var view=mock(InventoryView.class);when(view.getTopInventory()).thenReturn(inv);
            var scheduler=mock(BukkitScheduler.class);when(scheduler.runTaskLater(eq(plugin),any(Runnable.class),eq(4L))).thenReturn(mock(BukkitTask.class));
            try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{holder.set(c.getArgument(0));return inv;});
                var gui=new AdminDonationsGuiService(access,messages,plugin);gui.openMenu(player);assertNotNull(holder.get());
                var actor=player;
                if(scenario.equals("other")){actor=mock(Player.class);when(actor.getUniqueId()).thenReturn(UUID.randomUUID());when(actor.hasPermission("chrdkregister.admin")).thenReturn(true);}
                if(scenario.equals("revoked"))when(player.hasPermission("chrdkregister.admin")).thenReturn(false);
                if(scenario.equals("reordered"))rows.set(List.of(second,first));
                if(scenario.equals("changed"))first.amountKopecks++;
                if(scenario.equals("missing"))rows.set(List.of(second));
                if(scenario.equals("duplicate"))rows.set(List.of(first,first));
                var event=mock(InventoryClickEvent.class);when(event.getInventory()).thenReturn(inv);when(event.getClickedInventory()).thenReturn(inv);when(event.getWhoClicked()).thenReturn(actor);when(event.getView()).thenReturn(view);when(event.getRawSlot()).thenReturn(9);
                when(event.getClick()).thenReturn(scenario.equals("reject")?ClickType.RIGHT:scenario.equals("number")?ClickType.NUMBER_KEY:ClickType.LEFT);
                gui.onInventoryClick(event);verify(event).setCancelled(true);
                if(scenario.equals("reordered")){verify(player).performCommand("pass approve 101");verify(player,never()).performCommand("pass approve 102");}
                else if(scenario.equals("reject"))verify(player).performCommand("pass reject 101");
                else {verify(player,never()).performCommand(startsWith("pass approve"));verify(player,never()).performCommand(startsWith("pass reject"));}
                if(scenario.equals("other"))verify(actor).closeInventory();
                if(scenario.equals("revoked"))verify(player).closeInventory();
                verify(player,never()).getInventory();for(var item:items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static RegistrationStore.DonationRecord record(long id,String status){var r=new RegistrationStore.DonationRecord();r.id=id;r.status=status;r.amountKopecks=15_000;r.donorName="Alice";r.days=30;return r;}
}
