package ru.cherdakmd.chrdkartifacts;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class PvpNotMobRewardTest {
    @Test void playerDeathNeverRollsMobArtifacts() {
        var plugin=mock(ArtifactsPlugin.class); var event=mock(EntityDeathEvent.class); var victim=mock(Player.class);
        when(event.getEntity()).thenReturn(victim);
        new ArtifactLootListener(plugin).onEntityDeath(event);
        verifyNoInteractions(plugin); verify(victim,never()).getKiller(); verify(event,never()).getDrops();
    }
}
