package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class GuardDefenseTest {
    private static final long SECOND=1_000_000_000L;
    private final YamlConfiguration config=new YamlConfiguration();
    private GuardSettings settings() { return GuardSettings.parse(config); }
    private GuardDefense.Key key(String ip) { return new GuardDefense.Key(UUID.randomUUID(),ip); }
    private void attack(GuardDefense defense) {
        for(int window=0;window<2;window++)
            for(int i=0;i<30;i++)defense.slotRejected("ip"+(i%8),window*10*SECOND);
        assertTrue(defense.snapshot(20*SECOND).attack());
    }
    @Test void oneAddressCannotActivateAttackModeAndSparseWindowsDoNotAccumulate() {
        var pressure=new GuardPressure(0);var tuning=settings().defense();
        for(int w=0;w<5;w++)for(int i=0;i<100;i++)pressure.rejection(tuning,"one",w*10*SECOND);
        assertFalse(pressure.active(tuning,50*SECOND));
        var sparse=new GuardPressure(0);
        for(int i=0;i<30;i++)sparse.rejection(tuning,"ip"+(i%8),0);
        assertFalse(sparse.active(tuning,30*SECOND));
        for(int i=0;i<30;i++)sparse.rejection(tuning,"ip"+(i%8),30*SECOND);
        assertFalse(sparse.active(tuning,40*SECOND));
    }
    @Test void sustainedPressureEntersAndQuietPeriodAutomaticallyLeavesAttackMode() {
        var defense=new GuardDefense(settings(),0);attack(defense);
        assertTrue(defense.snapshot(79*SECOND).attack());assertFalse(defense.snapshot(80*SECOND).attack());
    }
    @Test void renewedQualifiedWindowExtendsAttackButSingleIpDoesNot() {
        var defense=new GuardDefense(settings(),0);attack(defense);
        for(int i=0;i<30;i++)defense.slotRejected("ip"+(i%8),25*SECOND);
        assertTrue(defense.snapshot(30*SECOND).attack());
        for(int i=0;i<200;i++)defense.slotRejected("single",70*SECOND);
        assertFalse(defense.snapshot(90*SECOND).attack());
    }
    @Test void attackWindowKeepsOnlyABoundedSetOfAddresses() {
        var pressure=new GuardPressure(0);for(int i=0;i<10000;i++)pressure.rejection(settings().defense(),"ip"+i,0);
        assertEquals(256,pressure.addressCount());
    }
    @Test void automaticModeCanBeDisabledWithoutDisablingBasicLimits() {
        config.set("security.defense.automatic",false);config.set("security.ip-burst",1);
        var defense=new GuardDefense(settings(),0);
        for(int w=0;w<3;w++)for(int i=0;i<30;i++)defense.slotRejected("ip"+(i%8),w*10*SECOND);
        assertFalse(defense.snapshot(30*SECOND).attack());var key=key("a");
        assertEquals(GuardRates.Admission.ALLOW,defense.enter(key,30*SECOND));
        assertEquals(GuardRates.Admission.IP_RATE,defense.enter(key,30*SECOND));
    }
    @Test void unknownConnectionsAreTightenedButRecentPairStillRequiresCorrectIpAndUuid() {
        config.set("security.defense.attack-burst",1);config.set("security.global-burst",100);
        var defense=new GuardDefense(settings(),0);var known=key("known");defense.authenticated(known,0);attack(defense);
        assertEquals(GuardRates.Admission.ALLOW,defense.enter(key("unknown1"),20*SECOND));
        assertEquals(GuardRates.Admission.ATTACK_RATE,defense.enter(key("unknown2"),20*SECOND));
        assertEquals(GuardRates.Admission.ALLOW,defense.enter(known,20*SECOND));
        assertEquals(GuardRates.Admission.ATTACK_RATE,defense.enter(new GuardDefense.Key(known.id(),"other"),20*SECOND));
        assertEquals(GuardRates.Admission.ATTACK_RATE,defense.enter(key("known"),20*SECOND));
    }
    @Test void returningPairsHaveABoundedRecoveryLaneWhenOrdinaryQuotaIsEmpty() {
        config.set("security.global-burst",1);config.set("security.defense.recovery-burst",1);
        var defense=new GuardDefense(settings(),0);var known=key("known");defense.authenticated(known,0);
        assertEquals(GuardRates.Admission.ALLOW,defense.enter(key("ordinary"),0));
        assertEquals(GuardRates.Admission.ALLOW,defense.enter(known,0));
        assertEquals(GuardRates.Admission.GLOBAL_RATE,defense.enter(known,0));
        assertFalse(defense.returning(new GuardDefense.Key(known.id(),"different"),0));
    }
    @Test void repeatedStrikesCoolOnlyTheSamePairAndRetriesDoNotExtendPenalty() {
        var defense=new GuardDefense(settings(),0);var offender=key("shared");
        for(int i=0;i<3;i++)defense.strike(offender,0);
        assertEquals(30,defense.cooldownSeconds(offender,0));
        assertEquals(GuardRates.Admission.COOLDOWN,defense.enter(offender,5*SECOND));
        assertEquals(25,defense.cooldownSeconds(offender,5*SECOND));
        assertEquals(GuardRates.Admission.ALLOW,defense.enter(key("shared"),5*SECOND));
        assertEquals(GuardRates.Admission.ALLOW,defense.enter(new GuardDefense.Key(offender.id(),"different"),5*SECOND));
        assertEquals(0,defense.cooldownSeconds(offender,30*SECOND));
        defense.strike(offender,30*SECOND);assertEquals(60,defense.cooldownSeconds(offender,30*SECOND));
    }
    @Test void cooldownIsCappedAndQuietStrikeWindowResetsEscalation() {
        var defense=new GuardDefense(settings(),0);var key=key("ip");
        for(int i=0;i<20;i++)defense.strike(key,0);
        assertEquals(300,defense.cooldownSeconds(key,0));
        defense.strike(key,601*SECOND);assertEquals(0,defense.cooldownSeconds(key,601*SECOND));
    }
    @Test void reloadPreservesCooldownAndAttackHistoryAndExpiryRemovesIdleEntries() {
        var defense=new GuardDefense(settings(),0);var key=key("ip");
        for(int i=0;i<3;i++)defense.strike(key,0);attack(defense);
        defense.configure(settings(),20*SECOND);
        assertEquals(10,defense.cooldownSeconds(key,20*SECOND));assertTrue(defense.snapshot(20*SECOND).attack());
        assertEquals(0,defense.snapshot(601*SECOND).history());
    }
    @Test void recentPriorityExpiresAndNeverLearnsUnknownAddresses() {
        var defense=new GuardDefense(settings(),0);var key=key("ip");defense.authenticated(key,0);
        assertTrue(defense.returning(key,1799*SECOND));assertFalse(defense.returning(key,1800*SECOND));
        defense.authenticated(key("unknown"),1800*SECOND);assertEquals(0,defense.snapshot(1801*SECOND).history());
    }
    @Test void historyCannotGrowBeyondItsConfiguredBound() {
        config.set("security.max-addresses",16);var defense=new GuardDefense(settings(),0);
        for(int i=0;i<1000;i++)defense.strike(key("ip"+i),0);
        assertEquals(16,defense.snapshot(0).history());assertEquals(0,defense.snapshot(601*SECOND).history());
    }
    @Test void concurrentRecoveryCannotOverspendReservedTokens() throws Exception {
        config.set("security.global-burst",1);config.set("security.ip-burst",1000);
        config.set("security.defense.recovery-burst",4);var defense=new GuardDefense(settings(),0);
        var known=key("same");defense.authenticated(known,0);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=new ArrayList<Callable<GuardRates.Admission>>();
            for(int i=0;i<200;i++)tasks.add(()->defense.enter(known,0));
            int accepted=0;for(var result:pool.invokeAll(tasks))if(result.get()==GuardRates.Admission.ALLOW)accepted++;
            assertEquals(5,accepted);
        }
    }
}
