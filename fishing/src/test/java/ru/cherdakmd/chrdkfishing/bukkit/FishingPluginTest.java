package ru.cherdakmd.chrdkfishing.bukkit;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FishingPluginTest {
    @Test void bootstrapRegistersAnInertListener() {
        var plugin = mock(Plugin.class); var manager = mock(PluginManager.class);
        var router = FishingPlugin.registerStagedListener(plugin, manager);
        verify(manager).registerEvents(router, plugin);
        var event = mock(PlayerFishEvent.class);
        router.onFish(event);
        verify(event, never()).getCaught();
        verify(event, never()).getPlayer();
    }
    @Test void routerOnlyForwardsUncancelledEventsToExplicitDelegate() throws Exception {
        var delegate = mock(FishCatchListener.class);
        var router = new FishCatchRouter(() -> delegate);
        var event = mock(PlayerFishEvent.class);
        router.onFish(event); verify(delegate).onFish(event);
        when(event.isCancelled()).thenReturn(true);
        router.onFish(event); verifyNoMoreInteractions(delegate);
        var annotation = FishCatchRouter.class.getMethod("onFish", PlayerFishEvent.class).getAnnotation(EventHandler.class);
        assertNotNull(annotation); assertEquals(EventPriority.HIGHEST, annotation.priority()); assertTrue(annotation.ignoreCancelled());
    }
}
