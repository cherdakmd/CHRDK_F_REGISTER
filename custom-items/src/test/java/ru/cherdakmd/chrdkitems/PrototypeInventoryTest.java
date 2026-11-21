package ru.cherdakmd.chrdkitems;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class PrototypeInventoryTest {
    private PlayerInventory inventory(ItemStack[] slots) {
        return (PlayerInventory) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PlayerInventory.class}, (p, method, args) -> {
                    if (method.getName().equals("getItem") && args[0] instanceof Integer i) return slots[i];
                    throw new AssertionError("Unexpected call " + method.getName());
                });
    }
    @Test void searchesOnlyStorageAndNeverOverwritesOccupiedSlots() {
        var slots = new ItemStack[41];
        for (int i = 0; i < 36; i++) slots[i] = new ItemStack(Material.STONE);
        assertEquals(-1, CustomItemsPlugin.firstFreeStorage(inventory(slots)));
        slots[14] = null;
        assertEquals(14, CustomItemsPlugin.firstFreeStorage(inventory(slots)));
        slots[3] = new ItemStack(Material.AIR);
        assertEquals(3, CustomItemsPlugin.firstFreeStorage(inventory(slots)));
    }
}
