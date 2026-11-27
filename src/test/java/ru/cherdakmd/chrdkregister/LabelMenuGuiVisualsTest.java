package ru.cherdakmd.chrdkregister;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LabelMenuGuiVisualsTest {
    @Test void labelsAndPersonalStylesKeepStatesPaidChecksAndActions() {
        for(boolean enabled:new boolean[]{false,true}) {
            var plugin=mock(RegisterPlugin.class);var yaml=new YamlConfiguration();yaml.set("core-gui-textures.labels-menu",enabled);when(plugin.getConfig()).thenReturn(yaml);
            when(plugin.messages()).thenReturn(Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger()));
            var prefs=mock(LabelPreferences.class);when(prefs.visible(anyString(),any())).thenReturn(true);var questsVisible=new AtomicBoolean(false);
            when(prefs.visible("Alice",LabelPreferences.Label.QUESTS)).thenAnswer(c->questsVisible.get());when(prefs.toggle("Alice",LabelPreferences.Label.QUESTS)).thenAnswer(c->{questsVisible.set(!questsVisible.get());return questsVisible.get();});
            when(prefs.color("Alice")).thenReturn("");var snapshot=new LabelPreferences.Snapshot(0,List.of(),"");when(prefs.presets("Alice")).thenReturn(Map.of("p1",snapshot));
            var rank=LabelPreferences.Label.RANK;var guild=LabelPreferences.Label.GUILDS;var classes=LabelPreferences.Label.CLASSES;
            var a=new LabelStyleService.Decoration(rank,"rank");var b=new LabelStyleService.Decoration(guild,"guild");var c=new LabelStyleService.Decoration(classes,"classes");
            var selected=new LabelStyleService.Selection(List.of(a),List.of(b),2,List.of(a,b,c));var style=mock(LabelStyleService.class);when(style.selection("Alice")).thenReturn(selected);
            when(style.assignments("Alice")).thenReturn(List.of("rank","","","","","","",""));var chat=mock(ChatService.class);when(chat.labelStyles()).thenReturn(style);when(chat.decoratedName("Alice")).thenReturn("Alice");when(plugin.chatService()).thenReturn(chat);
            when(plugin.purchasedProducts("Alice")).thenReturn(Set.of());when(plugin.effectiveRankLevel("Alice")).thenReturn(0);
            var color=new LabelPalette.Color("custom","Custom","<red>%name%</red>",0,"");var paid=new LabelPalette.Color("premium","Paid","<blue>%name%</blue>",0,"paid-color");var palette=LabelPalette.of(true,List.of(color,paid));
            var main=new LabelsMenuService(plugin,prefs);var options=new LabelsStyleMenuService(plugin,prefs);main.setStyleMenu(options);when(plugin.labelsMenu()).thenReturn(main);options.applySettings(palette,LabelLimits.DEFAULT);main.applySettings(palette,LabelLimits.DEFAULT);
            var player=mock(Player.class);when(player.getName()).thenReturn("Alice");when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.hasPermission("chrdkregister.labels")).thenReturn(true);
            var inventory=mock(Inventory.class);var holder=new AtomicReference<InventoryHolder>();when(inventory.getHolder()).thenAnswer(c->holder.get());var slots=new HashMap<Integer,ItemStack>();
            doAnswer(c->{slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inventory).setItem(anyInt(),any(ItemStack.class));var view=mock(InventoryView.class);when(view.getTopInventory()).thenReturn(inventory);when(player.getOpenInventory()).thenReturn(view);
            var manager=mock(PluginManager.class);
            try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,ctx)->{
                var meta=ctx.arguments().get(0)==Material.PLAYER_HEAD?mock(SkullMeta.class):mock(ItemMeta.class);when(item.getItemMeta()).thenReturn(meta);
            })) {
                bukkit.when(Bukkit::getPluginManager).thenReturn(manager);bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(c->{holder.set(c.getArgument(0));slots.clear();return inventory;});
                main.open(player);check(slots.get(4),null,false);check(slots.get(8),LabelMenuVisuals.control(LabelMenuVisuals.Control.PREVIEW),enabled);
                check(slots.get(19),LabelMenuVisuals.place(LabelMenuVisuals.Place.ASSIGNED),enabled);check(slots.get(20),LabelMenuVisuals.place(LabelMenuVisuals.Place.EMPTY),enabled);check(slots.get(21),LabelMenuVisuals.place(LabelMenuVisuals.Place.LOCKED),enabled);
                check(slots.get(LabelsMenuService.slotOf(rank)),LabelMenuVisuals.label(rank,LabelMenuVisuals.State.ON),enabled);
                check(slots.get(LabelsMenuService.slotOf(guild)),LabelMenuVisuals.label(guild,LabelMenuVisuals.State.WAITING),enabled);
                check(slots.get(LabelsMenuService.slotOf(classes)),LabelMenuVisuals.label(classes,LabelMenuVisuals.State.UNASSIGNED),enabled);
                check(slots.get(LabelsMenuService.slotOf(LabelPreferences.Label.QUESTS)),LabelMenuVisuals.label(LabelPreferences.Label.QUESTS,LabelMenuVisuals.State.OFF),enabled);
                var event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);when(event.getClick()).thenReturn(ClickType.LEFT);when(event.getRawSlot()).thenReturn(LabelsMenuService.slotOf(LabelPreferences.Label.QUESTS));
                main.onClick(event);verify(event).setCancelled(true);verify(prefs).toggle("Alice",LabelPreferences.Label.QUESTS);
                check(slots.get(LabelsMenuService.slotOf(LabelPreferences.Label.QUESTS)),LabelMenuVisuals.label(LabelPreferences.Label.QUESTS,LabelMenuVisuals.State.UNAVAILABLE),enabled);
                options.openColors(player);check(slots.get(10),null,false);check(slots.get(11),null,false);check(slots.get(49),LabelMenuVisuals.control(LabelMenuVisuals.Control.INFO),enabled);
                when(event.getRawSlot()).thenReturn(11);options.onClick(event);verify(prefs,never()).setColor(anyString(),anyString());
                when(event.getRawSlot()).thenReturn(10);options.onClick(event);verify(prefs).setColor("Alice","custom");
                options.openPresets(player);check(slots.get(10),LabelMenuVisuals.preset(true),enabled);check(slots.get(11),LabelMenuVisuals.preset(false),enabled);
                when(event.getRawSlot()).thenReturn(10);options.onClick(event);verify(prefs).apply("Alice",snapshot);
                when(event.getClick()).thenReturn(ClickType.SHIFT_RIGHT);options.onClick(event);verify(prefs).deletePreset("Alice","p1");
                options.openSlot(player,0);check(slots.get(10),LabelMenuVisuals.picker(rank,LabelMenuVisuals.Pick.SELECTED),enabled);check(slots.get(11),LabelMenuVisuals.picker(classes,LabelMenuVisuals.Pick.AVAILABLE),enabled);
                verify(player,never()).getInventory();for(var item:items.constructed())verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    private static void check(ItemStack item,org.bukkit.NamespacedKey model,boolean enabled){assertNotNull(item);if(enabled)verify(item.getItemMeta()).setItemModel(model);else verify(item.getItemMeta(),never()).setItemModel(any());}
}
