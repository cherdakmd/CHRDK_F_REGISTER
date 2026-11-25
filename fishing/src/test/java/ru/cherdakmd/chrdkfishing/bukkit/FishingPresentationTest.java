package ru.cherdakmd.chrdkfishing.bukkit;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkfishing.BaitCatalog;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FishingPresentationTest {
    @Test void costsUseRealPlannerAndAllIngredientsHavePlayerNames() {
        assertEquals(100, RodUpgradePlanner.costFor(2).minor());
        assertEquals("1.00", FishingText.amount(RodUpgradePlanner.costFor(2).minor()));
        for (int level = 2; level <= 10; level++) for (var part : RodUpgradePlanner.costFor(level).ingredients())
            assertNotEquals(part.material().name(), FishingText.material(part.material()));
        assertEquals("Незеритовый скрап", FishingText.material(Material.NETHERITE_SCRAP));
    }
    @Test void countsOnlyPlainValidStorageStacks() {
        ItemStack plain = mock(ItemStack.class), tagged = mock(ItemStack.class), invalid = mock(ItemStack.class);
        for (var item : new ItemStack[]{plain, tagged, invalid}) {
            when(item.getType()).thenReturn(Material.STRING); when(item.getMaxStackSize()).thenReturn(64);
        }
        when(plain.getAmount()).thenReturn(8); when(tagged.getAmount()).thenReturn(4); when(tagged.hasItemMeta()).thenReturn(true);
        when(invalid.getAmount()).thenReturn(65);
        assertEquals(8, RodUpgradePlanner.available(new ItemStack[]{plain, tagged, invalid, null}, Material.STRING));
        assertEquals(0, RodUpgradePlanner.available(new ItemStack[]{plain}, Material.IRON_INGOT));
    }
    @Test void baitDescriptionsReflectTheActualWeightedEffects() {
        assertTrue(FishingText.baitEffect(BaitCatalog.byId("quick")).contains("5%"));
        assertTrue(FishingText.baitEffect(BaitCatalog.byId("rare")).contains("редкой"));
        assertTrue(FishingText.baitEffect(BaitCatalog.byId("large")).contains("крупной"));
    }
}
