package ru.cherdakmd.chrdkregister.api;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetaliationDamageContextTest {
    @Test void matchesOnlyCurrentPairAndAlwaysUnwinds() {
        var a=mock(Player.class); var b=mock(Player.class); var c=mock(Player.class);
        when(a.getUniqueId()).thenReturn(UUID.randomUUID()); when(b.getUniqueId()).thenReturn(UUID.randomUUID());
        when(c.getUniqueId()).thenReturn(UUID.randomUUID());
        var hit=mock(EntityDamageByEntityEvent.class); when(hit.getDamager()).thenReturn(a); when(hit.getEntity()).thenReturn(b);
        var reversed=mock(EntityDamageByEntityEvent.class); when(reversed.getDamager()).thenReturn(b); when(reversed.getEntity()).thenReturn(a);
        assertFalse(RetaliationDamageContext.isRetaliation(hit));
        assertThrows(IllegalStateException.class, () -> RetaliationDamageContext.run(a,b,()->{
            assertTrue(RetaliationDamageContext.isRetaliation(hit));
            assertFalse(RetaliationDamageContext.isRetaliation(reversed));
            RetaliationDamageContext.run(a,c,()->assertFalse(RetaliationDamageContext.isRetaliation(hit)));
            assertTrue(RetaliationDamageContext.isRetaliation(hit));
            throw new IllegalStateException();
        }));
        assertFalse(RetaliationDamageContext.isRetaliation(hit));
    }
}
