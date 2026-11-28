package ru.cherdakmd.chrdkskills;

import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.entity.Monster;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.AreaDamageContext;
import ru.cherdakmd.chrdkregister.api.AreaPvpGate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BranchAreaDamageTest {
    @Test void mobsKeepOriginalDamagePlayersNeedPermissionAndKeepAttribution() {
        var caster = mock(Player.class); var enemy = mock(Player.class); var ally = mock(Player.class);
        var mob = mock(Monster.class); var pdc = mock(PersistentDataContainer.class);
        when(mob.getPersistentDataContainer()).thenReturn(pdc);
        when(caster.getUniqueId()).thenReturn(UUID.randomUUID()); when(enemy.getUniqueId()).thenReturn(UUID.randomUUID());
        when(ally.getUniqueId()).thenReturn(UUID.randomUUID());
        when(caster.getNearbyEntities(3,3,3)).thenReturn(List.of(mob,enemy,ally));
        var event = mock(EntityDamageByEntityEvent.class); when(event.getDamager()).thenReturn(caster); when(event.getEntity()).thenReturn(enemy);
        doAnswer(call -> { assertTrue(AreaDamageContext.isAbilityDamage(event)); return null; }).when(enemy).damage(12, caster);
        try (var gate = mockStatic(AreaPvpGate.class)) {
            gate.when(() -> AreaPvpGate.allows(caster,enemy)).thenReturn(true);
            gate.when(() -> AreaPvpGate.allows(caster,ally)).thenReturn(false);
            BranchAbilities.damageAround(caster,3,12);
        }
        verify(mob).damage(12); verify(enemy).damage(12,caster); verify(ally,never()).damage(anyDouble(),any(org.bukkit.entity.Entity.class));
        assertFalse(AreaDamageContext.isAbilityDamage(event));
    }
}
