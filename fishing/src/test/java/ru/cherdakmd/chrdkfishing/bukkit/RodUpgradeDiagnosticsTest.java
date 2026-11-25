package ru.cherdakmd.chrdkfishing.bukkit;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RodUpgradeDiagnosticsTest {
    @Test void readOnlyRoundTripIdentifiesCompatibleSnapshot() throws Exception {
        var codec = mock(RodUpgradeDiagnostics.SnapshotCodec.class);
        var rod = mock(ItemStack.class); when(rod.clone()).thenReturn(rod);
        var storage = new ItemStack[36]; storage[0] = rod;
        when(codec.encode(any(ItemStack[].class))).thenReturn("stable");
        when(codec.decode("stable")).thenReturn(storage.clone());
        when(codec.matches(same(storage), eq("stable"))).thenReturn(true);
        var result = RodUpgradeDiagnostics.inspect(storage, codec);
        assertTrue(result.semanticMatch()); assertTrue(result.exactMatch());
        assertTrue(result.stableSnapshot()); assertTrue(result.bankHashMatch());
        assertSame(rod, storage[0]);
        verify(rod, never()).setItemMeta(any()); verify(rod, never()).setAmount(anyInt());
    }
    @Test void flagsInventoryAndSnapshotMismatchWithoutExposingContents() throws Exception {
        var codec = mock(RodUpgradeDiagnostics.SnapshotCodec.class);
        var rod = mock(ItemStack.class); when(rod.clone()).thenReturn(rod);
        var storage = new ItemStack[36]; storage[0] = rod;
        var decoded = new ItemStack[36];
        when(codec.encode(any(ItemStack[].class))).thenReturn("before", "normalized", "normalized");
        when(codec.decode("before")).thenReturn(decoded);
        when(codec.matches(same(storage), eq("before"))).thenReturn(true);
        var result = RodUpgradeDiagnostics.inspect(storage, codec);
        assertTrue(result.semanticMatch()); assertFalse(result.exactMatch());
        assertFalse(result.stableSnapshot()); assertFalse(result.bankHashMatch());
        assertSame(rod, storage[0]);
    }
    @Test void flagsDifferentBankPlanEvenIfRestoredSnapshotIsStable() throws Exception {
        var codec = mock(RodUpgradeDiagnostics.SnapshotCodec.class); var storage = new ItemStack[36];
        when(codec.encode(any(ItemStack[].class))).thenReturn("same", "same", "changed");
        when(codec.decode("same")).thenReturn(new ItemStack[36]);
        when(codec.matches(same(storage), eq("same"))).thenReturn(true);
        var result = RodUpgradeDiagnostics.inspect(storage, codec);
        assertTrue(result.exactMatch()); assertTrue(result.stableSnapshot()); assertFalse(result.bankHashMatch());
    }
    @Test void requiresStorageLengthAndNeverTouchesCodecOnInvalidInput() {
        var codec = mock(RodUpgradeDiagnostics.SnapshotCodec.class);
        assertThrows(IllegalArgumentException.class, () -> RodUpgradeDiagnostics.inspect(new ItemStack[35], codec));
        verifyNoInteractions(codec);
    }
}
