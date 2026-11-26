package ru.cherdakmd.chrdkfishing.bukkit;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkfishing.FishCatalog;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class FishAlbumPaginationTest {
    @Test void allFishAppearExactlyOnceWithReservedNavigationRow() {
        assertEquals(3, FishAlbumGui.pageCount());
        assertEquals(45, FishAlbumGui.page(0).size());
        assertEquals(45, FishAlbumGui.page(1).size());
        assertEquals(2, FishAlbumGui.page(2).size());
        var ids = new HashSet<String>();
        for (int i = 0; i < FishAlbumGui.pageCount(); i++)
            for (var fish : FishAlbumGui.page(i)) assertTrue(ids.add(fish.id()));
        assertEquals(FishCatalog.all().size(), ids.size());
        assertThrows(IllegalArgumentException.class, () -> FishAlbumGui.page(-1));
        assertThrows(IllegalArgumentException.class, () -> FishAlbumGui.page(3));
    }
}
