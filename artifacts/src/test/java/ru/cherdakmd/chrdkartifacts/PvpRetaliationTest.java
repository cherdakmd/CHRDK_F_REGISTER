package ru.cherdakmd.chrdkartifacts;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.RetaliationDamageContext;
import static org.mockito.Mockito.*;

class PvpRetaliationTest {
    @Test void zeroNonFiniteAndReflectedHitsCannotTriggerRetaliation() {
        var plugin=mock(ArtifactsPlugin.class); var listener=new ArtifactEventListener(plugin);
        var source=mock(Player.class); var target=mock(Player.class); var event=mock(EntityDamageByEntityEvent.class);
        when(source.getUniqueId()).thenReturn(UUID.randomUUID()); when(target.getUniqueId()).thenReturn(UUID.randomUUID());
        when(event.getDamager()).thenReturn(source); when(event.getEntity()).thenReturn(target);
        for(double damage:new double[]{0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY}) {
            when(event.getFinalDamage()).thenReturn(damage); listener.onRetaliate(event);
        }
        when(event.getFinalDamage()).thenReturn(2.0);
        RetaliationDamageContext.run(source,target,()->listener.onRetaliate(event));
        verifyNoInteractions(plugin); verify(source,never()).damage(anyDouble(),any(org.bukkit.entity.Entity.class));
    }

    @Test void cloakSendsAttributedDamageInRetaliationScope() {
        var plugin=mock(ArtifactsPlugin.class); var listener=new ArtifactEventListener(plugin);
        var source=mock(Player.class); var target=mock(Player.class);
        when(source.getUniqueId()).thenReturn(UUID.randomUUID()); when(target.getUniqueId()).thenReturn(UUID.randomUUID());
        var event=mock(EntityDamageByEntityEvent.class); when(event.getDamager()).thenReturn(source); when(event.getEntity()).thenReturn(target);
        doAnswer(call->{
            org.junit.jupiter.api.Assertions.assertTrue(RetaliationDamageContext.isRetaliation(event));
            listener.onRetaliate(event); return null;
        }).when(target).damage(anyDouble(),eq(source));
        listener.retaliate(source,target);
        verify(target).damage(eq(ArtifactEventListener.RETALIATE_DAMAGE),eq(source));
        org.junit.jupiter.api.Assertions.assertFalse(RetaliationDamageContext.isRetaliation(event));
    }
}
