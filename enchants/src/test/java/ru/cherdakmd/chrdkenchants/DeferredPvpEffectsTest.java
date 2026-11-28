package ru.cherdakmd.chrdkenchants;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeferredPvpEffectsTest {
    @Test void waitsForAllListenersAndChecksFinalDamageAndFreshEvidence() {
        var tasks = new ArrayList<Runnable>(); var gate = new DeferredPvpEffects(tasks::add);
        var event = mock(EntityDamageByEntityEvent.class); when(event.getFinalDamage()).thenReturn(4.0);
        var applied = new AtomicInteger();
        assertTrue(gate.submit(event, () -> true, applied::incrementAndGet));
        assertEquals(0, applied.get()); when(event.isCancelled()).thenReturn(true); tasks.removeFirst().run();
        assertEquals(0, applied.get()); assertEquals(0,gate.pending());
        when(event.isCancelled()).thenReturn(false); assertTrue(gate.submit(event, () -> true, applied::incrementAndGet));
        when(event.getFinalDamage()).thenReturn(Double.NaN); tasks.removeFirst().run(); assertEquals(0,applied.get());
        when(event.getFinalDamage()).thenReturn(4.0); assertTrue(gate.submit(event, () -> false, applied::incrementAndGet));
        tasks.removeFirst().run(); assertEquals(0,applied.get());
        assertTrue(gate.submit(event, () -> true, applied::incrementAndGet)); tasks.removeFirst().run(); assertEquals(1,applied.get());
    }
    @Test void schedulingFailureAndCapacityFailClosedWithoutLeakingTickets() {
        var event=mock(EntityDamageByEntityEvent.class); when(event.getFinalDamage()).thenReturn(1.0);
        var broken=new DeferredPvpEffects(task->{throw new IllegalStateException();});
        assertFalse(broken.submit(event,()->true,()->{})); assertEquals(0,broken.pending());
        var tasks=new ArrayList<Runnable>(); var bounded=new DeferredPvpEffects(tasks::add);
        for(int i=0;i<2048;i++)assertTrue(bounded.submit(event,()->true,()->{}));
        assertFalse(bounded.submit(event,()->true,()->{}));
        tasks.removeFirst().run(); assertTrue(bounded.submit(event,()->true,()->{}));
    }
}
