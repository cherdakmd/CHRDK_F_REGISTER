package ru.cherdakmd.chrdkregister.api;

import java.io.IOException;

/** Квитанция банка + точный source + native nonce. Снимок AFTER сам по себе не является оплатой. */
public final class ChargeRecovery {
    public enum Decision { APPLY_AFTER, COMPLETE, ROLLBACK, WAIT, CONFLICT }
    /** Старые вызовы не получают выдуманное подтверждение playerdata. */
    public static Decision decide(BankCharge.Status status,long cash,boolean stateBefore,boolean stateAfter,
                                  boolean inventoryBefore,boolean inventoryAfter) {
        return decide(status,cash,stateBefore,stateAfter,inventoryBefore,inventoryAfter,false);
    }
    public static Decision decide(BankCharge.Status status,long cash,boolean stateBefore,boolean stateAfter,
                                  boolean inventoryBefore,boolean inventoryAfter,boolean witness) {
        if(status==null||status==BankCharge.Status.UNAVAILABLE) return Decision.WAIT;
        if(status==BankCharge.Status.CONFLICT||stateBefore==stateAfter||cash<0) return Decision.CONFLICT;
        if(status==BankCharge.Status.MISSING)
            return stateBefore&&!stateAfter&&!witness&&(cash==0||inventoryBefore&&!inventoryAfter)?Decision.ROLLBACK:Decision.CONFLICT;
        if(cash==0) return Decision.COMPLETE;
        if(witness) return Decision.COMPLETE;
        return stateBefore&&inventoryBefore&&!inventoryAfter?Decision.APPLY_AFTER:Decision.CONFLICT;
    }
    /** Production executor. Native plan/hash проверяется ChargeInventory.valid до входа.
     * Источник/журнал не закрываются, пока playerdata checkpoint не вернулся без исключения. */
    public static Decision recover(ChargeJournal journal,BankCharge.Status status,InventoryTransfer.Inventory inventory,
                                   ChargeInventory.Source source) throws IOException {
        var pending=journal.pending(); if(pending==null) return Decision.COMPLETE;
        if(!journal.healthy()) return Decision.CONFLICT;
        var c=pending.payment(); String state=source.snapshot();
        boolean before=state.equals(pending.beforeState()),after=state.equals(pending.afterState());
        if(c.cash()>0&&(inventory==null||!c.payer().equals(inventory.owner())||!inventory.available())) return Decision.WAIT;
        boolean witness=c.cash()>0&&c.transaction().toString().equals(inventory.witness());
        var decision=decide(status,c.cash(),before,after,c.cash()>0&&inventory.matches(pending.beforeInventory()),
                c.cash()>0&&inventory.matches(pending.afterInventory()),witness);
        if(decision==Decision.APPLY_AFTER||decision==Decision.COMPLETE) {
            if(c.cash()>0) {
                var proof=InventoryTransfer.recover(new InventoryTransfer.Intent(c.transaction(),c.payer(),pending.beforeInventory(),pending.afterInventory()),inventory);
                if(proof==InventoryTransfer.Decision.WAIT) return Decision.WAIT;
                if(proof!=InventoryTransfer.Decision.APPLIED) return Decision.CONFLICT;
            }
            source.publish(pending.beforeState(),pending.afterState()); journal.complete(c);
        } else if(decision==Decision.ROLLBACK) journal.rollback(c);
        return decision;
    }
    private ChargeRecovery() { }
}
