package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class GuardRatesTest {
    private GuardSettings settings(int ipBurst,int globalBurst,int capacity) {
        var c=new YamlConfiguration();c.set("security.ip-burst",ipBurst);c.set("security.global-burst",globalBurst);
        c.set("security.max-addresses",capacity);return GuardSettings.parse(c);
    }
    @Test void perAddressBurstRefillsWithoutPermanentBans() {
        var rates=new GuardRates(settings(2,60,20),0);
        assertEquals(GuardRates.Admission.ALLOW,rates.enter("ip",0));
        assertEquals(GuardRates.Admission.ALLOW,rates.enter("ip",0));
        assertEquals(GuardRates.Admission.IP_RATE,rates.enter("ip",0));
        assertEquals(GuardRates.Admission.ALLOW,rates.enter("ip",3_000_000_001L));
    }
    @Test void blockedIpDoesNotSpendOtherPlayersGlobalQuota() {
        var rates=new GuardRates(settings(1,2,20),0);
        assertEquals(GuardRates.Admission.ALLOW,rates.enter("a",0));
        for(int i=0;i<100;i++)assertEquals(GuardRates.Admission.IP_RATE,rates.enter("a",0));
        assertEquals(GuardRates.Admission.ALLOW,rates.enter("b",0));
        assertEquals(GuardRates.Admission.GLOBAL_RATE,rates.enter("c",0));
    }
    @Test void distributedBurstAndMemoryAreBoundedAndIdleEntriesExpire() {
        var rates=new GuardRates(settings(6,100,16),0);
        for(int i=0;i<16;i++)assertEquals(GuardRates.Admission.ALLOW,rates.enter("ip"+i,0));
        assertEquals(GuardRates.Admission.CAPACITY,rates.enter("extra",0));assertEquals(16,rates.tracked());
        assertEquals(GuardRates.Admission.ALLOW,rates.enter("extra",GuardRates.IDLE_NANOS+1));assertEquals(1,rates.tracked());
    }
    @Test void capacityRejectionsDoNotStarveKnownAddresses() {
        var rates=new GuardRates(settings(6,17,16),0);
        for(int i=0;i<16;i++)assertEquals(GuardRates.Admission.ALLOW,rates.enter("ip"+i,0));
        for(int i=0;i<100;i++)assertEquals(GuardRates.Admission.CAPACITY,rates.enter("extra",0));
        assertEquals(GuardRates.Admission.ALLOW,rates.enter("ip0",0));
    }
    @Test void saturatedGlobalQuotaDoesNotAllocateNewAddressEntries() {
        var rates=new GuardRates(settings(6,1,16),0);rates.enter("first",0);
        for(int i=0;i<10000;i++)assertEquals(GuardRates.Admission.GLOBAL_RATE,rates.enter("ip"+i,0));
        assertEquals(1,rates.tracked());
    }
    @Test void concurrentLoginsCannotOverspendTheSharedBucket() throws Exception {
        var rates=new GuardRates(settings(1000,17,1000),0);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs=new ArrayList<Callable<GuardRates.Admission>>();
            for(int i=0;i<200;i++){String ip="ip"+i;jobs.add(()->rates.enter(ip,0));}
            int allowed=0;for(var result:pool.invokeAll(jobs))if(result.get()==GuardRates.Admission.ALLOW)allowed++;
            assertEquals(17,allowed);assertEquals(17,rates.tracked());
        }
    }
    @Test void clockRollbackCannotRefillAndLongIdleNeverAccumulatesAboveBurst() {
        var bucket=new GuardRates.Bucket(2,1,10);
        assertTrue(bucket.take(10));assertTrue(bucket.take(10));assertFalse(bucket.take(0));
        assertTrue(bucket.take(10_000_000_000L));assertTrue(bucket.take(10_000_000_000L));assertFalse(bucket.take(10_000_000_000L));
    }
    @Test void cleanupDoesNotResetVerySlowCustomRateBeforeFullRefill() {
        var c=new YamlConfiguration();c.set("security.ip-burst",10);c.set("security.ip-refill-seconds",300);
        var rates=new GuardRates(GuardSettings.parse(c),0);
        for(int i=0;i<10;i++)assertEquals(GuardRates.Admission.ALLOW,rates.enter("ip",0));
        assertEquals(GuardRates.Admission.ALLOW,rates.enter("ip",GuardRates.IDLE_NANOS+1));
        assertEquals(GuardRates.Admission.IP_RATE,rates.enter("ip",GuardRates.IDLE_NANOS+1));
    }
}
