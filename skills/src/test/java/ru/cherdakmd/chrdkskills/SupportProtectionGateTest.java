package ru.cherdakmd.chrdkskills;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static ru.cherdakmd.chrdkskills.SupportTargetRules.Protection;
import static ru.cherdakmd.chrdkskills.SupportProtectionPolicy.Verdict;

class SupportProtectionGateTest {
    private Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (p,m,a)->{ throw new AssertionError("Gate must not mutate or inspect players"); });
    }
    private SupportProtectionGate.Source source(String name, boolean enabled, SupportProtectionPolicy policy) {
        return new SupportProtectionGate.Source(name,enabled,policy);
    }
    @Test void allRequiredAndAdditionalProvidersMustAllowEveryTime() {
        var caster=player(); var target=player(); var calls=new AtomicInteger();
        SupportProtectionPolicy allow=(a,b,ability)->{ assertSame(caster,a); assertSame(target,b); assertEquals(BranchAbility.GRACE,ability); calls.incrementAndGet(); return Verdict.ALLOW; };
        var entries=List.of(source("Claims",true,allow),source("Regions",true,allow),source("Extra",true,allow));
        assertEquals(Protection.ALLOW,SupportProtectionGate.evaluate(caster,target,BranchAbility.GRACE,Set.of("claims","REGIONS"),entries));
        assertEquals(Protection.ALLOW,SupportProtectionGate.evaluate(caster,target,BranchAbility.GRACE,Set.of("Claims","Regions"),entries));
        assertEquals(6,calls.get());
    }
    @Test void missingOrUncertainSourcesNeverGrant() {
        var caster=player(); var target=player(); SupportProtectionPolicy allow=(a,b,c)->Verdict.ALLOW;
        var present=source("Claims",true,allow);
        assertEquals(Protection.UNKNOWN,SupportProtectionGate.evaluate(caster,target,BranchAbility.GRACE,Set.of(),List.of(present)));
        assertEquals(Protection.UNKNOWN,SupportProtectionGate.evaluate(caster,target,BranchAbility.GRACE,Set.of("Claims","Region"),List.of(present)));
        assertEquals(Protection.UNKNOWN,SupportProtectionGate.evaluate(caster,target,BranchAbility.GRACE,Set.of("Claims"),List.of(source("Claims",false,allow))));
        assertEquals(Protection.UNKNOWN,SupportProtectionGate.evaluate(caster,target,BranchAbility.GRACE,Set.of("Claims"),List.of(source("Claims",true,(a,b,c)->null))));
        assertEquals(Protection.UNKNOWN,SupportProtectionGate.evaluate(caster,target,BranchAbility.GRACE,Set.of("Claims"),List.of(source("Claims",true,(a,b,c)->{ throw new IllegalStateException(); }))));
        assertEquals(Protection.UNKNOWN,SupportProtectionGate.evaluate(caster,target,BranchAbility.GRACE,Set.of("Claims"),List.of(source("Claims",true,(a,b,c)->{ throw new LinkageError(); }))));
        assertEquals(Protection.UNKNOWN,SupportProtectionGate.evaluate(caster,caster,BranchAbility.GRACE,Set.of("Claims"),List.of(present)));
        assertEquals(Protection.UNKNOWN,SupportProtectionGate.evaluate(caster,target,BranchAbility.CLEAVE,Set.of("Claims"),List.of(present)));
    }
    @Test void anyProviderCanDenyAndUnknownCannotBeTreatedAsAllow() {
        var caster=player(); var target=player();
        var yes=source("Claims",true,(a,b,c)->Verdict.ALLOW);
        var no=source("Region",true,(a,b,c)->Verdict.DENY);
        var unknown=source("Region",true,(a,b,c)->Verdict.UNKNOWN);
        assertEquals(Protection.DENY,SupportProtectionGate.evaluate(caster,target,BranchAbility.PURGE,Set.of("Claims","Region"),List.of(yes,no)));
        assertEquals(Protection.UNKNOWN,SupportProtectionGate.evaluate(caster,target,BranchAbility.PURGE,Set.of("Claims","Region"),List.of(yes,unknown)));
    }
}
