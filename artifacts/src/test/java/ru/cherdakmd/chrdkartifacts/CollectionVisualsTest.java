package ru.cherdakmd.chrdkartifacts;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollectionVisualsTest {
    @Test void hiddenEntriesStayIdenticalAndForeignMetadataIsPreserved() {
        var def = ArtifactRegistry.all().get(0);
        var meta = mock(ItemMeta.class);
        CollectionVisuals.entry(meta, def, false, true);
        verify(meta).setItemModel(CollectionVisuals.Icon.UNKNOWN.model());
        clearInvocations(meta);
        CollectionVisuals.entry(meta, def, true, false);
        verifyNoInteractions(meta);
        CollectionVisuals.entry(meta, def, true, true);
        verify(meta).setItemModel(ArtifactVisuals.model(def));
        clearInvocations(meta);
        when(meta.hasCustomModelData()).thenReturn(true);
        CollectionVisuals.entry(meta, def, true, true);
        CollectionVisuals.button(meta, CollectionVisuals.Icon.CLOSE, true);
        verify(meta, never()).setItemModel(any());
        clearInvocations(meta);
        when(meta.hasCustomModelData()).thenReturn(false);
        when(meta.getItemModel()).thenReturn(CollectionVisuals.Icon.PROGRESS.model());
        CollectionVisuals.entry(meta, def, true, true);
        CollectionVisuals.button(meta, CollectionVisuals.Icon.CLOSE, true);
        verify(meta, never()).setItemModel(any());
        assertEquals(6, java.util.Arrays.stream(CollectionVisuals.Icon.values()).map(CollectionVisuals.Icon::model).distinct().count());
    }

    @Test void guiStillProtectsSlotsAndPagesWithBothSettings() {
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(ArtifactsPlugin.class); var yaml = new YamlConfiguration();
            yaml.set("textures.collection-menu", enabled); when(plugin.getConfig()).thenReturn(yaml);
            var config = mock(ArtifactsConfig.class);
            when(config.collection()).thenReturn(new ArtifactsConfig.Collection(true, List.of())); when(plugin.config()).thenReturn(config);
            var messages = mock(Messages.class, call -> {
                if (call.getMethod().getName().equals("get")) return Component.text("test");
                if (call.getMethod().getName().equals("raw")) return "";
                return RETURNS_DEFAULTS.answer(call);
            });
            when(plugin.messages()).thenReturn(messages);
            var collection = mock(CollectionService.class); when(plugin.collection()).thenReturn(collection);
            var def = ArtifactRegistry.bySide(ArtifactDef.Side.GOOD).get(0);
            when(collection.discovered(any())).thenReturn(Set.of(def.id())); when(collection.claimed(any())).thenReturn(Set.of());
            var artifactItems = mock(ArtifactItems.class); when(plugin.items()).thenReturn(artifactItems); when(artifactItems.nameOf(any())).thenReturn("artifact");
            var player = mock(Player.class); UUID owner = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(owner);
            var top = mock(Inventory.class); when(top.getSize()).thenReturn(54);
            var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(top);
            var holder = new AtomicReference<InventoryHolder>(); when(top.getHolder()).thenAnswer(c -> holder.get());
            var gui = new CollectionGui(plugin); List<ItemMeta> metadata = new ArrayList<>();
            try (var bukkit = mockStatic(Bukkit.class);
                 var items = mockConstruction(ItemStack.class, (item, context) -> {
                     var meta = mock(ItemMeta.class); metadata.add(meta); when(item.getItemMeta()).thenReturn(meta);
                 })) {
                bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(player);
                bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(Component.class)))
                        .thenAnswer(c -> { holder.set(c.getArgument(0)); return top; });
                gui.open(player, 0);
                verify(top).setItem(eq(0), same(items.constructed().get(0)));
                verify(top).setItem(eq(48), any()); verify(top).setItem(eq(49), any());
                verify(top).setItem(eq(50), any()); verify(top).setItem(eq(53), any());
                if (enabled) {
                    verify(metadata.get(0)).setItemModel(ArtifactVisuals.model(def));
                    for (int i = 1; i < 45; i++) verify(metadata.get(i)).setItemModel(CollectionVisuals.Icon.UNKNOWN.model());
                    verify(metadata.get(45)).setItemModel(CollectionVisuals.Icon.REWARDS.model());
                    verify(metadata.get(46)).setItemModel(CollectionVisuals.Icon.PROGRESS.model());
                    verify(metadata.get(47)).setItemModel(CollectionVisuals.Icon.CLOSE.model());
                    verify(metadata.get(48)).setItemModel(CollectionVisuals.Icon.NEXT.model());
                } else for (var meta : metadata) verify(meta, never()).setItemModel(any());
                var stranger = mock(Player.class); when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
                var wrong = mock(InventoryClickEvent.class); when(wrong.getView()).thenReturn(view);
                when(wrong.getWhoClicked()).thenReturn(stranger); when(wrong.getRawSlot()).thenReturn(53);
                gui.onClick(wrong); verify(wrong).setCancelled(true); verify(player, times(1)).openInventory(top);
                var drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(view);
                gui.onDrag(drag); verify(drag).setCancelled(true);
                var click = mock(InventoryClickEvent.class); when(click.getView()).thenReturn(view);
                when(click.getWhoClicked()).thenReturn(player); when(click.getRawSlot()).thenReturn(53);
                gui.onClick(click); verify(click).setCancelled(true); verify(player, times(2)).openInventory(top);
                verify(top).setItem(eq(45), any());
                when(click.getRawSlot()).thenReturn(50); gui.onClick(click); verify(player).closeInventory();
                verify(collection, never()).record(any(), anyString());
            }
        }
    }
}
