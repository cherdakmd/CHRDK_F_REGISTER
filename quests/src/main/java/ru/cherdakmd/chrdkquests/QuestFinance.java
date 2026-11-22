package ru.cherdakmd.chrdkquests;

import java.io.IOException;
import java.util.UUID;
import ru.cherdakmd.chrdkregister.api.InventoryTransfer;

/** Journal checkpoint precedes native debit and bank income; retries are receipt-based. */
final class QuestFinance {
    interface Bank {
        boolean available(); boolean registered(UUID lineage); boolean register(UUID lineage);
        boolean known(UUID owner); long credited(UUID lineage,UUID owner); boolean credit(UUID lineage,UUID owner,long minor);
    }
    static boolean bind(QuestAwardJournal journal,Bank bank) throws IOException {
        if(!journal.healthy()||!journal.imported()||!bank.available())return false;
        if(bank.registered(journal.lineage())) {journal.markRegistered();return true;}
        if(journal.snapshot().values().stream().flatMap(rows->rows.values().stream()).anyMatch(a->a.minor()!=0||!a.ready()))return false;
        if(!bank.register(journal.lineage()))return false;
        journal.markRegistered();return true;
    }
    static InventoryTransfer.Decision recover(QuestAwardJournal journal,UUID owner,String quest,InventoryTransfer.Inventory inventory) throws IOException {
        var award=journal.award(owner,quest);
        if(award==null)throw new IllegalStateException("unknown award");
        if(award.ready())return InventoryTransfer.Decision.APPLIED;
        var result=InventoryTransfer.recover(award.inventory(),inventory);
        if(result==InventoryTransfer.Decision.APPLIED)journal.confirmInventory(owner,quest);
        return result;
    }
    static void verifyOwner(QuestAwardJournal journal,Bank bank,UUID owner) throws IOException {
        long credited=bank.credited(journal.lineage(),owner);
        if(credited<0||credited>journal.totals().getOrDefault(owner,0L))throw new IOException("quest journal older than bank receipt");
    }
    static void pay(QuestAwardJournal journal,Bank bank) throws IOException {
        if(!bind(journal,bank))return;
        for(var row:journal.totals().entrySet()) payOne(journal,bank,row.getKey(),row.getValue());
    }
    static void payOne(QuestAwardJournal journal,Bank bank,UUID owner,long total) throws IOException {
        if(total==0||!bank.known(owner))return;
        long credited=bank.credited(journal.lineage(),owner);
        if(credited<0||credited>total)throw new IOException("quest journal/bank receipt conflict");
        bank.credit(journal.lineage(),owner,total);
    }
    private QuestFinance(){}
}
