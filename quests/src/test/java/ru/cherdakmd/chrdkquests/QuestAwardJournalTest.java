package ru.cherdakmd.chrdkquests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.BankStore;
import ru.cherdakmd.chrdkregister.api.InventoryTransfer;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class QuestAwardJournalTest {
    @TempDir Path dir; final UUID owner=UUID.randomUUID();
    QuestAwardJournal load() throws Exception {var j=new QuestAwardJournal(dir);if(!j.imported())j.importLegacy(java.util.Map.of());return j;}
    final class Bank implements QuestFinance.Bank {
        BankStore store=new BankStore(dir.resolve("bank"),Logger.getAnonymousLogger());
        Bank(){store.load();store.open(owner,"Steve");store.flush();}
        public boolean available(){return store.available();}
        public boolean registered(UUID l){return store.incomeSourceAvailable(QuestAwardJournal.SOURCE,l);}
        public boolean register(UUID l){return store.registerIncomeSource(QuestAwardJournal.SOURCE,l);}
        public boolean known(UUID id){return owner.equals(id);}
        public long credited(UUID l,UUID id){return store.creditedEntitlement(QuestAwardJournal.SOURCE,l,id.toString(),id);}
        public boolean credit(UUID l,UUID id,long amount){return store.creditEntitlementMinor(QuestAwardJournal.SOURCE,l,id.toString(),id,amount);}
    }
    static final class Bag implements InventoryTransfer.Inventory {
        UUID owner; String state="before",token; int applies; boolean fail;
        Bag(UUID owner){this.owner=owner;}
        public UUID owner(){return owner;} public boolean available(){return true;} public String witness(){return token;}
        public boolean matches(String value){return state.equals(value);}
        public void apply(String after,String token)throws IOException {state=after;this.token=token;applies++;if(fail)throw new IOException("save unknown");}
        public void checkpoint(){}
    }
    @Test void importPreservesOldCompletedMarksWithoutRetroactivePayment() throws Exception {
        var b=new Bank();var j=new QuestAwardJournal(dir);
        j.importLegacy(java.util.Map.of(owner,java.util.Set.of("old_quest")));
        assertTrue(QuestFinance.bind(j,b));QuestFinance.pay(j,b);
        assertEquals(0,b.store.balanceMinor(owner));assertTrue(load().award(owner,"old_quest").ready());
        assertThrows(IllegalStateException.class,()->load().prepare(owner,"old_quest",1500,null));
    }
    @Test void frozenReadyAwardPaysOnceAfterRestartAndSpending() throws Exception {
        var b=new Bank();var j=load();assertTrue(QuestFinance.bind(j,b));j.prepare(owner,"one",1200,null);
        QuestFinance.pay(j,b);assertEquals(1200,b.store.balanceMinor(owner));assertTrue(b.store.withdrawMinor(owner,100,false));
        QuestFinance.pay(load(),b);assertEquals(1100,b.store.balanceMinor(owner));
        assertThrows(IllegalStateException.class,()->load().prepare(owner,"one",9000,null));
    }
    @Test void gatherRequiresProofBeforePublishingMoneyAndReplaysAfterRestart() throws Exception {
        var b=new Bank();var j=load();QuestFinance.bind(j,b);var i=new InventoryTransfer.Intent(UUID.randomUUID(),owner,"before","after");
        j.prepare(owner,"gather",1500,i);QuestFinance.pay(j,b);assertEquals(0,b.store.balanceMinor(owner));
        var bag=new Bag(owner);bag.fail=true;assertThrows(IOException.class,()->QuestFinance.recover(j,owner,"gather",bag));
        assertFalse(load().award(owner,"gather").ready());bag.fail=false;
        assertEquals(InventoryTransfer.Decision.APPLIED,QuestFinance.recover(load(),owner,"gather",bag));
        assertEquals(1,bag.applies);QuestFinance.pay(load(),b);assertEquals(1500,b.store.balanceMinor(owner));
    }
    @Test void matchingAfterWithoutWitnessIsNotProof() throws Exception {
        var b=new Bank();var j=load();QuestFinance.bind(j,b);j.prepare(owner,"gather",1500,new InventoryTransfer.Intent(UUID.randomUUID(),owner,"before","after"));
        var bag=new Bag(owner);bag.state="after";
        assertEquals(InventoryTransfer.Decision.CONFLICT,QuestFinance.recover(j,owner,"gather",bag));
        assertTrue(load().pending(owner));QuestFinance.pay(load(),b);assertEquals(0,b.store.balanceMinor(owner));
    }
    @Test void fullAccountRetainsObligationAndPaysWhenSpaceOpens() throws Exception {
        var b=new Bank();var j=load();QuestFinance.bind(j,b);b.store.setBalance(owner,"Steve",BankStore.MAX_BALANCE);
        j.prepare(owner,"one",15000,null);QuestFinance.pay(j,b);
        assertEquals(BankStore.MAX_BALANCE*100L,b.store.balanceMinor(owner));
        assertTrue(b.store.withdraw(owner,150));QuestFinance.pay(load(),b);
        assertEquals(BankStore.MAX_BALANCE*100L,b.store.balanceMinor(owner));
        assertTrue(b.store.withdraw(owner,150));QuestFinance.pay(load(),b);
        assertEquals((BankStore.MAX_BALANCE-150)*100L,b.store.balanceMinor(owner));
    }
    @Test void beforeRenameFailureNeverDebitsInventoryOrCreatesObligation() throws Exception {
        var b=new Bank();var j=load();QuestFinance.bind(j,b);
        Files.createDirectory(dir.resolve("quest-money.json.tmp"));
        assertThrows(IOException.class,()->j.prepare(owner,"gather",1500,new InventoryTransfer.Intent(UUID.randomUUID(),owner,"before","after")));
        assertFalse(j.healthy()); Files.delete(dir.resolve("quest-money.json.tmp"));
        assertNull(load().award(owner,"gather"));QuestFinance.pay(load(),b);assertEquals(0,b.store.balanceMinor(owner));
    }
    @Test void replacementAmbiguityKeepsAcceptedAmountAndQuarantinesWriter() throws Exception {
        var b=new Bank();var j=load();QuestFinance.bind(j,b);
        var fault=new QuestAwardJournal(dir,()->{throw new IOException("replace uncertain");});
        assertThrows(IOException.class,()->fault.prepare(owner,"one",1500,null));assertFalse(fault.healthy());
        assertEquals(1500,load().award(owner,"one").minor());QuestFinance.pay(load(),b);assertEquals(1500,b.store.balanceMinor(owner));
    }
    @Test void missingBankLineageCannotBeRecreatedWithAwards() throws Exception {
        var b=new Bank();var j=load();QuestFinance.bind(j,b);j.prepare(owner,"one",1500,null);
        b.store=new BankStore(dir.resolve("empty"),Logger.getAnonymousLogger());b.store.load();
        assertFalse(QuestFinance.bind(j,b));assertFalse(b.registered(j.lineage()));
    }
    @Test void corruptedOrDuplicateFieldsQuarantineWithoutOverwriting() throws Exception {
        String bad="{\"schema\":1,\"schema\":1}";Files.writeString(dir.resolve("quest-money.json"),bad);
        assertThrows(IOException.class,()->load());assertEquals(bad,Files.readString(dir.resolve("quest-money.json")));
    }
}
