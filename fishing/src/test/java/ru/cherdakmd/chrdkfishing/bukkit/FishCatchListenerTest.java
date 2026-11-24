package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkfishing.FishToken;
import ru.cherdakmd.chrdkfishing.FishingRules;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FishCatchListenerTest {
    @Test void plainCatchIsReplacedOnlyWhenEverySafetyGatePasses() {
        var event = mock(PlayerFishEvent.class); var player = mock(Player.class); var entity = mock(Item.class);
        var vanilla = mock(ItemStack.class); var replacement = mock(ItemStack.class);
        when(event.getState()).thenReturn(PlayerFishEvent.State.CAUGHT_FISH); when(event.getCaught()).thenReturn(entity);
        when(event.getPlayer()).thenReturn(player); when(player.isOnline()).thenReturn(true); when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(entity.getItemStack()).thenReturn(vanilla); when(vanilla.getType()).thenReturn(Material.COD); when(vanilla.getAmount()).thenReturn(1);
        when(replacement.getAmount()).thenReturn(1); when(replacement.getType()).thenReturn(Material.COD);
        byte[] key = new byte[32]; var calls = new AtomicInteger();
        var listener = new FishCatchListener(p -> true, () -> true, p -> 1, p -> 1,
                e -> new FishingRules.Context(null, null, false, false), token -> {
                    assertTrue(FishToken.verify(key, token)); calls.incrementAndGet(); return replacement;
                }, UUID::randomUUID, key, FishingRules.defaults(), 10_000, new Random(8));
        listener.onFish(event);
        verify(entity).setItemStack(replacement); assertEquals(1, calls.get());
        reset(entity); when(entity.getItemStack()).thenReturn(vanilla);
        when(vanilla.hasItemMeta()).thenReturn(true);
        listener.onFish(event); verify(entity, never()).setItemStack(any()); assertEquals(1, calls.get());
        when(vanilla.hasItemMeta()).thenReturn(false); when(event.isCancelled()).thenReturn(true);
        listener.onFish(event); verify(entity, never()).setItemStack(any());
    }
    @Test void noServerMutationWhenSafetyGateOrAuthenticationIsUnavailable() {
        var event = mock(PlayerFishEvent.class); var player = mock(Player.class); var entity = mock(Item.class);
        var item = mock(ItemStack.class);
        when(event.getState()).thenReturn(PlayerFishEvent.State.CAUGHT_FISH); when(event.getCaught()).thenReturn(entity);
        when(event.getPlayer()).thenReturn(player); when(player.isOnline()).thenReturn(true); when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(entity.getItemStack()).thenReturn(item); when(item.getType()).thenReturn(Material.COD); when(item.getAmount()).thenReturn(1);
        var blocked = new FishCatchListener(p -> true, () -> false, p -> 1, p -> 1,
                e -> new FishingRules.Context(null, null, false, false), t -> { throw new AssertionError(); },
                UUID::randomUUID, new byte[32], FishingRules.defaults(), 10_000, new Random(1));
        blocked.onFish(event); verify(entity, never()).setItemStack(any());
        var unauth = new FishCatchListener(p -> false, () -> true, p -> 1, p -> 1,
                e -> new FishingRules.Context(null, null, false, false), t -> { throw new AssertionError(); },
                UUID::randomUUID, new byte[32], FishingRules.defaults(), 10_000, new Random(1));
        unauth.onFish(event); verify(entity, never()).setItemStack(any());
    }
}
