package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.EconomyConfirmations;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EconomyConfirmationsTest {
    record Quote(UUID recipient,long amount,long tax) { }
    @Test void consentBindsOwnerSessionRecipientAmountAndTaxAndIsSingleUse() {
        var c=new EconomyConfirmations();var owner=UUID.randomUUID();Object session=new Object();
        var quote=new Quote(UUID.randomUUID(),100,2);
        assertFalse(c.consume(owner,session,quote,0));
        assertTrue(c.offer(owner,session,quote,0));
        assertFalse(c.consume(UUID.randomUUID(),session,quote,1));
        assertTrue(c.consume(owner,session,new Quote(quote.recipient(),100,2),1));
        assertFalse(c.consume(owner,session,quote,2));
        for(Object changed:new Object[]{new Quote(UUID.randomUUID(),100,2),new Quote(quote.recipient(),101,2),new Quote(quote.recipient(),100,3),"withdraw"}) {
            assertTrue(c.offer(owner,session,quote,10));
            assertFalse(c.consume(owner,session,changed,11));
            assertFalse(c.consume(owner,session,quote,12));
        }
        assertTrue(c.offer(owner,session,quote,20));
        assertFalse(c.consume(owner,new Object(),quote,21));
        assertFalse(c.consume(owner,session,quote,22));
    }
    @Test void exactExpiryBackwardClockAndNanoTimeWrapAreSafe() {
        var c=new EconomyConfirmations();var owner=UUID.randomUUID();var session=new Object();
        long ttl=EconomyConfirmations.TTL_NANOS;
        c.offer(owner,session,"q",0);assertTrue(c.consume(owner,session,"q",ttl-1));
        c.offer(owner,session,"q",0);assertFalse(c.consume(owner,session,"q",ttl));
        c.offer(owner,session,"q",10);assertFalse(c.consume(owner,session,"q",9));
        long start=Long.MAX_VALUE-5;
        c.offer(owner,session,"q",start);assertTrue(c.consume(owner,session,"q",start+10));
        c.offer(owner,session,"q",Long.MIN_VALUE);assertTrue(c.consume(owner,session,"q",Long.MIN_VALUE+1));
    }
    @Test void memoryIsBoundedAndExpiredCapacityIsReclaimed() {
        var c=new EconomyConfirmations();var session=new Object();var first=new UUID(0,0);
        for(int i=0;i<EconomyConfirmations.MAX_ENTRIES;i++) assertTrue(c.offer(new UUID(0,i),session,"q",0));
        var extra=new UUID(1,0);
        assertFalse(c.offer(extra,session,"q",1));assertEquals(EconomyConfirmations.MAX_ENTRIES,c.size());
        assertTrue(c.offer(first,session,"replacement",1));
        assertFalse(c.consume(first,session,"q",2));
        assertTrue(c.offer(extra,session,"q",2));
        assertTrue(c.offer(first,session,"q",EconomyConfirmations.TTL_NANOS+2));assertEquals(1,c.size());
        c.clear();assertEquals(0,c.size());assertFalse(c.consume(first,session,"q",EconomyConfirmations.TTL_NANOS+3));
    }
}
