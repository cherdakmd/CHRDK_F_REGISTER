package ru.cherdakmd.chrdkregister;

import ru.cherdakmd.chrdkregister.api.InventoryTransfer;
import java.io.IOException;
import java.util.*;

/** Canonical bank.json intent: один незавершённый обмен на UUID, банк/лимит владельца зарезервированы. */
final class BankCash {
    enum Kind { DEPOSIT, WITHDRAW }
    record Intent(UUID transaction,UUID owner,long sequence,Kind kind,long amount,long beforeBalance,
                  int blockWorth,int selected,long request,String before,String after) {
        boolean valid() {
            return transaction!=null&&owner!=null&&sequence>0&&sequence<Long.MAX_VALUE&&kind!=null
                    &&amount>0&&amount<=36L*64*64&&beforeBalance>=0&&beforeBalance<=BankStore.MAX_BALANCE
                    &&blockWorth>=1&&blockWorth<=64&&selected>=-1&&selected<36
                    &&(kind==Kind.DEPOSIT?(request==-1||request==amount)&&amount<=BankStore.MAX_BALANCE-beforeBalance
                    :request==amount&&selected==-1&&amount<=2304&&amount<=beforeBalance)
                    &&before!=null&&after!=null&&!before.equals(after)&&before.length()<=4*1024*1024&&after.length()<=4*1024*1024;
        }
        long afterBalance() { return kind==Kind.DEPOSIT?beforeBalance+amount:beforeBalance-amount; }
    }
    record Receipt(long sequence,UUID transaction,Kind kind,long amount) {
        boolean valid() { return sequence>0&&sequence<Long.MAX_VALUE&&transaction!=null&&kind!=null&&amount>0&&amount<=(kind==Kind.WITHDRAW?2304:36L*64*64); }
        boolean matches(Intent i) { return sequence==i.sequence()&&transaction.equals(i.transaction())&&kind==i.kind()&&amount==i.amount(); }
    }
    static boolean matches(Receipt receipt,String witness) { return Objects.equals(receipt==null?null:receipt.transaction().toString(),witness); }
    /** Native plan/hash проверяется серверным adapter до исполнения. AFTER без nonce не является доказательством. */
    static InventoryTransfer.Decision recover(BankStore bank,UUID owner,InventoryTransfer.Inventory inventory) throws IOException {
        if(!bank.available()||inventory==null||!owner.equals(inventory.owner())||!inventory.available()) return InventoryTransfer.Decision.WAIT;
        var pending=bank.cashPending(owner); var receipt=bank.cashReceipt(owner);
        if(pending==null) return matches(receipt,inventory.witness())?InventoryTransfer.Decision.APPLIED:InventoryTransfer.Decision.CONFLICT;
        if(!pending.transaction().toString().equals(inventory.witness())&&!matches(receipt,inventory.witness())) return InventoryTransfer.Decision.CONFLICT;
        var proof=InventoryTransfer.recover(new InventoryTransfer.Intent(pending.transaction(),owner,pending.before(),pending.after()),inventory);
        if(proof!=InventoryTransfer.Decision.APPLIED) return proof;
        return bank.finishCash(pending)?InventoryTransfer.Decision.APPLIED:InventoryTransfer.Decision.WAIT;
    }
    private BankCash() { }
}
