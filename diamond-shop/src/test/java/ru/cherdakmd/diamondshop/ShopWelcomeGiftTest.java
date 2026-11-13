package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ShopWelcomeGiftTest {
    @TempDir Path dir;
    private static final UUID STEVE=UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ALEX=UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MARKET=UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** Семантика BankStore: квитанция одна на ключ, смена владельца запрещена, повтор — доплата разницы. */
    private static final class Bank implements ShopWelcomeGift.Bank {
        static final class Receipt {
            final UUID owner; final long base;
            Receipt(UUID owner,long base){this.owner=owner;this.base=base;}
        }
        final Map<UUID,Long> balances=new HashMap<>();
        final Map<String,Receipt> receipts=new HashMap<>();
        boolean available=true;
        boolean creditAllowed=true;
        int calls;
        public boolean available(UUID market){return available&&MARKET.equals(market);}
        public long creditedBase(UUID market,UUID player,String key) {
            if(!available(market))return -1;
            Receipt receipt=receipts.get(key);
            return receipt==null?0:receipt.owner.equals(player)?receipt.base:-1;
        }
        public boolean credit(UUID market,UUID player,String key,long amount) {
            calls++;
            if(!available(market)||!creditAllowed)return false;
            Receipt receipt=receipts.get(key);
            if(receipt!=null&&!receipt.owner.equals(player))return false;
            long previous=receipt==null?0:receipt.base;
            if(previous>amount)return false;
            receipts.put(key,new Receipt(player,amount));
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
        assertEquals(10000,bank.receipts.get(ShopWelcomeGift.receiptKey(STEVE)).base);
    }

    @Test
    void failedBankRetryCompletesSameDurableObligation() {
        Bank bank=new Bank(); bank.creditAllowed=false;
        ShopWelcomeGift first=gift();
        assertEquals(ShopWelcomeGift.Status.UNAVAILABLE,first.claim(STEVE,MARKET,bank));
        assertTrue(first.claimed(STEVE),"обязательство сохранено до неуспешного денежного вызова");
        bank.creditAllowed=true;
        ShopWelcomeGift restarted=gift();
        assertEquals(ShopWelcomeGift.Status.CLAIMED,restarted.claim(STEVE,MARKET,bank));
        assertEquals(10000,bank.balances.get(STEVE));
        assertEquals(1,bank.receipts.size());
    }

    /** Регресс production-отказа: чужая глобальная квитанция не блокирует подарки остальным игрокам. */
    @Test
    void everyPlayerHasOwnGiftDespiteGlobalReceiptOwnerLock() {
        Bank bank=new Bank(); ShopWelcomeGift gift=gift();
        assertEquals(ShopWelcomeGift.Status.CLAIMED,gift.claim(STEVE,MARKET,bank));
        assertEquals(ShopWelcomeGift.Status.CLAIMED,gift.claim(ALEX,MARKET,bank));
        assertEquals(10000,bank.balances.get(STEVE));
        assertEquals(10000,bank.balances.get(ALEX));
    }

    /** Миграция: получивший по старой глобальной квитанции не получает повторно; остальные получают штатно. */
    @Test
    void legacyGlobalReceiptOwnerIsNotPaidTwiceButOthersClaimNormally() {
        Bank bank=new Bank();
        bank.receipts.put(ShopWelcomeGift.RECEIPT_KEY,new Bank.Receipt(STEVE,10000));
        ShopWelcomeGift gift=gift();
        assertEquals(ShopWelcomeGift.Status.ALREADY,gift.claim(STEVE,MARKET,bank));
        assertEquals(0,bank.balances.getOrDefault(STEVE,0L));
        assertEquals(ShopWelcomeGift.Status.CLAIMED,gift.claim(ALEX,MARKET,bank));
        assertEquals(10000,bank.balances.get(ALEX));
    }

    /** Застрявший игрок (обязательство сохранено, денег не было) получает деньги следующим нажатием. */
    @Test
    void stuckObligationIsCompletedByNextPressAfterUpgrade() {
        Bank bank=new Bank(); bank.creditAllowed=false;
        ShopWelcomeGift old=gift();
        assertEquals(ShopWelcomeGift.Status.UNAVAILABLE,old.claim(ALEX,MARKET,bank));
        bank.creditAllowed=true;
        ShopWelcomeGift upgraded=gift();
        assertEquals(ShopWelcomeGift.Status.CLAIMED,upgraded.claim(ALEX,MARKET,bank));
        assertEquals(10000,bank.balances.get(ALEX));
    }

    @Test
    void unavailableBankWritesNothingAndRecovers() {
        Bank bank=new Bank(); bank.available=false;
        ShopWelcomeGift first=gift();
        assertEquals(ShopWelcomeGift.Status.UNAVAILABLE,first.claim(STEVE,MARKET,bank));
        assertFalse(first.claimed(STEVE),"без банка обязательство не сохраняется");
        bank.available=true;
        assertEquals(ShopWelcomeGift.Status.CLAIMED,first.claim(STEVE,MARKET,bank));
        assertEquals(10000,bank.balances.get(STEVE));
    }
}
