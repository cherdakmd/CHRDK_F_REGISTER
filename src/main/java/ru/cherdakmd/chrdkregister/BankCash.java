package ru.cherdakmd.chrdkregister;

import ru.cherdakmd.chrdkregister.api.InventoryTransfer;
import java.io.IOException;
import java.util.*;

/** Canonical bank.json intent: один незавершённый обмен на UUID, банк/лимит владельца зарезервированы. */
final class BankCash {
    enum Kind { DEPOSIT, WITHDRAW, SHOP, CLAIM_FUND }
    record Intent(UUID transaction,UUID owner,long sequence,Kind kind,long amount,long beforeBalance,
                  int blockWorth,int selected,long request,String before,String after,ru.cherdakmd.chrdkregister.api.ShopExchange shop,
                  long beforeBalanceMinor,long minorDelta) {
        Intent(UUID t,UUID o,long s,Kind k,long a,long b,int w,int slot,long r,String before,String after) { this(t,o,s,k,a,b,w,slot,r,before,after,null,b*100L,0); }
        Intent(UUID t,UUID o,long s,Kind k,long a,long b,int w,int slot,long r,String before,String after,ru.cherdakmd.chrdkregister.api.ShopExchange shop) { this(t,o,s,k,a,b,w,slot,r,before,after,shop,b*100L,0); }
        boolean valid() {
            if(minorDelta!=0) {
                if(shop!=null||kind==null||transaction==null||owner==null||sequence<=0||sequence>=Long.MAX_VALUE
                        ||amount<=0||beforeBalanceMinor<0||beforeBalanceMinor>BankStore.MAX_BALANCE*100L||minorDelta==Long.MIN_VALUE
                        ||before==null||after==null||before.equals(after)||before.length()>4*1024*1024||after.length()>4*1024*1024) return false;
                long afterMinor;
                try { afterMinor=Math.addExact(beforeBalanceMinor,minorDelta); }
                catch(ArithmeticException overflow) { return false; }
                if(afterMinor<0||afterMinor>BankStore.MAX_BALANCE*100L) return false;
                return switch(kind) {
                    case DEPOSIT -> minorDelta>0&&amount<=36L*64*64&&blockWorth>=1&&blockWorth<=64&&selected>=-1&&selected<36&&(request==-1||request==amount);
                    case WITHDRAW -> minorDelta<0&&amount==request&&amount<=2304&&blockWorth==1&&selected==-1;
                    case CLAIM_FUND -> minorDelta>0&&amount<=36L*64*9&&blockWorth==9&&selected==-1&&request>beforeBalanceMinor&&request<=BankStore.MAX_BALANCE*100L&&minorDelta>=request-beforeBalanceMinor;
                    case SHOP -> false;
                };
            }
            if(beforeBalanceMinor!=0&&beforeBalanceMinor!=beforeBalance*100L) return false;
            if(kind==Kind.SHOP) return shop!=null&&shop.valid()&&(shop.fractional()||shop.fits(beforeBalance))&&transaction.equals(shop.transaction())&&owner.equals(shop.owner())&&sequence>0&&sequence<Long.MAX_VALUE&&amount==shop.money()&&before.equals(shop.before())&&after.equals(shop.after());
            if(kind==Kind.CLAIM_FUND) return shop==null&&transaction!=null&&owner!=null&&sequence>0&&sequence<Long.MAX_VALUE&&beforeBalance>=0&&beforeBalance<=BankStore.MAX_BALANCE&&amount>0&&amount<=36L*64*9&&amount<=BankStore.MAX_BALANCE-beforeBalance&&request>beforeBalance&&request<=BankStore.MAX_BALANCE&&amount>=request-beforeBalance&&blockWorth==9&&selected==-1&&before!=null&&after!=null&&!before.equals(after)&&before.length()<=4*1024*1024&&after.length()<=4*1024*1024;
            return shop==null&&transaction!=null&&owner!=null&&sequence>0&&sequence<Long.MAX_VALUE&&kind!=null
                    &&amount>0&&amount<=36L*64*64&&beforeBalance>=0&&beforeBalance<=BankStore.MAX_BALANCE
                    &&blockWorth>=1&&blockWorth<=64&&selected>=-1&&selected<36
                    &&(kind==Kind.DEPOSIT?(request==-1||request==amount)&&amount<=BankStore.MAX_BALANCE-beforeBalance
                    :request==amount&&selected==-1&&amount<=2304&&amount<=beforeBalance)
                    &&before!=null&&after!=null&&!before.equals(after)&&before.length()<=4*1024*1024&&after.length()<=4*1024*1024;
        }
        long afterBalance() { return kind==Kind.SHOP?beforeBalance-shop.debit()+shop.credit():(kind==Kind.DEPOSIT||kind==Kind.CLAIM_FUND)?beforeBalance+amount:beforeBalance-amount; }
    }
    record Receipt(long sequence,UUID transaction,Kind kind,long amount) {
        boolean valid() { return sequence>0&&sequence<Long.MAX_VALUE&&transaction!=null&&kind!=null&&amount>=0&&(amount>0||kind==Kind.SHOP)&&amount<=(kind==Kind.SHOP?BankStore.MAX_BALANCE:kind==Kind.WITHDRAW?2304:kind==Kind.CLAIM_FUND?36L*64*9:36L*64*64); }
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
