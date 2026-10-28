package ru.cherdakmd.chrdkregister;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class PaidActionRunnerTest {
    @TempDir Path dir;
    final UUID payer=UUID.randomUUID();
    final UUID id=UUID.randomUUID();
    PaidActionStore ready() throws Exception {
        var s=new PaidActionStore(dir); s.markRegistered(); var a=new PaidActionStore.Action(id,payer,payer,7,"rtp","world:1:64:2",PaidActionStore.Phase.READY);
        s.publish(s.snapshot(),s.preview(a)); return s;
    }
    @Test void cancelledNativeTeleportRefundsOnceAndCannotBeRetried() throws Exception {
        var s=ready(); var calls=new AtomicInteger();
        assertFalse(PaidActions.execute(s,id,()->{calls.incrementAndGet();return false;}));
        assertFalse(PaidActions.execute(s,id,()->{calls.incrementAndGet();return false;})); assertEquals(1,calls.get()); assertEquals(7,new PaidActionStore(dir).refunds().get(payer));
    }
    @Test void exceptionAfterNativeMayHaveSideEffectSoHoldsInsteadOfRefundingOrReplaying() throws Exception {
        var s=ready(); var calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->PaidActions.execute(s,id,()->{calls.incrementAndGet();throw new IllegalStateException("unknown native result");}));
        var restored=new PaidActionStore(dir); assertFalse(restored.refundReady(id)); assertFalse(PaidActions.execute(restored,id,()->{calls.incrementAndGet();return true;})); assertEquals(1,calls.get()); assertTrue(restored.refunds().isEmpty());
    }
    @Test void successfulNativeTeleportConsumesReserveNotRefundAndNoReplay() throws Exception {
        var s=ready(); assertTrue(PaidActions.execute(s,id,()->true)); assertTrue(new PaidActionStore(dir).actions().isEmpty()); assertTrue(s.refunds().isEmpty());
        assertFalse(PaidActions.execute(s,id,()->{fail("must not call native twice"); return true;}));
    }
    @Test void cannotCallNativeUntilExecutingFenceIsDurable() throws Exception {
        var s=ready(); Files.createDirectory(dir.resolve("paid-actions.json.tmp"));
        assertThrows(java.io.IOException.class,()->PaidActions.execute(s,id,()->{fail("no durable executing fence"); return true;}));
        assertEquals(PaidActionStore.Phase.READY,new PaidActionStore(dir).actions().getFirst().phase());
    }
}
