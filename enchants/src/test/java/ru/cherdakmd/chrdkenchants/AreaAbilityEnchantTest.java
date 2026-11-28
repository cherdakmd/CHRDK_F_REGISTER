package ru.cherdakmd.chrdkenchants;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.AreaDamageContext;
import static org.mockito.Mockito.*;

class AreaAbilityEnchantTest {
    @Test void areaDamageDoesNotTriggerWeaponModifiersOrSecondaryWeaponEffects() {
        var plugin=mock(EnchantsPlugin.class); var attacker=mock(Player.class); var target=mock(Player.class);
        when(attacker.getUniqueId()).thenReturn(UUID.randomUUID()); when(target.getUniqueId()).thenReturn(UUID.randomUUID());
        var event=mock(EntityDamageByEntityEvent.class); when(event.getDamager()).thenReturn(attacker); when(event.getEntity()).thenReturn(target);
        var melee=new MeleeEffectsListener(plugin); var polarity=new PolarityEffectsListener(plugin);
        AreaDamageContext.run(attacker,target,()->{
            melee.onEntityDamageByEntity(event); melee.onConfirmedHit(event); polarity.onMelee(event);
        });
        verifyNoInteractions(plugin);
        verify(event,never()).setDamage(anyDouble());
        verify(target,never()).damage(anyDouble(),any(org.bukkit.entity.Entity.class));
    }
}
