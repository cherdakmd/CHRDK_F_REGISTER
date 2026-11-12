package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ShopWelcomeGiftTest {
    @TempDir Path dir;
    private static final UUID STEVE=UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MARKET=UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final class Bank implements ShopWelcomeGift.Bank {
        final Map<UUID,Long> balances=new HashMap<>();
        final Map<UUID,Long> receipts=new HashMap<>();
        boolean available=true;
        boolean creditAllowed=true;
        int calls;
        public boolean available(UUID market){return available&&MARKET.equals(market);}
        public boolean credit(UUID market,UUID player,String key,long amount) {
            calls++;
            if(!available(market)||!creditAllowed||!ShopWelcomeGift.RECEIPT_KEY.equals(key))return false;
            long previous=receipts.getOrDefault(player,0L);
            if(previous>amount)return false;
            receipts.put(player,amount);
            balances.merge(player,amount-previous,Long::sum);
            return true;
        }
    }
    private ShopWelcomeGift gift(){return new ShopWelcomeGift(dir,Logger.getAnonymousLogger(),true,10000);}

    @Test
    void doubleClickAndRestartCreditExactlyOnce() {
        Bank bank=new Bank(); ShopWelcomeGift first=gift();
        assertEquals(ShopWelcomeGift.Status.CLAIMED,first.claim(STEVE,MARKET,bank));
        assertEquals(ShopWelcomeGift.Status.ALREADY,first.claim(STEVE,MARKET,bank));
        ShopWelcomeGift restarted=gift();
        assertTrue(restarted.claimed(STEVE));
        assertEquals(ShopWelcomeGift.Status.ALREADY,restarted.claim(STEVE,MARKET,bank));
        assertEquals(10000,bank.balances.get(STEVE));
        assertEquals(10000,bank.receipts.get(STEVE));
    }

    @Test
    void failedBankRetryCompletesSameDurableObligation() {
        Bank bank=new Bank(); bank.creditAllowed=false;
        ShopWelcomeGift first=gift();
        assertEquals(ShopWelcomeGift.Status.UNAVAILABLE,first.claim(STEVE,MARKET,bank));
        assertTrue(first.claimed(STEVE),"обязательство сохранено до неуспешного денежного вызова");
        bank.creditAllowed=true;
        ShopWelcomeGift restarted=gift();
        assertEquals(ShopWelcomeGift.Status.ALREADY,restarted.claim(STEVE,MARKET,bank));
        assertEquals(10000,bank.balances.get(STEVE));
        assertEquals(ShopWelcomeGift.Status.ALREADY,restarted.claim(STEVE,MARKET,bank));
        assertEquals(10000,bank.balances.get(STEVE));
    }

    @Test
    void missingBankReceiptIsReconciledInsteadOfDuplicated() {
        Bank bank=new Bank(); bank.credit(MARKET,STEVE,ShopWelcomeGift.RECEIPT_KEY,10000);
        assertEquals(ShopWelcomeGift.Status.CLAIMED,gift().claim(STEVE,MARKET,bank));
        assertEquals(10000,bank.balances.get(STEVE));
    }

    @Test
    void unwritableOrCorruptJournalFailsClosed() throws Exception {
        Path file=Files.writeString(dir.resolve("not-a-directory"),"x");
        var broken=new ShopWelcomeGift(file,Logger.getAnonymousLogger(),true,10000);
        Bank bank=new Bank();
        assertEquals(ShopWelcomeGift.Status.UNAVAILABLE,broken.claim(STEVE,MARKET,bank));
        assertTrue(bank.balances.isEmpty());

        Files.writeString(dir.resolve(ShopWelcomeGift.FILE_NAME),"{broken");
        var corrupt=gift();
        assertEquals(ShopWelcomeGift.Status.UNAVAILABLE,corrupt.claim(STEVE,MARKET,bank));
        assertTrue(bank.balances.isEmpty());
    }

    @Test
    void disabledGiftNeverCredits() {
        var disabled=new ShopWelcomeGift(dir,Logger.getAnonymousLogger(),false,10000);
        Bank bank=new Bank();
        assertEquals(ShopWelcomeGift.Status.UNAVAILABLE,disabled.claim(STEVE,MARKET,bank));
        assertTrue(bank.balances.isEmpty());
    }
}
