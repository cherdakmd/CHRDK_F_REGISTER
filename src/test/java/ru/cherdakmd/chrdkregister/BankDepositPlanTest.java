package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class BankDepositPlanTest {
    @Test void capRefusalKeepsExactBlockCountsInsteadOfMakingRefundDiamonds() {
        int[] counts=new int[36]; long[] values=new long[36]; counts[4]=2; values[4]=9;
        int[] original=counts.clone();
        assertNull(BankDepositPlan.plan(counts,values,-1,-1,BankStore.MAX_BALANCE-17)); assertArrayEquals(original,counts);
        var plan=BankDepositPlan.plan(counts,values,-1,-1,BankStore.MAX_BALANCE-18);
        assertEquals(18,plan.credit()); assertEquals(0,plan.remaining()[4]); assertEquals(2,counts[4]);
    }
    @Test void selectedHotbarDoesNotTouchOtherStorageAndExactAmountDoesNotBreakBlocks() {
        int[] counts=new int[36]; long[] values=new long[36]; counts[0]=10; values[0]=1; counts[5]=3; values[5]=9;
        var hand=BankDepositPlan.plan(counts,values,5,-1,0); assertEquals(27,hand.credit()); assertEquals(10,hand.remaining()[0]);
        var exact=BankDepositPlan.plan(counts,values,-1,7,0); assertEquals(3,exact.remaining()[0]); assertEquals(3,exact.remaining()[5]);
        assertNull(BankDepositPlan.plan(counts,values,-1,11,0)); assertEquals(10,counts[0]);
        assertNull(BankDepositPlan.plan(new int[37],new long[37],-1,-1,0));
    }
    @Test void bankExchangePreflightNeverRunsInventoryCallbackOnRefusal(@TempDir Path dir) {
        var bank=new BankStore(dir,Logger.getAnonymousLogger()); var id=UUID.randomUUID(); bank.deposit(id,"Buyer",BankStore.MAX_BALANCE);
        var changed=new AtomicBoolean(); assertFalse(bank.exchange(id,"Buyer",0,1,()->changed.set(true))); assertFalse(changed.get());
    }
    @Test void productionDepositHasNoRefundOrDropAndUsesDeepStoragePlan() throws Exception {
        String source=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/BankService.java"));
        String deposit=source.substring(source.indexOf("public long deposit(Player"),source.indexOf("/* --------------------------------- снятие"));
        assertTrue(deposit.contains("BankCashInventory.deposit")); assertTrue(deposit.contains("store.prepareCash"));
        assertFalse(deposit.contains("setStorageContents(before)")); assertTrue(deposit.contains("getHeldItemSlot"));
        assertFalse(deposit.contains("refund(")); assertFalse(deposit.contains("dropItem")); assertFalse(deposit.contains("getItemInOffHand"));
    }
}
