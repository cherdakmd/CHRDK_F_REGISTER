package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.InventoryTransfer;
import java.io.IOException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class InventoryTransferTest {
    final UUID owner=UUID.randomUUID();
    InventoryTransfer.Intent intent() { return new InventoryTransfer.Intent(UUID.randomUUID(),owner,"before","after"); }
    static class Bag implements InventoryTransfer.Inventory {
        UUID owner; String state="before",token,durableState="before",durableToken;
        boolean online=true,failSave,loseToken,both; int applies,checkpoints;
        Bag(UUID id) { owner=id; }
        public UUID owner() { return owner; }
        public boolean available() { return online; }
        public String witness() { return token; }
        public boolean matches(String value) { return both||state.equals(value); }
        public void apply(String after,String witness) throws IOException { applies++; state=after; token=loseToken?null:witness; checkpoint(); }
        public void checkpoint() throws IOException { checkpoints++; if(failSave) throw new IOException("playerdata save refused"); durableState=state; durableToken=token; }
    }
    @Test void nativeTransitionPersistsStorageAndTokenTogether() throws Exception {
        var i=intent(); var b=new Bag(owner); assertEquals(InventoryTransfer.Decision.APPLIED,InventoryTransfer.recover(i,b)); assertEquals("after",b.durableState); assertEquals(i.transaction().toString(),b.durableToken); assertEquals(1,b.applies);
    }
    @Test void identicalAfterWithoutNonceIsNotProof() throws Exception {
        var b=new Bag(owner); b.state="after"; assertEquals(InventoryTransfer.Decision.CONFLICT,InventoryTransfer.recover(intent(),b)); assertEquals(0,b.applies); assertEquals(0,b.checkpoints);
    }
    @Test void nonceAllowsLegitimateInventoryChangesWithoutOldSnapshotReplay() throws Exception {
        var i=intent(); var b=new Bag(owner); InventoryTransfer.recover(i,b); b.state="new legitimate inventory";
        assertEquals(InventoryTransfer.Decision.APPLIED,InventoryTransfer.recover(i,b)); assertEquals(1,b.applies); assertEquals("new legitimate inventory",b.durableState); assertEquals(2,b.checkpoints);
    }
    @Test void inMemoryNonceAfterSaveFailureIsNotYetDurableProof() throws Exception {
        var i=intent(); var b=new Bag(owner); b.failSave=true; assertThrows(IOException.class,()->InventoryTransfer.recover(i,b)); assertNull(b.durableToken);
        assertThrows(IOException.class,()->InventoryTransfer.recover(i,b)); assertNull(b.durableToken); assertEquals(1,b.applies);
        b.failSave=false; assertEquals(InventoryTransfer.Decision.APPLIED,InventoryTransfer.recover(i,b)); assertEquals(i.transaction().toString(),b.durableToken); assertEquals(1,b.applies);
    }
    @Test void wrongOwnerOfflineAndMissingPortNeverApply() throws Exception {
        var b=new Bag(UUID.randomUUID()); var i=intent(); assertEquals(InventoryTransfer.Decision.WAIT,InventoryTransfer.recover(i,b)); b.owner=owner; b.online=false;
        assertEquals(InventoryTransfer.Decision.WAIT,InventoryTransfer.recover(i,b)); assertEquals(InventoryTransfer.Decision.WAIT,InventoryTransfer.recover(i,null)); assertEquals(0,b.applies);
    }
    @Test void changedOrBothMatchingInventoryWithoutNonceFailsClosed() throws Exception {
        var b=new Bag(owner); b.state="unrelated"; assertEquals(InventoryTransfer.Decision.CONFLICT,InventoryTransfer.recover(intent(),b)); b.both=true;
        assertEquals(InventoryTransfer.Decision.CONFLICT,InventoryTransfer.recover(intent(),b)); assertEquals(0,b.applies);
    }
    @Test void aDifferentTransactionNeverClosesOldIntentByMatchingAfter() throws Exception {
        var b=new Bag(owner); b.token=UUID.randomUUID().toString(); b.state="after"; assertEquals(InventoryTransfer.Decision.CONFLICT,InventoryTransfer.recover(intent(),b)); assertEquals(0,b.applies);
    }
    @Test void lostNonceAfterApplyDoesNotAuthorizePublication() throws Exception {
        var b=new Bag(owner); b.loseToken=true; assertEquals(InventoryTransfer.Decision.CONFLICT,InventoryTransfer.recover(intent(),b)); assertEquals(1,b.applies);
    }
    @Test void unsupportedOldPortCannotSilentlyTrustAnInMemoryNonce() {
        var i=intent(); InventoryTransfer.Inventory port=new InventoryTransfer.Inventory() {
            public UUID owner() { return owner; } public boolean available() { return true; } public String witness() { return i.transaction().toString(); }
            public boolean matches(String value) { return true; } public void apply(String after,String token) { fail("must not replay"); }
        };
        assertThrows(IOException.class,()->InventoryTransfer.recover(i,port));
    }
    @Test void invalidIntentsFailBeforeNativeAccess() {
        assertThrows(IllegalArgumentException.class,()->new InventoryTransfer.Intent(null,owner,"b","a"));
        assertThrows(IllegalArgumentException.class,()->new InventoryTransfer.Intent(UUID.randomUUID(),owner,"same","same"));
    }
}
