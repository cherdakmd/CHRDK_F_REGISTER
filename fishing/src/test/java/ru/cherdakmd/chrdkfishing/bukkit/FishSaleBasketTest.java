package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkfishing.FishToken;
import ru.cherdakmd.chrdkfishing.FishingModel;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FishSaleBasketTest {
    private static FishToken.Token fish(UUID id, long price) {
        return FishToken.issue(new byte[32], id, new FishingModel.Catch("pike", FishingModel.Size.NORMAL, 40), price);
    }
    @Test void addsRemovesAndClearsWithoutTouchingRealItems() {
        var basket = new FishSaleBasket(); var first = fish(UUID.randomUUID(), 70); var second = fish(UUID.randomUUID(), 120);
        assertEquals(FishSaleBasket.Change.ADDED, basket.toggle(8, first));
        assertEquals(FishSaleBasket.Change.ADDED, basket.toggle(35, second));
        assertEquals(190, basket.total()); assertEquals(List.of(8, 35), basket.slots());
        assertThrows(UnsupportedOperationException.class, () -> basket.entries().clear());
        basket.removeAt(0);
        assertEquals(List.of(35), basket.slots()); assertEquals(120, basket.total());
        assertEquals(FishSaleBasket.Change.REMOVED, basket.toggle(35, second));
        assertTrue(basket.isEmpty()); assertEquals(0, basket.total());
        basket.toggle(8, first); basket.clear();
        assertTrue(basket.isEmpty()); assertEquals(0, basket.total());
    }
    @Test void duplicateIdentitiesAndExcessiveTotalsAreRejected() {
        var basket = new FishSaleBasket(); var id = UUID.randomUUID(); var first = fish(id, 600_000);
        assertEquals(FishSaleBasket.Change.ADDED, basket.toggle(0, first));
        assertEquals(FishSaleBasket.Change.DUPLICATE, basket.toggle(1, fish(id, 1)));
        assertEquals(FishSaleBasket.Change.TOO_EXPENSIVE, basket.toggle(1, fish(UUID.randomUUID(), 400_001)));
        assertEquals(FishSaleBasket.Change.ADDED, basket.toggle(1, fish(UUID.randomUUID(), 400_000)));
        assertEquals(1_000_000, basket.total());
        assertThrows(IllegalArgumentException.class, () -> basket.toggle(36, first));
    }
    @Test void changedOrReplacedFishCannotBeSoldAsTheDisplayedSelection() {
        var basket = new FishSaleBasket(); var original = fish(UUID.randomUUID(), 70); var replacement = fish(UUID.randomUUID(), 70);
        var codec = mock(FishItemCodec.class); var item = mock(ItemStack.class); when(item.clone()).thenReturn(item);
        var storage = new ItemStack[36]; storage[5] = item;
        basket.toggle(5, original);
        when(codec.read(item)).thenReturn(Optional.of(replacement));
        assertFalse(basket.matches(FishSalePlanner.plan(storage, basket.slots(), codec)));
        assertEquals(FishSaleBasket.Change.CHANGED, basket.toggle(5, replacement));
        assertTrue(basket.isEmpty());
        assertEquals(FishSaleBasket.Change.ADDED, basket.toggle(5, replacement));
        assertTrue(basket.matches(FishSalePlanner.plan(storage, basket.slots(), codec)));
        assertSame(item, storage[5]);
    }
    @Test void quoteMustMatchDisplayedPricesAndOrder() {
        var basket = new FishSaleBasket(); var one = fish(UUID.randomUUID(), 70); var two = fish(UUID.randomUUID(), 100);
        var codec = mock(FishItemCodec.class); var a = mock(ItemStack.class); var b = mock(ItemStack.class);
        when(a.clone()).thenReturn(a); when(b.clone()).thenReturn(b);
        when(codec.read(a)).thenReturn(Optional.of(one)); when(codec.read(b)).thenReturn(Optional.of(two));
        var storage = new ItemStack[36]; storage[1] = a; storage[2] = b;
        basket.toggle(1, one); basket.toggle(2, two);
        assertTrue(basket.matches(FishSalePlanner.plan(storage, List.of(1, 2), codec)));
        assertFalse(basket.matches(FishSalePlanner.plan(storage, List.of(2, 1), codec)));
        when(codec.read(a)).thenReturn(Optional.of(fish(one.id(), 71)));
        assertFalse(basket.matches(FishSalePlanner.plan(storage, basket.slots(), codec)));
    }
}
