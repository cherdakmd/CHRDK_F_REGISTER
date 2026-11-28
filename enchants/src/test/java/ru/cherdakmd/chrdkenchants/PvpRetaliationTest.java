package ru.cherdakmd.chrdkenchants;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.RetaliationDamageContext;
import static org.mockito.Mockito.*;

class PvpRetaliationTest {
    @Test void reflectedHitDoesNotTriggerAnotherArmorResponseOrWeaponEffect() {
        var plugin=mock(EnchantsPlugin.class); var source=mock(Player.class); var target=mock(Player.class);
        when(source.getUniqueId()).thenReturn(UUID.randomUUID()); when(target.getUniqueId()).thenReturn(UUID.randomUUID());
        var event=mock(EntityDamageByEntityEvent.class); when(event.getDamager()).thenReturn(source);
        when(event.getEntity()).thenReturn(target); when(event.getFinalDamage()).thenReturn(5.0);
        var armor=new ArmorEffectsListener(plugin); var melee=new MeleeEffectsListener(plugin);
        RetaliationDamageContext.run(source,target,()->{ armor.onDamaged(event); melee.onConfirmedHit(event); });
        verifyNoInteractions(plugin);
        verify(target,never()).damage(anyDouble(),any(org.bukkit.entity.Entity.class));
    }

    @Test void retributionSendsNormalAttributedDamageWithinScopedMarker() {
        var plugin=mock(EnchantsPlugin.class); var defender=mock(Player.class); var attacker=mock(Player.class);
        var world=mock(org.bukkit.World.class); var location=mock(org.bukkit.Location.class);
        when(defender.getUniqueId()).thenReturn(UUID.randomUUID()); when(attacker.getUniqueId()).thenReturn(UUID.randomUUID());
        when(defender.getWorld()).thenReturn(world); when(attacker.getLocation()).thenReturn(location);
        when(location.add(anyDouble(),anyDouble(),anyDouble())).thenReturn(location);
        var original=mock(EntityDamageByEntityEvent.class); when(original.getDamager()).thenReturn(attacker);
        when(original.getFinalDamage()).thenReturn(10.0);
        var reflected=mock(EntityDamageByEntityEvent.class); when(reflected.getDamager()).thenReturn(defender);
        when(reflected.getEntity()).thenReturn(attacker); when(reflected.getFinalDamage()).thenReturn(0.8);
        var armor=new ArmorEffectsListener(plugin);
        doAnswer(call->{
            org.junit.jupiter.api.Assertions.assertTrue(RetaliationDamageContext.isRetaliation(reflected));
            armor.onDamaged(reflected);
            return null;
        }).when(attacker).damage(anyDouble(),eq(defender));
        armor.retaliate(original,defender,attacker,java.util.Map.of("retribution",1));
        verify(attacker).damage(eq(0.8),eq(defender));
        org.junit.jupiter.api.Assertions.assertFalse(RetaliationDamageContext.isRetaliation(reflected));
    }
}
