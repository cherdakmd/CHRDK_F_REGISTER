package ru.cherdakmd.chrdkenchants;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.junit.jupiter.api.Test;

import java.util.Map;
import static org.mockito.Mockito.*;

/** Exercises the actual listener against synthetic anvil events, without a running server. */
class VanillaMergeItemRuntimeTest {
    private static final class Fixture {
        final EnchantsPlugin plugin = mock(EnchantsPlugin.class);
        final PrepareAnvilEvent event = mock(PrepareAnvilEvent.class);
        final AnvilInventory inventory = mock(AnvilInventory.class);
        final AnvilView view = mock(AnvilView.class);
        final Enchantment efficiency = mock(Enchantment.class);
        final ItemStack left = mock(ItemStack.class), right = mock(ItemStack.class);
        final ItemMeta leftMeta = mock(ItemMeta.class), rightMeta = mock(ItemMeta.class);
        final ItemStack output = mock(ItemStack.class);
        final ItemMeta outputMeta = mock(ItemMeta.class);
        final VanillaMergeListener listener = new VanillaMergeListener(plugin);
        Fixture() {
            when(event.getInventory()).thenReturn(inventory);
            when(event.getView()).thenReturn(view);
            when(view.getMaximumRepairCost()).thenReturn(Integer.MAX_VALUE);
            when(inventory.getItem(0)).thenReturn(left);
            when(inventory.getItem(1)).thenReturn(right);
            when(left.getType()).thenReturn(Material.DIAMOND_PICKAXE);
            when(right.getType()).thenReturn(Material.DIAMOND_PICKAXE);
            when(left.getAmount()).thenReturn(1);
            when(right.getAmount()).thenReturn(1);
            when(left.getItemMeta()).thenReturn(leftMeta);
            when(right.getItemMeta()).thenReturn(rightMeta);
            when(leftMeta.getEnchants()).thenReturn(Map.of(efficiency, 5));
            when(rightMeta.getEnchants()).thenReturn(Map.of(efficiency, 5));
            when(efficiency.getMaxLevel()).thenReturn(5);
            when(output.getItemMeta()).thenReturn(outputMeta);
        }
    }
    @Test void resultClampedByVanillaIsPatchedFromFiveToSix() {
        var f = new Fixture();
        ItemStack nativeResult = mock(ItemStack.class);
        ItemMeta nativeMeta = mock(ItemMeta.class);
        when(nativeResult.getItemMeta()).thenReturn(nativeMeta);
        when(nativeMeta.getEnchants()).thenReturn(Map.of(f.efficiency, 5));
        when(nativeResult.clone()).thenReturn(f.output);
        when(f.event.getResult()).thenReturn(nativeResult);
        f.listener.onPrepareAnvil(f.event);
        verify(f.outputMeta).addEnchant(f.efficiency, 6, true);
        verify(f.output).setItemMeta(f.outputMeta);
        verify(f.event).setResult(f.output);
    }
    @Test void twoIdenticalItemsCreateSixWhenVanillaReturnsNoResult() {
        var f = new Fixture();
        when(f.left.clone()).thenReturn(f.output);
        when(f.event.getResult()).thenReturn(null);
        f.listener.onPrepareAnvil(f.event);
        verify(f.outputMeta).addEnchant(f.efficiency, 6, true);
        verify(f.event).setResult(f.output);
        verify(f.view).setRepairItemCountCost(1);
        verify(f.view).setRepairCost(6);
    }
    @Test void unrelatedItemTypesNeverFabricateResult() {
        var f = new Fixture();
        when(f.right.getType()).thenReturn(Material.NETHERITE_PICKAXE);
        when(f.event.getResult()).thenReturn(null);
        f.listener.onPrepareAnvil(f.event);
        verify(f.event, never()).setResult(any());
        verify(f.view, never()).setRepairCost(anyInt());
    }
}
