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

class FishSalePlannerTest {
    private static ItemStack item() { var item = mock(ItemStack.class); when(item.clone()).thenReturn(item); return item; }
    private static FishToken.Token fish(UUID id, long value) {
        return FishToken.issue(new byte[32], id, new FishingModel.Catch("pike", FishingModel.Size.NORMAL, 40), value);
    }
    @Test void planRemovesOnlySelectedValidatedFishAndDoesNotAlterStorage() {
        var codec = mock(FishItemCodec.class); var a = item(); var b = item(); var other = item();
        var storage = new ItemStack[36]; storage[2] = a; storage[10] = other; storage[35] = b;
        var one = fish(UUID.randomUUID(), 70); var two = fish(UUID.randomUUID(), 120);
        when(codec.read(a)).thenReturn(Optional.of(one)); when(codec.read(b)).thenReturn(Optional.of(two));
        var plan = FishSalePlanner.plan(storage, List.of(2, 35), codec);
        assertEquals(190, plan.price()); assertEquals(List.of(one, two), plan.fish());
        assertSame(a, storage[2]); assertSame(b, storage[35]);
        assertNull(plan.after()[2]); assertNull(plan.after()[35]); assertSame(other, plan.after()[10]);
        var edited = plan.after(); edited[10] = null; assertSame(other, plan.after()[10]);
        assertThrows(UnsupportedOperationException.class, () -> plan.fish().clear());
    }
    @Test void invalidSlotsStacksAndDuplicateIdentityAreRejected() {
        var codec = mock(FishItemCodec.class); var a = item(); var b = item(); var storage = new ItemStack[36];
        storage[0] = a; storage[1] = b; var duplicate = fish(UUID.randomUUID(), 70);
        when(codec.read(a)).thenReturn(Optional.of(duplicate)); when(codec.read(b)).thenReturn(Optional.of(duplicate));
        assertThrows(IllegalArgumentException.class, () -> FishSalePlanner.plan(storage, List.of(0, 0), codec));
        assertThrows(IllegalArgumentException.class, () -> FishSalePlanner.plan(storage, List.of(0, 1), codec));
        assertThrows(IllegalArgumentException.class, () -> FishSalePlanner.plan(storage, List.of(36), codec));
        when(codec.read(b)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> FishSalePlanner.plan(storage, List.of(1), codec));
        assertSame(a, storage[0]); assertSame(b, storage[1]);
    }
}
