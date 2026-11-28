package ru.cherdakmd.chrdkbp;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.pve.PveAwardLedger;
import static org.mockito.Mockito.*;

class PvpNotMobRewardTest {
    @Test void playerDeathDoesNotRegisterPveReceiptOrKillProgress() {
        var plugin=mock(BattlePassPlugin.class); var event=mock(EntityDeathEvent.class); var victim=mock(Player.class);
        when(event.getEntity()).thenReturn(victim); doCallRealMethod().when(plugin).onEntityDeath(event);
        try(var ledger=mockStatic(PveAwardLedger.class)) {
            plugin.onEntityDeath(event); ledger.verifyNoInteractions();
        }
        verify(victim,never()).getKiller();
        verify(plugin,never()).recordProgress(any(),any(),anyString(),anyInt());
    }
}
