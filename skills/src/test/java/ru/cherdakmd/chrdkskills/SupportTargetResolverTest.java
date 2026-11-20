package ru.cherdakmd.chrdkskills;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static ru.cherdakmd.chrdkskills.SupportTargetRules.*;

class SupportTargetResolverTest {
    private Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (p, m, a) -> { if (m.getName().equals("getUniqueId")) return id; throw new AssertionError(m.getName()); });
    }
    @Test void combinesFreshCandidateRelationsAndExplicitPermission() {
        var caster=player(UUID.randomUUID()); var target=player(UUID.randomUUID());
        var calls=new AtomicInteger();
        var selected=SupportTargetResolver.resolve(caster, p->new SupportAim.Candidate(target, 64),
                (a,b)->{ calls.incrementAndGet(); assertEquals(caster.getUniqueId(),a); assertEquals(target.getUniqueId(),b);
                    return new SupportRelations.Snapshot(GuildRelation.OTHER,GroupRelation.SAME_GROUP); },
                (a,b)->{ calls.incrementAndGet(); assertSame(caster,a); assertSame(target,b); return Protection.ALLOW; });
        assertSame(target,selected); assertEquals(2,calls.get());
        assertSame(caster,SupportTargetResolver.resolve(caster,p->new SupportAim.Candidate(target,1),
                (a,b)->new SupportRelations.Snapshot(GuildRelation.ACTIVE_WAR,GroupRelation.SAME_GROUP), (a,b)->Protection.ALLOW));
        assertSame(caster,SupportTargetResolver.resolve(caster,p->new SupportAim.Candidate(target,1),
                (a,b)->new SupportRelations.Snapshot(GuildRelation.SAME_GUILD,GroupRelation.UNKNOWN), (a,b)->Protection.UNKNOWN));
        assertSame(caster,SupportTargetResolver.resolve(caster,p->new SupportAim.Candidate(target,65),
                (a,b)->new SupportRelations.Snapshot(GuildRelation.SAME_GUILD,GroupRelation.DIFFERENT), (a,b)->Protection.ALLOW));
    }
    @Test void absenceNullOrErrorsNeverSelectSomeoneElse() {
        var caster=player(UUID.randomUUID()); var target=player(UUID.randomUUID());
        var aim=(java.util.function.Function<Player,SupportAim.Candidate>)(p->new SupportAim.Candidate(target,1));
        assertSame(caster,SupportTargetResolver.resolve(caster,null,(a,b)->null,(a,b)->Protection.ALLOW));
        assertSame(caster,SupportTargetResolver.resolve(caster,p->null,(a,b)->null,(a,b)->Protection.ALLOW));
        assertSame(caster,SupportTargetResolver.resolve(caster,aim,(a,b)->null,(a,b)->Protection.ALLOW));
        assertSame(caster,SupportTargetResolver.resolve(caster,aim,(a,b)->{ throw new IllegalStateException(); },(a,b)->Protection.ALLOW));
        assertSame(caster,SupportTargetResolver.resolve(caster,aim,
                (a,b)->new SupportRelations.Snapshot(GuildRelation.SAME_GUILD,GroupRelation.DIFFERENT),
                (a,b)->{ throw new LinkageError(); }));
        assertSame(caster,SupportTargetResolver.resolve(caster,p->new SupportAim.Candidate(caster,1),
                (a,b)->null,(a,b)->Protection.ALLOW));
        assertNull(SupportTargetResolver.resolve(null,aim,(a,b)->null,(a,b)->Protection.ALLOW));
    }
}
