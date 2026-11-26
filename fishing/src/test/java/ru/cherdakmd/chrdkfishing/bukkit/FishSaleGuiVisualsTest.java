package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkfishing.FishSaleJournal;
import ru.cherdakmd.chrdkfishing.FishToken;
import ru.cherdakmd.chrdkfishing.FishingModel;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FishSaleGuiVisualsTest {
    @Test void virtualPreviewKeepsSelectionAmountsAndPlainVanillaIcons() {
        for (boolean enabled : new boolean[]{false, true}) {
            var plugin = mock(Plugin.class); var config = new YamlConfiguration(); config.set("textures.sale-menu", enabled);
            when(plugin.getConfig()).thenReturn(config);
            var engine = mock(FishSaleEngine.class); var codec = mock(FishItemCodec.class);
            var gui = new FishSaleGui(plugin, engine, codec); gui.setBack(p -> {});
            var holder = new FishSaleGui.Holder(UUID.randomUUID()); var top = mock(Inventory.class); holder.inventory = top;
            Map<Integer, ItemStack> shown = new HashMap<>();
            doAnswer(c -> { shown.clear(); return null; }).when(top).clear();
            doAnswer(c -> { shown.put(c.getArgument(0), c.getArgument(1)); return null; }).when(top).setItem(anyInt(), nullable(ItemStack.class));
            var token = FishToken.issue(new byte[32], UUID.randomUUID(), new FishingModel.Catch("pike", FishingModel.Size.NORMAL, 40), 70);
            var vanilla = new FishSaleJournal.VanillaFish(8, "SALMON", 20);
            try (var items = mockConstruction(ItemStack.class, (item, context) -> {
                var meta = mock(ItemMeta.class); when(item.getItemMeta()).thenReturn(meta);
            })) {
                gui.render(holder);
                checkModel(shown.get(49), FishSaleVisuals.Icon.EMPTY.model(), enabled);
                assertTrue(holder.basket.isEmpty()); assertEquals(0, holder.basket.total());
                assertEquals(FishSaleBasket.Change.ADDED, holder.basket.toggle(3, token));
                assertEquals(FishSaleBasket.Change.ADDED, holder.basket.toggle(new FishSalePlanner.Entry(8, null, vanilla)));
                gui.render(holder);
                checkModel(shown.get(0), FishingVisuals.fish("pike"), enabled);
                verify(shown.get(1).getItemMeta(), never()).setItemModel(any());
                checkModel(shown.get(49), FishSaleVisuals.Icon.SELL.model(), enabled);
                assertEquals(java.util.List.of(3, 8), holder.basket.slots()); assertEquals(270, holder.basket.total());
                verifyNoInteractions(engine, codec);
                for (var item : items.constructed()) verify(item.getItemMeta(), never()).getPersistentDataContainer();
            }
        }
    }
    private static void checkModel(ItemStack item, org.bukkit.NamespacedKey key, boolean enabled) {
        assertNotNull(item); if (enabled) verify(item.getItemMeta()).setItemModel(key);
        else verify(item.getItemMeta(), never()).setItemModel(any());
    }
}
