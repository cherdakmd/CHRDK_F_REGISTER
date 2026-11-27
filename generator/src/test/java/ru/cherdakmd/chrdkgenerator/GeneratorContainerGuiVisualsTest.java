package ru.cherdakmd.chrdkgenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeneratorContainerGuiVisualsTest {
    @Test void controlsNeverRestyleContainerContentsOrChangeTransfers() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(GeneratorPlugin.class); var yaml = new YamlConfiguration(); yaml.set("textures.menu", enabled); when(plugin.getConfig()).thenReturn(yaml);
            when(plugin.messages()).thenReturn(Messages.from(getClass().getResourceAsStream("/messages.yml"), null, Logger.getAnonymousLogger()));
            var generator = mock(GeneratorStore.class); when(plugin.store()).thenReturn(generator);
            var containerStore = mock(GeneratorContainerStore.class); var transfer = mock(GeneratorContainerTransfer.class); var access = mock(GeneratorContainerGui.Access.class);
            var player = mock(Player.class); UUID owner=UUID.randomUUID(), core=UUID.randomUUID(); when(player.getUniqueId()).thenReturn(owner);
            when(player.isOnline()).thenReturn(true); when(player.hasPermission("chrdkgenerator.use")).thenReturn(true);
            var entry=new GeneratorStore.Entry(owner,0,core,"world",0,64,0,1,Map.of(),0,0,0,0,0,0);
            when(generator.entry(owner,0)).thenReturn(entry); when(plugin.nearCore(player,entry)).thenReturn(true); when(access.prepare(player,0)).thenReturn(true);
            var stacks = new ArrayList<GeneratorContainerStore.Stack>(Collections.nCopies(GeneratorContainerStore.SLOTS,null));
            stacks.set(0,new GeneratorContainerStore.Stack("STONE",4));
            var snapshot=new GeneratorContainerStore.View(core, 1, stacks, 0, 0, false, null, false); when(containerStore.view(owner,0)).thenReturn(snapshot);
            var holder=new AtomicReference<InventoryHolder>(); var slots=new HashMap<Integer,ItemStack>(); var inv=mock(Inventory.class);
            when(inv.getHolder()).thenAnswer(c -> holder.get()); doAnswer(c -> {slots.clear(); return null;}).when(inv).clear();
            doAnswer(c -> {slots.put(c.getArgument(0),c.getArgument(1));return null;}).when(inv).setItem(anyInt(),any(ItemStack.class));
            var view=mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inv); when(player.getOpenInventory()).thenReturn(view);
            var scheduler=mock(BukkitScheduler.class); var task=new AtomicReference<Runnable>(); doAnswer(c -> {task.set(c.getArgument(1));return mock(BukkitTask.class);}).when(scheduler).runTask(eq(plugin),any(Runnable.class));
            var fakeMaterial=mock(Material.class); when(fakeMaterial.isItem()).thenReturn(true); when(fakeMaterial.isAir()).thenReturn(false); when(fakeMaterial.getMaxStackSize()).thenReturn(64);
            try (var bukkit=mockStatic(Bukkit.class); var materials=mockStatic(Material.class); var items=mockConstruction(ItemStack.class, (item,context) -> when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
                materials.when(() -> Material.matchMaterial("STONE")).thenReturn(fakeMaterial);
                bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(Component.class))).thenAnswer(c -> {holder.set(c.getArgument(0));return inv;});
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                var gui=new GeneratorContainerGui(plugin,containerStore,transfer,access); gui.open(player,0);
                var shown=slots.get(0); assertNotNull(shown); verify(shown.getItemMeta(),never()).setItemModel(any()); verify(shown.getItemMeta(),never()).setCustomModelData(any());
                GeneratorGuiVisualsTest.check(slots.get(45),GeneratorMenuVisuals.Control.CONTAINER_REFRESH.model(),enabled);
                GeneratorGuiVisualsTest.check(slots.get(47),GeneratorMenuVisuals.Control.FUEL_OPEN.model(),enabled);
                GeneratorGuiVisualsTest.check(slots.get(49),GeneratorMenuVisuals.Control.CONTAINER_SETTINGS.model(),enabled);
                GeneratorGuiVisualsTest.check(slots.get(53),GeneratorMenuVisuals.Control.CONTAINER_CLOSE.model(),enabled);
                var event=mock(InventoryClickEvent.class); when(event.getView()).thenReturn(view); when(event.getWhoClicked()).thenReturn(player);
                when(event.getRawSlot()).thenReturn(47); when(event.getClick()).thenReturn(ClickType.LEFT); gui.click(event); verify(event).setCancelled(true);
                assertNotNull(task.get()); task.getAndSet(null).run();
                GeneratorGuiVisualsTest.check(slots.get(13),GeneratorMenuVisuals.Control.FUEL_HELP.model(),enabled);
                GeneratorGuiVisualsTest.check(slots.get(22),GeneratorMenuVisuals.Control.FUEL_TOTAL.model(),enabled);
                GeneratorGuiVisualsTest.check(slots.get(47),GeneratorMenuVisuals.Control.FUEL_BACK.model(),enabled);
                verifyNoInteractions(transfer); verify(player,never()).getInventory();
                for (var item : items.constructed()) verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
}
