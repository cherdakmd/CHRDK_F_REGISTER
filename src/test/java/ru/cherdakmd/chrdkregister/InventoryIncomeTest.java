package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.InventoryIncome;
import java.io.IOException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class InventoryIncomeTest {
    final UUID owner=UUID.randomUUID();
    InventoryIncome.Intent intent() { return new InventoryIncome.Intent(UUID.randomUUID(),owner,"before","after"); }
    static final class Bag implements InventoryIncome.Inventory {
        UUID owner; String state="before",witness; boolean available=true,crash,noWitness; int writes;
        Bag(UUID owner) { this.owner=owner; }
        public UUID owner() { return owner; }
        public boolean available() { return available; }
        public String witness() { return witness; }
        public boolean matches(String value) { return state.equals(value); }
        public void checkpoint() throws IOException { }
        public void consume(String after,String token) throws IOException { state=after; if(!noWitness) witness=token; writes++; if(crash) throw new IOException("after native saveData"); }
    }
    @Test void consumesOnlyAfterIntentAndReturnsProofForSourcePublication() throws Exception {
        var i=intent(); var bag=new Bag(owner);
        assertEquals(InventoryIncome.Decision.CONSUMED,InventoryIncome.recover(i,bag)); assertEquals("after",bag.state); assertEquals(i.transaction().toString(),bag.witness); assertEquals(1,bag.writes);
    }
    @Test void repeatWithWitnessNeverConsumesInventoryAgain() throws Exception {
        var i=intent(); var bag=new Bag(owner); InventoryIncome.recover(i,bag); bag.state="legitimate later inventory";
        assertEquals(InventoryIncome.Decision.CONSUMED,InventoryIncome.recover(i,bag)); assertEquals(1,bag.writes); assertEquals("legitimate later inventory",bag.state);
    }
    @Test void restartAfterSaveDataExceptionUsesDurableWitness() throws Exception {
        var i=intent(); var bag=new Bag(owner); bag.crash=true;
        assertThrows(IOException.class,()->InventoryIncome.recover(i,bag)); bag.crash=false;
        assertEquals(InventoryIncome.Decision.CONSUMED,InventoryIncome.recover(i,bag)); assertEquals(1,bag.writes);
    }
    @Test void matchingAfterWithoutWitnessIsAmbiguousNotSuccess() throws Exception {
        var bag=new Bag(owner); bag.state="after";
        assertEquals(InventoryIncome.Decision.CONFLICT,InventoryIncome.recover(intent(),bag)); assertEquals(0,bag.writes);
    }
    @Test void unavailableOrAnotherOwnerDoesNotApply() throws Exception {
        var bag=new Bag(owner); bag.available=false;
        assertEquals(InventoryIncome.Decision.WAIT,InventoryIncome.recover(intent(),bag)); bag.available=true; bag.owner=UUID.randomUUID();
        assertEquals(InventoryIncome.Decision.WAIT,InventoryIncome.recover(intent(),bag)); assertEquals(InventoryIncome.Decision.WAIT,InventoryIncome.recover(intent(),null)); assertEquals(0,bag.writes);
    }
    @Test void failedWitnessVerificationCannotAuthorizeMoney() throws Exception {
        var bag=new Bag(owner); bag.noWitness=true;
        assertEquals(InventoryIncome.Decision.CONFLICT,InventoryIncome.recover(intent(),bag));
    }
    @Test void laterTransactionWitnessDoesNotMatchOldIntent() throws Exception {
        var bag=new Bag(owner); bag.state="changed"; bag.witness=UUID.randomUUID().toString();
        assertEquals(InventoryIncome.Decision.CONFLICT,InventoryIncome.recover(intent(),bag)); assertEquals(0,bag.writes);
    }
    @Test void invalidIntentsNeverReachNativeApply() {
        assertThrows(IllegalArgumentException.class,()->new InventoryIncome.Intent(null,owner,"before","after"));
        assertThrows(IllegalArgumentException.class,()->new InventoryIncome.Intent(UUID.randomUUID(),null,"before","after"));
        assertThrows(IllegalArgumentException.class,()->new InventoryIncome.Intent(UUID.randomUUID(),owner,"same","same"));
    }
}
