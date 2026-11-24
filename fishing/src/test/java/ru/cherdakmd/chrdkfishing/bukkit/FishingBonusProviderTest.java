package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FishingBonusProviderTest {
    @Test void onlyAuthenticatedOnlineSurvivalOnMainThreadCanMintExtra() {
        var codec = mock(FishItemCodec.class); var player = mock(Player.class);
        var original = mock(ItemStack.class); var extra = mock(ItemStack.class); var id = UUID.randomUUID();
        when(player.isOnline()).thenReturn(true); when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(codec.duplicateForCatch(original, id)).thenReturn(Optional.of(extra));
        var provider = new FishingBonusProvider(codec, p -> true, () -> true, () -> id);
        assertSame(extra, provider.createExtra(player, original));
        when(player.isOnline()).thenReturn(false); assertNull(provider.createExtra(player, original));
        when(player.isOnline()).thenReturn(true); when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        assertNull(provider.createExtra(player, original));
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        assertNull(new FishingBonusProvider(codec, p -> false, () -> true, () -> id).createExtra(player, original));
        assertNull(new FishingBonusProvider(codec, p -> true, () -> false, () -> id).createExtra(player, original));
        verify(codec, times(1)).duplicateForCatch(original, id);
    }
}
