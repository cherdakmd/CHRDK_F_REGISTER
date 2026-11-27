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
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminPanelGuiSafetyTest {
    @Test void listActionsStayBoundToDisplayedRecords() {
        try(var f=new Fixture()) {
            f.ledger.addModerator("alice","admin",1);f.ledger.addModerator("bob","admin",1);
            f.open(AdminPanelGui.Page.MODERATORS," ");f.ledger.addModerator("aaron","admin",2);
            f.click(10,ClickType.LEFT);verify(f.service).removeModerator(f.player,"alice");
            f.open(AdminPanelGui.Page.MODERATORS,"");f.ledger.removeModerator("aaron","admin",3);f.ledger.addModerator("aaron","admin",4);
            f.click(10,ClickType.LEFT);verify(f.service,never()).removeModerator(f.player,"aaron");
            var warn=f.ledger.issueWarn("alice","admin","first",10);f.open(AdminPanelGui.Page.PROFILE,"alice");f.ledger.issueWarn("alice","admin","second",20);
            f.click(10,ClickType.LEFT);verify(f.service).removeWarn(f.player,warn.id);
            f.open(AdminPanelGui.Page.PROFILE,"alice");f.ledger.removeWarn(2,"other",30);
            f.click(10,ClickType.LEFT);verify(f.service,never()).removeWarn(f.player,2);
            f.click(11,ClickType.NUMBER_KEY);verify(f.service,times(1)).removeWarn(any(),anyLong());
            when(f.event.isCancelled()).thenReturn(true);f.click(11,ClickType.LEFT);verify(f.service,times(1)).removeWarn(any(),anyLong());
            when(f.event.isCancelled()).thenReturn(false);when(f.service.isStaff(f.player)).thenReturn(false);f.click(11,ClickType.LEFT);verify(f.service,times(1)).removeWarn(any(),anyLong());
        }
    }

    @Test void togglesAndMutePresetsRejectStaleState() {
        try(var f=new Fixture()) {
            f.open(AdminPanelGui.Page.PROFILE,"alice");f.ledger.ban("alice","new ban","other",10);f.click(50,ClickType.LEFT);verify(f.service,never()).unban(any(),anyString());verify(f.input,never()).begin(any(Player.class),anyString(),any());
            f.click(50,ClickType.LEFT);verify(f.service).unban(f.player,"alice");
            f.open(AdminPanelGui.Page.PROFILE,"alice");f.ledger.addModerator("alice","other",20);f.click(51,ClickType.LEFT);verify(f.service,never()).addModerator(any(),anyString());verify(f.service,never()).removeModerator(any(),anyString());
            f.open(AdminPanelGui.Page.PUNISH,"alice");var initial=ModerationConfig.DEFAULT;var changed=new ModerationConfig(initial.enabled(),initial.warnLimit(),initial.banReason(),initial.warnsInTab(),initial.warnsPublicInTab(),initial.warnsPublicLimit(),initial.journalLimit(),List.of(99L));when(f.service.config()).thenReturn(changed);
            f.click(10,ClickType.LEFT);verify(f.input,never()).begin(any(Player.class),anyString(),any());
            f.click(10,ClickType.LEFT);verify(f.input).begin(eq(f.player),anyString(),any());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final RegisterPlugin plugin=mock(RegisterPlugin.class);
        final ModerationService service=mock(ModerationService.class);
        final ModerationLedger ledger=new ModerationLedger();
        final GuiChatInput input=mock(GuiChatInput.class);
        final Player player=mock(Player.class);
        final Inventory inventory=mock(Inventory.class);
        final InventoryView view=mock(InventoryView.class);
        final InventoryClickEvent event=mock(InventoryClickEvent.class);
        final AtomicReference<InventoryHolder> holder=new AtomicReference<>();
        final MockedStatic<Bukkit> bukkit;
        final MockedConstruction<ItemStack> items;
        final AdminPanelGui gui;
        Fixture() {
            var store=mock(RegistrationStore.class);var bank=mock(BankStore.class);
            when(plugin.store()).thenReturn(store);when(store.accountNames()).thenReturn(List.of("alice","bob"));when(store.getAccount(anyString())).thenReturn(Optional.empty());
            when(plugin.bankStore()).thenReturn(bank);when(plugin.profileRegistry()).thenReturn(new ProfileRegistry(null));when(plugin.chatInput()).thenReturn(input);
            when(plugin.messages()).thenReturn(Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger()));
            when(service.ledger()).thenReturn(ledger);when(service.config()).thenReturn(ModerationConfig.DEFAULT);when(service.isStaff(player)).thenReturn(true);when(service.isBanned(anyString())).thenAnswer(c->ledger.isBanned(c.getArgument(0)));
            when(service.ipBanReason(anyString())).thenReturn("");when(service.banReason(anyString())).thenReturn("");when(service.warnText(anyString())).thenReturn("");when(service.activeMutes()).thenReturn(List.of());
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getName()).thenReturn("Admin");when(player.isOnline()).thenReturn(true);
            when(inventory.getHolder()).thenAnswer(c->holder.get());when(view.getTopInventory()).thenReturn(inventory);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);
            bukkit=mockStatic(Bukkit.class);bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{holder.set(c.getArgument(0));return inventory;});
            items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)));
            gui=new AdminPanelGui(plugin,service);
        }
        void open(AdminPanelGui.Page page,String target) {gui.open(player,page,target);}
        void click(int slot,ClickType click) {when(event.getRawSlot()).thenReturn(slot);when(event.getClick()).thenReturn(click);gui.onClick(event);}
        public void close(){items.close();bukkit.close();}
    }
}
