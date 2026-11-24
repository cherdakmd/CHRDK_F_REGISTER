package ru.cherdakmd.chrdkfishing.bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkskills.ChrdkSkillsApi;
import ru.cherdakmd.chrdkskills.FishingCatchBonus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FishingPluginTest {
    @TempDir Path dir;
    @Test void bootstrapRegistersProviderAndActiveListener() throws Exception {
        var plugin = mock(Plugin.class); var manager = mock(PluginManager.class);
        var services = mock(ServicesManager.class); var skills = mock(ChrdkSkillsApi.class);
        when(services.load(ChrdkSkillsApi.class)).thenReturn(skills);
        var active = FishingActivation.install(plugin, manager, services, p -> true, () -> true, () -> true,
                skills, new byte[32], 5000, new Random(1));
        verify(manager).registerEvents(active.router(), plugin);
        verify(services).register(FishingCatchBonus.class, active.provider(), plugin, ServicePriority.Normal);
        var annotation = FishCatchRouter.class.getMethod("onFish", PlayerFishEvent.class).getAnnotation(EventHandler.class);
        assertNotNull(annotation); assertEquals(EventPriority.HIGHEST, annotation.priority()); assertTrue(annotation.ignoreCancelled());
    }
    @Test void missingCoreOrSkillsOrCompetingServiceFailsClosed() {
        var plugin = mock(Plugin.class); var manager = mock(PluginManager.class);
        var services = mock(ServicesManager.class); var skills = mock(ChrdkSkillsApi.class);
        when(services.load(ChrdkSkillsApi.class)).thenReturn(skills);
        assertThrows(IllegalStateException.class, () -> FishingActivation.install(plugin, manager, services, p -> true,
                () -> false, () -> true, skills, new byte[32], 5000, new Random(1)));
        when(services.load(FishingCatchBonus.class)).thenReturn(mock(FishingCatchBonus.class));
        assertThrows(IllegalStateException.class, () -> FishingActivation.install(plugin, manager, services, p -> true,
                () -> true, () -> true, skills, new byte[32], 5000, new Random(1)));
        verify(manager, never()).registerEvents(any(), any());
    }
    @Test void newKeyOnlyForEmptyFolderNeverForMissingEstablishedState() throws Exception {
        Path data = dir.resolve("data");
        byte[] initial = FishingPlugin.signingKey(data);
        assertArrayEquals(initial, FishingPlugin.signingKey(data));
        Files.delete(data.resolve("fish-signing.key")); Files.writeString(data.resolve("existing-data"), "do not replace");
        assertThrows(IOException.class, () -> FishingPlugin.signingKey(data));
        assertFalse(Files.exists(data.resolve("fish-signing.key")));
        assertEquals("do not replace", Files.readString(data.resolve("existing-data")));
    }
}
