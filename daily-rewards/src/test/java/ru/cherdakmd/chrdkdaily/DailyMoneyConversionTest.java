package ru.cherdakmd.chrdkdaily;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.BankStore;
import ru.cherdakmd.chrdkregister.api.RewardMarketQuote;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class DailyMoneyConversionTest {
    @TempDir Path dir;
    final UUID owner=UUID.randomUUID(); final Logger log=Logger.getAnonymousLogger(); final long now=21500L*86400000L;
    DailyStore load(){var s=new DailyStore(dir,log);s.load();return s;}
    DailyConfig config() throws Exception {var y=new YamlConfiguration();y.loadFromString("rewards:\n - day: 1\n   items: ['DIAMOND 2', 'BREAD 8', 'BAKED_POTATO 2']\n   vip-boost-hours: 2\n");return DailyConfig.parse(y,log,false);}
    RewardMarketQuote shop(long price){return (material,count)->material.equals("BREAD")
            ? new RewardMarketQuote.Quote(RewardMarketQuote.Status.AVAILABLE,price)
            : new RewardMarketQuote.Quote(RewardMarketQuote.Status.NOT_BOUGHT,0);}
    final class Bank implements DailyDelivery.MoneyBank {
        BankStore root=new BankStore(dir.resolve("core"),log);
        Bank(){root.load();root.open(owner,"Steve");root.flush();}
        public boolean available(){return root.available();}
        public boolean registered(UUID l){return root.incomeSourceAvailable(DailyStore.MONEY_SOURCE,l);}
        public boolean register(UUID l){return root.registerIncomeSource(DailyStore.MONEY_SOURCE,l);}
        public boolean known(UUID id){return owner.equals(id);}
        public long credited(UUID l,UUID id){return root.creditedEntitlement(DailyStore.MONEY_SOURCE,l,id.toString(),id);}
        public boolean credit(UUID l,UUID id,long n){return root.creditEntitlementMinor(DailyStore.MONEY_SOURCE,l,id.toString(),id,n);}
    }
    DailyStore ready(Bank b) throws Exception {
        var s=load();assertTrue(b.root.registerIncomeSource(DailyStore.SOURCE,s.lineage()));s.markRegistered();
        assertTrue(DailyDelivery.bindMoney(s,b));return s;
    }
    @Test void quoteUsesShopAndExplicitFallbackAndFailsClosed() throws Exception {
        var day=config().rewardForStreakDay(1);
        assertEquals(30_290,DailyMoneyQuote.quote(day,true,shop(300),v->v/2));
        assertThrows(IllegalStateException.class,()->DailyMoneyQuote.quote(day,true,null,v->v));
        assertThrows(IllegalStateException.class,()->DailyMoneyQuote.quote(day,true,(m,n)->new RewardMarketQuote.Quote(RewardMarketQuote.Status.UNAVAILABLE,0),v->v));
        assertThrows(IllegalStateException.class,()->DailyMoneyQuote.quote(day,true,(m,n)->new RewardMarketQuote.Quote(RewardMarketQuote.Status.NOT_BOUGHT,0),v->v));
    }
    @Test void quoteAndProgressAreFrozenBeforeCreditAndNeverRequoted() throws Exception {
        var bank=new Bank(); var s=ready(bank);
        var accepted=s.acceptMoney(owner,now,config(),true,r->DailyMoneyQuote.quote(r,true,shop(300),v->v));
        assertEquals(30_580,accepted.minor());assertNull(s.pending(owner));
        s=load(); assertEquals(30_580,s.moneyMinor().get(owner));
        DailyDelivery.moneyMinor(s,bank);assertEquals(30_580,bank.root.balanceMinor(owner));
        assertTrue(bank.root.withdrawMinor(owner,100,false));DailyDelivery.moneyMinor(load(),bank);assertEquals(30_480,bank.root.balanceMinor(owner));
        assertNull(s.acceptMoney(owner,now,config(),true,r->999999));
        assertEquals(30_580,load().moneyMinor().get(owner));
    }
    @Test void failedQuoteDoesNotAdvanceOrWriteAnObligation() throws Exception {
        var bank=new Bank();var s=ready(bank);
        assertThrows(IllegalStateException.class,()->s.acceptMoney(owner,now,config(),false,r->{throw new IllegalStateException();}));
        assertTrue(load().canClaim(owner,now));assertTrue(load().moneyMinor().isEmpty());
    }
    @Test void fullBankRetriesAfterRestartWithoutDuplicating() throws Exception {
        var bank=new Bank();var s=ready(bank);bank.root.setBalance(owner,"Steve",BankStore.MAX_BALANCE);
        s.acceptMoney(owner,now,config(),false,r->15000);DailyDelivery.moneyMinor(s,bank);
        assertEquals(BankStore.MAX_BALANCE*100L,bank.root.balanceMinor(owner));
        assertTrue(bank.root.withdraw(owner,150));DailyDelivery.moneyMinor(load(),bank);
        assertEquals(BankStore.MAX_BALANCE*100L,bank.root.balanceMinor(owner));
        assertTrue(bank.root.withdraw(owner,150));DailyDelivery.moneyMinor(load(),bank);
        assertEquals((BankStore.MAX_BALANCE-150)*100L,bank.root.balanceMinor(owner));
    }
    @Test void interruptedPrepareAfterRenameRecoversFrozenTotal() throws Exception {
        var bank=new Bank();ready(bank);
        var broken=new DailyStore(dir,log,()->{throw new IOException("uncertain replace");});broken.load();
        assertThrows(IOException.class,()->broken.acceptMoney(owner,now,config(),false,r->1234));
        var recovered=load();assertFalse(recovered.canClaim(owner,now));assertEquals(1234,recovered.moneyMinor().get(owner));
        DailyDelivery.moneyMinor(recovered,bank);DailyDelivery.moneyMinor(load(),bank);assertEquals(1234,bank.root.balanceMinor(owner));
    }
    @Test void legacyPendingRetainsItsOriginalEffectsAndUnknownOutcome() throws Exception {
        var bank=new Bank();var s=ready(bank);
        var old=s.accept(owner,now,config(),true);assertNotNull(old);assertEquals(2,old.hours());
        s.begin(owner); // Old item effect was started, but its result is unknown.
        s=load(); assertTrue(s.pending(owner).executing());
        var calls=new int[1];
        assertFalse(DailyDelivery.nativeEffects(s,owner,new DailyDelivery.Native(){
            public void item(DailyConfig.ItemReward i){calls[0]++;} public void boost(int h){calls[0]++;}
        }));
        assertEquals(0,calls[0]);assertFalse(s.canClaim(owner,now+86400000));assertTrue(s.moneyMinor().isEmpty());
    }
    @Test void missingRegisteredBankSourceCannotBeRecreatedAfterAcceptance() throws Exception {
        var bank=new Bank();var s=ready(bank);s.acceptMoney(owner,now,config(),false,r->5);
        bank.root=new BankStore(dir.resolve("empty-bank"),log);bank.root.load();
        assertFalse(DailyDelivery.bindMoney(s,bank));assertFalse(bank.registered(s.lineage()));
    }
}
