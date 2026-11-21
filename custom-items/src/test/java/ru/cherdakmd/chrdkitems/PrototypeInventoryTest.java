package ru.cherdakmd.chrdkitems;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrototypeInventoryTest {
    @Test void searchesOnlyStorageAndNeverSelectsOccupiedSlots() {
        boolean[] occupied = new boolean[41];
        java.util.Arrays.fill(occupied, 0, 36, true);
        assertEquals(-1, CustomItemsPlugin.firstFreeStorage(i -> occupied[i]));
        occupied[14] = false;
        assertEquals(14, CustomItemsPlugin.firstFreeStorage(i -> occupied[i]));
        occupied[3] = false;
        assertEquals(3, CustomItemsPlugin.firstFreeStorage(i -> occupied[i]));
    }
}
