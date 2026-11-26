package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkfishing.RodToken;
import ru.cherdakmd.chrdkfishing.RodUpgradeJournal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RodDuplicateGuardTest {
    private final byte[] key = new byte[32];

    @Test void matchesIdentityAcrossLevelsButIgnoresUnmarkedOrUnrelatedItems() {
        var id = UUID.randomUUID();
        var selected = RodToken.issue(key, id, 3);
        var same = RodToken.issue(key, id, 2);
        var different = RodToken.issue(key, UUID.randomUUID(), 3);
        var clone = mock(ItemStack.class); var unrelated = mock(ItemStack.class); var plain = mock(ItemStack.class);
        var codec = mock(RodItemCodec.class);
        when(codec.read(clone)).thenReturn(Optional.of(same));
        when(codec.read(unrelated)).thenReturn(Optional.of(different));
        when(codec.read(plain)).thenReturn(Optional.empty());
        assertFalse(RodDuplicateGuard.hasVisibleDuplicate(null, codec, clone));
        assertFalse(RodDuplicateGuard.hasVisibleDuplicate(selected, codec, null, plain, unrelated));
        assertTrue(RodDuplicateGuard.hasVisibleDuplicate(selected, codec, unrelated, clone));
        verifyNoInteractions(clone, unrelated, plain);
    }

    @Test void duplicateInsideStorageIsRejectedBeforeMaterialsOrStamping() {
        var token = RodToken.issue(key, UUID.randomUUID(), 2);
        var rod = mock(ItemStack.class); var copy = mock(ItemStack.class); var codec = mock(RodItemCodec.class);
        when(rod.clone()).thenReturn(rod); when(copy.clone()).thenReturn(copy);
        when(rod.getType()).thenReturn(org.bukkit.Material.FISHING_ROD); when(rod.getAmount()).thenReturn(1);
        when(codec.read(rod)).thenReturn(Optional.of(token)); when(codec.read(copy)).thenReturn(Optional.of(token));
        var storage = new ItemStack[36]; storage[0] = rod; storage[20] = copy;
        var error = assertThrows(IllegalArgumentException.class,
                () -> RodUpgradePlanner.plan(storage, 0, codec, key, UUID.randomUUID()));
        assertEquals("duplicate rod identity", error.getMessage());
        verify(codec, never()).stamp(any(), any());
        verify(rod, never()).setAmount(anyInt()); verify(copy, never()).setAmount(anyInt());
    }

    @Test void offhandOrCursorCopyAppearingAfterQuoteStopsBeforeJournalBankAndTransfer() throws Exception {
        var token = RodToken.issue(key, UUID.randomUUID(), 2);
        var duplicate = mock(ItemStack.class); var codec = mock(RodItemCodec.class);
        when(codec.read(duplicate)).thenReturn(Optional.of(token));
        var player = mock(Player.class); var inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        var plan = mock(RodUpgradePlanner.Plan.class); when(plan.oldToken()).thenReturn(token);
        var journal = mock(RodUpgradeJournal.class); var bank = mock(CoreRodBank.class);
        var engine = spy(new RodUpgradeEngine(journal, bank, codec, key, p -> true, getClass().getClassLoader()));
        doReturn(true).when(engine).available(player);
        when(inventory.getItemInOffHand()).thenReturn(duplicate);
        assertEquals(RodUpgradeJournal.Result.CONFLICT, engine.confirm(player, plan));
        when(inventory.getItemInOffHand()).thenReturn(null);
        when(player.getItemOnCursor()).thenReturn(duplicate);
        assertEquals(RodUpgradeJournal.Result.CONFLICT, engine.confirm(player, plan));
        verify(journal, times(2)).pendingOwner();
        verifyNoMoreInteractions(journal);
        verifyNoInteractions(bank);
        verify(inventory, never()).setStorageContents(any());
        verify(player, never()).saveData();
    }
}
