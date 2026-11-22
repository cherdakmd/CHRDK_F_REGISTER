package ru.cherdakmd.chrdkregister;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class FixedMoneyMilestonesTest {
    static final String SOURCE="fixed-award-test";
    @TempDir Path dir; final UUID owner=UUID.randomUUID();
    FixedMoneyMilestones journal() throws IOException {return new FixedMoneyMilestones(dir.resolve("source"),SOURCE);}
    BankStore bank(){var bank=new BankStore(dir.resolve("core"),Logger.getAnonymousLogger());bank.load();return bank;}
    static final class Gateway implements FixedMoneyMilestones.Bank {
        final BankStore bank; boolean known=true;
        Gateway(BankStore bank){this.bank=bank;}
        public boolean available(){return bank.available();}
        public boolean registered(String s,UUID l){return bank.incomeSourceAvailable(s,l);}
        public boolean register(String s,UUID l){return bank.registerIncomeSource(s,l);}
        public boolean known(UUID o){return known;}
        public long credited(String s,UUID l,UUID o){return bank.creditedEntitlement(s,l,o.toString(),o);}
        public boolean credit(String s,UUID l,UUID o,long amount){return bank.creditEntitlementMinor(s,l,o.toString(),o,amount);}
    }
    FixedMoneyMilestones imported() throws Exception {
        var journal=journal();journal.importLegacy(Map.of(owner,Map.of("paid",FixedMoneyMilestones.State.LEGACY_DONE,"unknown",FixedMoneyMilestones.State.LEGACY_UNCERTAIN)));return journal;
    }
    @Test void legacyDoneAndUnknownAreNotNewMoney() throws Exception {
        var j=imported();var b=bank();var gateway=new Gateway(b);
        assertEquals(FixedMoneyMilestones.State.LEGACY_DONE,j.accept(gateway,owner,"paid",100).state());
        assertEquals(FixedMoneyMilestones.State.LEGACY_UNCERTAIN,j.accept(gateway,owner,"unknown",100).state());
        assertEquals(0,j.total(owner));assertTrue(j.pay(gateway,owner));assertEquals(0,b.balanceMinor(owner));
        j.importLegacy(Map.of(owner,Map.of("later",FixedMoneyMilestones.State.LEGACY_DONE)));
        assertNull(journal().award(owner,"later"));
    }
    @Test void frozenMinorAmountsSurviveRestartsAndSpending() throws Exception {
        var j=imported();var b=bank();var gateway=new Gateway(b);
        assertEquals(15001,j.accept(gateway,owner,"new",15001).minor());
        assertEquals(15001,j.accept(gateway,owner,"new",99999).minor());
        assertTrue(j.pay(gateway,owner));assertTrue(b.withdrawMinor(owner,100,false));
        j=journal();b=bank();gateway=new Gateway(b);
        assertTrue(j.pay(gateway,owner));assertEquals(14901,b.balanceMinor(owner));
        j.accept(gateway,owner,"second",55);assertTrue(j.pay(gateway,owner));assertEquals(14956,b.balanceMinor(owner));
    }
    @Test void rollbackCannotBeHiddenByNewAward() throws Exception {
        var j=imported();var b=bank();var g=new Gateway(b);j.accept(g,owner,"first",1000);j.pay(g,owner);
        var file=dir.resolve("source/fixed-money.json");var json=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        json.getAsJsonObject("awards").getAsJsonObject(owner.toString()).remove("first");Files.writeString(file,json.toString());
        var old=journal();assertThrows(IOException.class,()->old.accept(g,owner,"second",2000));
        assertThrows(IOException.class,()->old.pay(g,owner));assertNull(journal().award(owner,"second"));assertEquals(1000,b.balanceMinor(owner));
    }
    @Test void failedWriteBeforeAndAfterReplacementAreRecoverableWithoutSecondPrice() throws Exception {
        var j=imported();var b=bank();var g=new Gateway(b);assertTrue(j.bind(g));
        Files.createDirectory(dir.resolve("source/fixed-money.json.tmp"));
        var before=j;assertThrows(IOException.class,()->before.accept(g,owner,"before",123));assertFalse(before.healthy());assertNull(journal().award(owner,"before"));
        Files.delete(dir.resolve("source/fixed-money.json.tmp"));
        var after=new FixedMoneyMilestones(dir.resolve("source"),SOURCE,()->{throw new IOException("after replace");});
        assertThrows(IOException.class,()->after.accept(g,owner,"after",123));assertFalse(after.healthy());
        var recovered=journal();assertEquals(123,recovered.accept(g,owner,"after",999).minor());assertTrue(recovered.pay(g,owner));
        assertEquals(123,bank().balanceMinor(owner));
    }
    @Test void bankFailureAfterReplaceAndUnknownOwnerDoNotDuplicate() throws Exception {
        var j=imported();var b=bank();var g=new Gateway(b);assertTrue(j.bind(g));j.accept(g,owner,"new",500);
        g.known=false;assertFalse(j.pay(g,owner));assertEquals(0,b.balanceMinor(owner));
        var broken=new BankStore(dir.resolve("core"),Logger.getAnonymousLogger(),()->{throw new IOException("after bank replace");});broken.load();
        assertFalse(j.pay(new Gateway(broken),owner));assertFalse(broken.available());
        var recovered=bank();assertEquals(500,recovered.balanceMinor(owner));assertTrue(journal().pay(new Gateway(recovered),owner));assertEquals(500,recovered.balanceMinor(owner));
    }
    @Test void lostSourceOrRolledBackBankDoesNotCreateNewLineage() throws Exception {
        var j=imported();var b=bank();var g=new Gateway(b);assertTrue(j.bind(g));j.accept(g,owner,"new",100);
        Files.delete(dir.resolve("source/fixed-money.json"));var replacement=imported();assertFalse(replacement.bind(g));
        assertThrows(IOException.class,()->replacement.accept(g,owner,"new",100));
        var emptyBank=new BankStore(dir.resolve("empty-core"),Logger.getAnonymousLogger());emptyBank.load();
        // Original, registered journal must never bind a bank that lost its source.
        assertFalse(j.bind(new Gateway(emptyBank)));
    }
    @Test void fullBankLeavesObligationAndPartialCreditForRetry() throws Exception {
        var j=imported();var b=bank();var g=new Gateway(b);b.deposit(owner,"Alex",BankStore.MAX_BALANCE);
        j.accept(g,owner,"new",15000);assertFalse(j.pay(g,owner));assertEquals(15000,journal().total(owner));
        assertTrue(b.withdraw(owner,50));assertFalse(j.pay(g,owner));assertEquals(5000,b.creditedEntitlement(SOURCE,j.lineage(),owner.toString(),owner));
        assertTrue(b.withdraw(owner,100));assertTrue(journal().pay(g,owner));assertEquals(BankStore.MAX_BALANCE*100L,b.balanceMinor(owner));
    }
    @Test void badFieldsDuplicatesAndFractionsFailClosed() throws Exception {
        imported();var file=dir.resolve("source/fixed-money.json");String good=Files.readString(file);
        for(String bad:List.of(good.replace("\"schema\": 1","\"schema\": 1, \"schema\": 1"),good.replace("\"minor\": 0","\"minor\": 0.5"),good.replace("LEGACY_DONE","NOT_A_STATE"))) {
            assertNotEquals(good,bad);Files.writeString(file,bad);assertThrows(IOException.class,this::journal);assertEquals(bad,Files.readString(file));
        }
    }
    @Test void quoteScalesOnlyDiamondComponent() {
        var rate=new DiamondExchangePolicy.Quote(15000,0,0,0,0,0);
        assertEquals(41500,FixedMoneyMilestones.quote(10,2,100,rate));
        assertThrows(IllegalArgumentException.class,()->FixedMoneyMilestones.quote(0,-1,0,rate));
    }
}
