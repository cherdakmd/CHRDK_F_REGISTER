package ru.cherdakmd.chrdkenchants;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.AreaPvpGate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArrowExplosionGuardTest {
    @Test void blocksDeniedCollateralOnlyDuringTheScopedExplosion() {
        var guard=new ArrowExplosionGuard(); var shooter=mock(Player.class); var target=mock(Player.class);
        var event=mock(EntityDamageEvent.class); when(event.getEntity()).thenReturn(target);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_EXPLOSION);
        try(var gate=mockStatic(AreaPvpGate.class)) {
            gate.when(()->AreaPvpGate.allows(shooter,target)).thenReturn(false);
            guard.protect(event); verify(event,never()).setCancelled(true);
            guard.explode(shooter,()->guard.protect(event)); verify(event).setCancelled(true);
            reset(event); when(event.getEntity()).thenReturn(target); when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);
            guard.explode(shooter,()->guard.protect(event)); verify(event,never()).setCancelled(true);
            assertThrows(IllegalStateException.class,()->guard.explode(shooter,()->{throw new IllegalStateException();}));
            when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_EXPLOSION);
            guard.protect(event); verify(event,never()).setCancelled(true);
        }
    }
}
