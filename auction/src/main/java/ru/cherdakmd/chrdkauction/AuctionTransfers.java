package ru.cherdakmd.chrdkauction;

import ru.cherdakmd.chrdkregister.api.*;
import java.io.IOException;

/** Production recovery executor: банковская квитанция не является подтверждением удаления cash из playerdata. */
final class AuctionTransfers {
    static AuctionRecovery.Decision bank(AuctionStore store,AuctionPayment.Status status,InventoryTransfer.Inventory inventory) throws IOException {
        var p=store.pending(); if(!store.healthy()||p==null||p.payment()==null) return AuctionRecovery.Decision.CONFLICT;
        var c=p.payment();
        if(c.cash()>0&&(inventory==null||!c.buyer().equals(inventory.owner())||!inventory.available())) return AuctionRecovery.Decision.WAIT;
        boolean before=c.cash()>0&&inventory.matches(p.before()),after=c.cash()>0&&inventory.matches(p.after());
        boolean witness=c.cash()>0&&c.transaction().toString().equals(inventory.witness());
        var decision=AuctionRecovery.decide(status,c.cash(),before,after,witness);
        switch(decision) {
            case APPLY_AFTER, COMPLETE -> {
                if(c.cash()>0) {
                    var result=InventoryTransfer.recover(new InventoryTransfer.Intent(c.transaction(),c.buyer(),p.before(),p.after()),inventory);
                    if(result==InventoryTransfer.Decision.WAIT) return AuctionRecovery.Decision.WAIT;
                    if(result!=InventoryTransfer.Decision.APPLIED) return AuctionRecovery.Decision.CONFLICT;
                }
                store.completeBank(c);
            }
            case ROLLBACK -> store.rollbackBank(c);
            case WAIT, CONFLICT -> { }
        }
        return decision;
    }
    static InventoryTransfer.Decision item(AuctionStore store,InventoryTransfer.Inventory inventory) throws IOException {
        var p=store.pending();
        if(!store.healthy()||p==null||p.payment()!=null||p.transferId()==null) return InventoryTransfer.Decision.CONFLICT;
        var result=InventoryTransfer.recover(new InventoryTransfer.Intent(p.transferId(),p.player(),p.before(),p.after()),inventory);
        if(result==InventoryTransfer.Decision.APPLIED) store.complete();
        return result;
    }
    private AuctionTransfers() { }
}
