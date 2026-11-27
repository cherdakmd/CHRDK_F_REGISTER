package ru.cherdakmd.chrdkgenerator;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeneratorGuiVisualsTest {
    @Test void mainMenuUsesOnlyVirtualModelsAndPreservesStatusAndLevel() {
        for (boolean enabled : new boolean[]{false, true}) {
            var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
            yaml.set("textures.menu", enabled); var cfg = GeneratorConfig.load(yaml);
            var plugin = mock(GeneratorPlugin.class); when(plugin.getConfig()).thenReturn(yaml); when(plugin.config()).thenReturn(cfg);
            when(plugin.messages()).thenReturn(Messages.from(getClass().getResourceAsStream("/messages.yml"), null, Logger.getAnonymousLogger()));
            var store = mock(GeneratorStore.class); when(plugin.store()).thenReturn(store);
            var player = mock(Player.class); UUID owner = UUID.randomUUID(), core = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(owner);
            var entry = new GeneratorStore.Entry(owner, 0, core, "world", 0, 64, 0, 1, Map.of(), 0, 0, 0, 0, 0, 0);
            when(store.entry(owner,0)).thenReturn(entry); when(plugin.nearCore(player,entry)).thenReturn(true);
            when(plugin.stateOf(owner,0)).thenReturn("state-working"); when(plugin.combinedBunkerEntry(any())).thenAnswer(c -> c.getArgument(0));
            var slots = new HashMap<Integer,ItemStack>(); var holder = new AtomicReference<InventoryHolder>(); var inv = mock(Inventory.class);
            when(inv.getHolder()).thenAnswer(c -> holder.get()); when(inv.getSize()).thenReturn(54); when(inv.getItem(anyInt())).thenAnswer(c -> slots.get(c.getArgument(0)));
            doAnswer(c -> { slots.clear(); return null; }).when(inv).clear(); doAnswer(c -> { slots.put(c.getArgument(0), c.getArgument(1)); return null; }).when(inv).setItem(anyInt(), any(ItemStack.class));
            var view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inv); when(player.getOpenInventory()).thenReturn(view);
            try (var bukkit = mockStatic(Bukkit.class); var items = mockConstruction(ItemStack.class, (item, context) -> {
                when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)); when(item.clone()).thenReturn(item);
            })) {
                bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(Component.class))).thenAnswer(c -> { holder.set(c.getArgument(0)); return inv; });
                var gui = new GeneratorGui(plugin); gui.open(player,0);
                check(slots.get(4), GeneratorMenuVisuals.status("state-working"), enabled);
                check(slots.get(10), GeneratorMenuVisuals.tier(1), enabled);
                check(slots.get(12), GeneratorMenuVisuals.bunker(0,100), enabled);
                check(slots.get(20), GeneratorMenuVisuals.upgradeTier(1), enabled);
                check(slots.get(21), GeneratorMenuVisuals.module("speed",0), enabled);
                check(slots.get(24), GeneratorMenuVisuals.chunk(0), enabled);
                check(slots.get(49), GeneratorMenuVisuals.Control.TAKE.model(), enabled);
                check(slots.get(8), GeneratorMenuVisuals.Control.CLOSE.model(), enabled);
                var max = new GeneratorStore.Entry(owner,0,core,"world",0,64,0,GeneratorConfig.TIERS,
                        Map.of("speed",GeneratorStore.MAX_LEVEL,"capacity",GeneratorStore.MAX_LEVEL,"luck",GeneratorStore.MAX_LEVEL,"chunk",1), Long.MAX_VALUE,0,0,0,0,0);
                when(store.entry(owner,0)).thenReturn(max); when(plugin.nearCore(player,max)).thenReturn(true); when(plugin.stateOf(owner,0)).thenReturn("state-no-water");
                gui.open(player,0);
                check(slots.get(4), GeneratorMenuVisuals.status("state-no-water"), enabled);
                check(slots.get(10), GeneratorMenuVisuals.tier(GeneratorConfig.TIERS), enabled);
                check(slots.get(12), GeneratorMenuVisuals.bunker(100,100), enabled);
                check(slots.get(20), GeneratorMenuVisuals.upgradeTier(GeneratorConfig.TIERS), enabled);
                check(slots.get(21), GeneratorMenuVisuals.module("speed",GeneratorStore.MAX_LEVEL), enabled);
                check(slots.get(22), GeneratorMenuVisuals.module("capacity",GeneratorStore.MAX_LEVEL), enabled);
                check(slots.get(23), GeneratorMenuVisuals.module("luck",GeneratorStore.MAX_LEVEL), enabled);
                check(slots.get(24), GeneratorMenuVisuals.chunk(1), enabled);
                verify(player,never()).getInventory(); for (var item : items.constructed()) verify(item.getItemMeta(),never()).getPersistentDataContainer();
            }
        }
    }
    static void check(ItemStack item, org.bukkit.NamespacedKey key, boolean enabled) {
        assertNotNull(item); if (enabled) verify(item.getItemMeta()).setItemModel(key); else verify(item.getItemMeta(),never()).setItemModel(any());
    }
}
