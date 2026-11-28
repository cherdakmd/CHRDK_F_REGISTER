package ru.cherdakmd.chrdkenchants;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.AreaPvpGate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArrowExplosionGuardTest {
    @Test void blocksDeniedCollateralOnlyDuringTheScopedExplosion() {
        var guard=new ArrowExplosionGuard(); var shooter=mock(Player.class); var target=mock(Player.class); var arrow=mock(org.bukkit.entity.Arrow.class);
        var event=mock(EntityDamageEvent.class); when(event.getEntity()).thenReturn(target);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_EXPLOSION);
        try(var gate=mockStatic(AreaPvpGate.class)) {
            gate.when(()->AreaPvpGate.allows(shooter,target,arrow)).thenReturn(false);
            guard.protect(event); verify(event,never()).setCancelled(true);
            guard.explode(shooter,arrow,()->guard.protect(event)); verify(event).setCancelled(true);
            var knockback=mock(io.papermc.paper.event.entity.EntityKnockbackEvent.class);
            when(knockback.getEntity()).thenReturn(target);
            when(knockback.getCause()).thenReturn(io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.EXPLOSION);
            guard.explode(shooter,arrow,()->guard.protectKnockback(knockback));
            verify(knockback).setCancelled(true);
            gate.when(()->AreaPvpGate.allows(shooter,target,arrow)).thenReturn(true);
            reset(knockback); when(knockback.getEntity()).thenReturn(target);
            when(knockback.getCause()).thenReturn(io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.EXPLOSION);
            guard.explode(shooter,arrow,()->guard.protectKnockback(knockback)); verify(knockback,never()).setCancelled(true);
            reset(event); when(event.getEntity()).thenReturn(target); when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);
            guard.explode(shooter,arrow,()->guard.protect(event)); verify(event,never()).setCancelled(true);
            assertThrows(IllegalStateException.class,()->guard.explode(shooter,arrow,()->{throw new IllegalStateException();}));
            when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_EXPLOSION);
            guard.protect(event); verify(event,never()).setCancelled(true);
        }
    }
}
