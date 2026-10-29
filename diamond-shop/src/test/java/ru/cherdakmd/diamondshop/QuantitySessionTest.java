package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class QuantitySessionTest {
    private final UUID owner=UUID.randomUUID(),other=UUID.randomUUID();

    @Test void editingDoesNotSubmitOrExecute() {
        var session=new QuantitySession(owner);
        assertTrue(session.editable(owner));assertFalse(session.pending(owner));
    }
    @Test void onlyOwnerCanSubmitAndObserveAPendingRequest() {
        var session=new QuantitySession(owner);
        assertFalse(session.editable(other));assertFalse(session.submit(other));
        assertTrue(session.submit(owner));assertFalse(session.pending(other));assertTrue(session.pending(owner));
    }
    @Test void repeatedConfirmClicksCanOnlySubmitOnce() {
        var session=new QuantitySession(owner);
        assertTrue(session.submit(owner));
        for(int i=0;i<100;i++){assertFalse(session.submit(owner));assertFalse(session.editable(owner));}
        assertTrue(session.pending(owner));
    }
    @Test void closeBeforeConfirmPermanentlyCancelsTheScreen() {
        var session=new QuantitySession(owner);session.close();session.close();
        assertFalse(session.editable(owner));assertFalse(session.submit(owner));assertFalse(session.pending(owner));
    }
    @Test void closeAfterConfirmCancelsQueuedExecutionEvenIfSameInventoryIsReopened() {
        var session=new QuantitySession(owner);assertTrue(session.submit(owner));session.close();
        assertFalse(session.pending(owner));assertFalse(session.submit(owner));assertFalse(session.editable(owner));
    }
    @Test void productionCallbackRequiresBothLiveSessionAndExactView() throws Exception {
        String s=Files.readString(Path.of("src/main/java/ru/cherdakmd/diamondshop/ShopQuantityGui.java"));
        assertTrue(s.contains("close(InventoryCloseEvent e)"));assertTrue(s.contains("h.session.close()"));
        assertTrue(s.contains("h.session.pending(p.getUniqueId())&&p.getOpenInventory().getTopInventory()==h.inventory"));
        assertTrue(s.indexOf("if(!active(player,h))return;")<s.indexOf("h.service.quoted(player"));
    }
}
