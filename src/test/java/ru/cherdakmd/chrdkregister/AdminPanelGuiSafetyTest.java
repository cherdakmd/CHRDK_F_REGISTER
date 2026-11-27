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

    @Test void visualStatesAreOptInAndDoNotAlterHeadsOrActions() {
        for(boolean enabled:new boolean[]{false,true})try(var f=new Fixture(enabled)) {
            f.open(AdminPanelGui.Page.PLAYERS,"");
            f.check(1,AdminPanelVisuals.Icon.MODERATORS,enabled);f.check(2,AdminPanelVisuals.Icon.JOURNAL,enabled);f.check(3,AdminPanelVisuals.Icon.MUTES,enabled);f.check(4,AdminPanelVisuals.Icon.INFO,enabled);f.check(5,AdminPanelVisuals.Icon.HONORS,enabled);f.check(6,AdminPanelVisuals.Icon.FILTER,enabled);f.check(8,AdminPanelVisuals.Icon.REFRESH,enabled);f.check(45,AdminPanelVisuals.Icon.PREV,enabled);f.check(46,AdminPanelVisuals.Icon.PAGES,enabled);f.check(47,AdminPanelVisuals.Icon.NEXT,enabled);f.check(52,AdminPanelVisuals.Icon.BACK,enabled);f.check(53,AdminPanelVisuals.Icon.CLOSE,enabled);f.noModel(0);f.noModel(10);
            f.ledger.issueWarn("alice","admin","a",10);f.ledger.issueWarn("alice","admin","b",20);f.ledger.removeWarn(2,"admin",30);
            f.open(AdminPanelGui.Page.PROFILE,"alice");f.noModel(4);f.check(10,AdminPanelVisuals.Icon.WARN_REMOVED,enabled);f.check(11,AdminPanelVisuals.Icon.WARN_ACTIVE,enabled);f.check(48,AdminPanelVisuals.Icon.WARN,enabled);f.check(49,AdminPanelVisuals.Icon.PUNISH,enabled);f.check(50,AdminPanelVisuals.Icon.BAN,enabled);f.check(51,AdminPanelVisuals.Icon.ROLE_ADD,enabled);
            f.ledger.ban("alice","reason","admin",40);f.ledger.addModerator("alice","admin",40);f.open(AdminPanelGui.Page.PROFILE,"alice");f.check(50,AdminPanelVisuals.Icon.UNBAN,enabled);f.check(51,AdminPanelVisuals.Icon.ROLE_REMOVE,enabled);
            f.open(AdminPanelGui.Page.MODERATORS,"");f.check(10,AdminPanelVisuals.Icon.MODERATOR_ENTRY,enabled);f.check(49,AdminPanelVisuals.Icon.ROLE_ADD,enabled);
            f.open(AdminPanelGui.Page.JOURNAL,"");f.check(10,AdminPanelVisuals.Icon.JOURNAL_ENTRY,enabled);
            var mute=new RegistrationStore.MuteEntry();mute.playerName="alice";mute.until=System.currentTimeMillis()+60000;when(f.service.activeMutes()).thenReturn(List.of(mute));f.open(AdminPanelGui.Page.MUTES,"");f.check(10,AdminPanelVisuals.Icon.MUTE_ENTRY,enabled);
            f.open(AdminPanelGui.Page.PUNISH,"alice");f.check(4,AdminPanelVisuals.Icon.PUNISH_INFO,enabled);f.check(10,AdminPanelVisuals.Icon.MUTE_PRESET,enabled);f.check(14,AdminPanelVisuals.Icon.MUTE_CUSTOM,enabled);f.check(19,AdminPanelVisuals.Icon.UNMUTE,enabled);f.check(20,AdminPanelVisuals.Icon.KICK,enabled);f.check(21,AdminPanelVisuals.Icon.IP_BAN,enabled);f.check(22,AdminPanelVisuals.Icon.IP_UNBAN,enabled);f.check(23,AdminPanelVisuals.Icon.CHAT_HISTORY,enabled);
            when(f.service.chatHistory(anyString(),anyInt())).thenReturn(List.of("hello"));f.open(AdminPanelGui.Page.CHAT_HISTORY,"alice");f.check(10,AdminPanelVisuals.Icon.CHAT_ENTRY,enabled);
            when(f.service.chatHistory(anyString(),anyInt())).thenReturn(List.of());f.open(AdminPanelGui.Page.CHAT_HISTORY,"alice");f.check(AdminPanelGui.ENTRY_SLOTS[AdminPanelGui.ENTRY_SLOTS.length/2],AdminPanelVisuals.Icon.EMPTY,enabled);
            verify(f.service,never()).removeWarn(any(),anyLong());verify(f.service,never()).unban(any(),anyString());verify(f.service,never()).removeModerator(any(),anyString());
            for(var item:f.items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
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
        final Map<Integer,ItemStack> slots=new HashMap<>();
        final MockedStatic<Bukkit> bukkit;
        final MockedConstruction<ItemStack> items;
        final AdminPanelGui gui;
        Fixture() { this(false); }
        Fixture(boolean enabled) {
            var config=new org.bukkit.configuration.file.YamlConfiguration();config.set("core-gui-textures.admin-panel",enabled);when(plugin.getConfig()).thenReturn(config);
            var store=mock(RegistrationStore.class);var bank=mock(BankStore.class);
            when(plugin.store()).thenReturn(store);when(store.accountNames()).thenReturn(List.of("alice","bob"));when(store.getAccount(anyString())).thenReturn(Optional.empty());
            when(plugin.bankStore()).thenReturn(bank);when(plugin.profileRegistry()).thenReturn(new ProfileRegistry(null));when(plugin.chatInput()).thenReturn(input);
            when(plugin.messages()).thenReturn(Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger()));
            when(service.ledger()).thenReturn(ledger);when(service.config()).thenReturn(ModerationConfig.DEFAULT);when(service.isStaff(player)).thenReturn(true);when(service.isBanned(anyString())).thenAnswer(c->ledger.isBanned(c.getArgument(0)));
            when(service.ipBanReason(anyString())).thenReturn("");when(service.banReason(anyString())).thenReturn("");when(service.warnText(anyString())).thenReturn("");when(service.activeMutes()).thenReturn(List.of());
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getName()).thenReturn("Admin");when(player.isOnline()).thenReturn(true);
            doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inventory).setItem(anyInt(),any(ItemStack.class));
            when(inventory.getHolder()).thenAnswer(c->holder.get());when(view.getTopInventory()).thenReturn(inventory);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);
            bukkit=mockStatic(Bukkit.class);bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{holder.set(c.getArgument(0));slots.clear();return inventory;});
            items=mockConstruction(ItemStack.class,(item,ctx)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)));
            gui=new AdminPanelGui(plugin,service);
        }
        void open(AdminPanelGui.Page page,String target) {gui.open(player,page,target);}
        void click(int slot,ClickType click) {when(event.getRawSlot()).thenReturn(slot);when(event.getClick()).thenReturn(click);gui.onClick(event);}
        void check(int slot,AdminPanelVisuals.Icon icon,boolean enabled) {var item=slots.get(slot);org.junit.jupiter.api.Assertions.assertNotNull(item);if(enabled)verify(item.getItemMeta()).setItemModel(AdminPanelVisuals.key(icon));else noModel(slot);}
        void noModel(int slot) {var item=slots.get(slot);org.junit.jupiter.api.Assertions.assertNotNull(item);verify(item.getItemMeta(),never()).setItemModel(any());}
        public void close(){items.close();bukkit.close();}
    }
}
