package ru.cherdakmd.chrdkenchants;

import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PvpMeleeEffectsTest {
    @Test void pvpEffectsWaitForFinalCancellationAndRejectRecursiveSecondaryHits() {
        var tasks=new ArrayList<Runnable>(); var plugin=mock(EnchantsPlugin.class);
        var service=mock(EnchantService.class); var random=mock(Random.class);
        var attacker=mock(Player.class); var target=mock(Player.class); var world=mock(World.class);
        var at=mock(Location.class);
        var event=mock(EntityDamageByEntityEvent.class);
        when(attacker.getUniqueId()).thenReturn(UUID.randomUUID()); when(target.getUniqueId()).thenReturn(UUID.randomUUID());
        when(world.getUID()).thenReturn(UUID.randomUUID()); when(world.getPVP()).thenReturn(true);
        when(attacker.getWorld()).thenReturn(world); when(target.getWorld()).thenReturn(world);
        when(attacker.isOnline()).thenReturn(true); when(target.isOnline()).thenReturn(true);
        when(target.getLocation()).thenReturn(at);
        var api=mock(ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.class);
        when(plugin.coreApi()).thenReturn(api); when(api.isAuthenticated(attacker)).thenReturn(true); when(api.isAuthenticated(target)).thenReturn(true);
        when(plugin.service()).thenReturn(service); when(service.random()).thenReturn(random); when(random.nextInt(100)).thenReturn(0);
        when(event.getDamager()).thenReturn(attacker); when(event.getEntity()).thenReturn(target); when(event.getFinalDamage()).thenReturn(5.0);
        var listener=new MeleeEffectsListener(plugin,tasks::add);
        listener.confirmedEffects(event,attacker,target,Map.of("thunderstrike",1)); assertEquals(1,tasks.size());
        when(event.isCancelled()).thenReturn(true); tasks.removeFirst().run();
        verify(target,never()).damage(anyDouble(),any(org.bukkit.entity.Entity.class));
        when(event.isCancelled()).thenReturn(false);
        var reflected=mock(EntityDamageByEntityEvent.class); when(reflected.getDamager()).thenReturn(target);
        when(reflected.getEntity()).thenReturn(attacker); when(reflected.getFinalDamage()).thenReturn(1.0);
        doAnswer(call->{listener.onConfirmedHit(reflected);return null;}).when(target).damage(anyDouble(),eq(attacker));
        listener.confirmedEffects(event,attacker,target,Map.of("thunderstrike",1)); assertEquals(1,tasks.size()); tasks.removeFirst().run();
        verify(target).damage(anyDouble(),eq(attacker)); assertTrue(tasks.isEmpty(),"secondary reflection must not schedule another enchant chain");
    }
}
