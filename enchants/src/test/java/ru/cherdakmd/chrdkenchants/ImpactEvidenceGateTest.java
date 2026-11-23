package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ImpactEvidenceGateTest {
    static final class Clock implements ImpactEvidenceGate.Scheduler {
        final List<Runnable> tasks=new ArrayList<>();
        public ImpactEvidenceGate.Handle submit(Runnable action){
            var cancelled=new AtomicBoolean();tasks.add(()->{if(!cancelled.get())action.run();});return ()->cancelled.set(true);
        }
        void tick(){var old=List.copyOf(tasks);tasks.clear();old.forEach(Runnable::run);}
    }
    @Test void damageAndCollisionCanArriveInEitherOrderAndCommitOnlyOnce() {
        for(boolean damageFirst:List.of(false,true)) {
            var clock=new Clock();var gate=new ImpactEvidenceGate<String>(clock,10);var fired=new AtomicInteger();
            if(damageFirst){assertTrue(gate.damage("a",()->true));assertTrue(gate.collision("a",true,()->true,fired::incrementAndGet));}
            else {assertTrue(gate.collision("a",true,()->true,fired::incrementAndGet));assertTrue(gate.damage("a",()->true));}
            assertEquals(0,fired.get());clock.tick();assertEquals(1,fired.get());clock.tick();assertEquals(1,fired.get());assertEquals(0,gate.size());
        }
    }
    @Test void cancellationMissingEvidenceOrMismatchedVictimNeverTriggerAreaEffect() {
        var clock=new Clock();var gate=new ImpactEvidenceGate<String>(clock,10);var fire=new AtomicInteger();
        var valid=new AtomicBoolean(true);gate.damage("arrow:one",valid::get);gate.collision("arrow:one",true,()->true,fire::incrementAndGet);valid.set(false);
        gate.damage("arrow:two",()->true);gate.collision("arrow:three",true,()->true,fire::incrementAndGet);
        var collisionValid=new AtomicBoolean(true);gate.damage("arrow:four",()->true);gate.collision("arrow:four",true,collisionValid::get,fire::incrementAndGet);collisionValid.set(false);
        clock.tick();assertEquals(0,fire.get());assertEquals(0,gate.size());
    }
    @Test void blockCollisionDoesNotInventDamageAndLateCancellationWins() {
        var clock=new Clock();var gate=new ImpactEvidenceGate<String>(clock,10);var fire=new AtomicInteger();var cancelled=new AtomicBoolean();
        gate.collision("block",false,()->!cancelled.get(),fire::incrementAndGet);cancelled.set(true);clock.tick();assertEquals(0,fire.get());
        gate.collision("other-block",false,()->true,fire::incrementAndGet);clock.tick();assertEquals(1,fire.get());
    }
    @Test void duplicateEvidenceFailsClosedAndCapacityAndShutdownAreBounded() {
        var clock=new Clock();var gate=new ImpactEvidenceGate<String>(clock,1);var fire=new AtomicInteger();
        assertTrue(gate.damage("a",()->true));assertFalse(gate.damage("a",()->true));
        assertFalse(gate.collision("b",false,()->true,fire::incrementAndGet));
        assertFalse(gate.collision("a",true,()->true,fire::incrementAndGet));clock.tick();assertEquals(0,fire.get());
        assertTrue(gate.collision("c",false,()->true,fire::incrementAndGet));gate.clear();clock.tick();assertEquals(0,fire.get());assertEquals(0,gate.size());
    }
    @Test void duplicateCollisionsOrDifferentEvidenceKindFailClosed() {
        var clock=new Clock();var gate=new ImpactEvidenceGate<String>(clock,10);var fire=new AtomicInteger();
        assertTrue(gate.collision("a",true,()->true,fire::incrementAndGet));assertFalse(gate.collision("a",true,()->true,fire::incrementAndGet));
        assertTrue(gate.collision("b",false,()->true,fire::incrementAndGet));assertFalse(gate.damage("b",()->true));
        clock.tick();assertEquals(0,fire.get());
    }
}
