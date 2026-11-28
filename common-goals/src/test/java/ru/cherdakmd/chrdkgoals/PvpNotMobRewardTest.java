package ru.cherdakmd.chrdkgoals;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.pve.PveAwardLedger;
import static org.mockito.Mockito.*;

class PvpNotMobRewardTest {
    @Test void playerDeathDoesNotRegisterPveReceipt() {
        var plugin=mock(CommonGoalsPlugin.class); var event=mock(EntityDeathEvent.class); var victim=mock(Player.class);
        when(event.getEntity()).thenReturn(victim);
        try(var ledger=mockStatic(PveAwardLedger.class)) {
            new GoalListeners(plugin).onEntityDeath(event); ledger.verifyNoInteractions();
        }
        verifyNoInteractions(plugin); verify(victim,never()).getKiller();
    }
}
