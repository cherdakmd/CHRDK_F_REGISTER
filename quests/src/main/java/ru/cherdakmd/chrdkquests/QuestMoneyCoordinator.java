package ru.cherdakmd.chrdkquests;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.api.*;
import java.io.IOException;
import java.util.*;

/** Bukkit adapter around the durable journal; all native transitions run on the main thread. */
final class QuestMoneyCoordinator {
    private static final NamespacedKey WITNESS=new NamespacedKey("chrdk_quests","reward_inventory");
    private final QuestsPlugin plugin; private final QuestAwardJournal journal; private final QuestStore store;
    private int cursor; private boolean conflict;
    private UUID inventoryConflictOwner; private String inventoryConflictQuest;
    private final QuestFinance.Bank bank=new QuestFinance.Bank(){
        public boolean available(){return plugin.coreApi()!=null&&plugin.coreApi().personalBankAvailable();}
        public boolean registered(UUID l){return plugin.coreApi().incomeSourceAvailable(QuestAwardJournal.SOURCE,l);}
        public boolean register(UUID l){return plugin.coreApi().registerIncomeSource(QuestAwardJournal.SOURCE,l);}
        public boolean known(UUID id){return plugin.coreApi().canReceiveBankIncome(id);}
        public long credited(UUID l,UUID id){return plugin.coreApi().creditedEntitlement(QuestAwardJournal.SOURCE,l,id.toString(),id);}
        public boolean credit(UUID l,UUID id,long amount){return plugin.coreApi().creditEntitlementMinor(QuestAwardJournal.SOURCE,l,id.toString(),id,amount);}
    };
    QuestMoneyCoordinator(QuestsPlugin plugin,QuestStore store) throws IOException {
        this.plugin=plugin;this.store=store;this.journal=new QuestAwardJournal(plugin.getDataFolder().toPath());
        journal.importLegacy(store.completedSnapshot());
        // Journal is authoritative if legacy progress was not flushed before shutdown.
        journal.snapshot().forEach((owner,rows)->{
            if (!store.canRetain(owner)) throw new IllegalStateException("quests.json capacity; journal owner cannot be restored");
            rows.forEach((id,a)->{if(a.ready())store.markDone(owner,id);});
        });
    }
    private boolean eligible(Player p){return Bukkit.isPrimaryThread()&&p!=null&&p.isOnline()&&!p.isDead()&&!p.hasMetadata("NPC")
            &&plugin.coreApi()!=null&&plugin.coreApi().isAuthenticated(p);}
    boolean ready(Player p){return !conflict&&store.healthy()&&journal.healthy()&&eligible(p)&&store.canRetain(p.getUniqueId());}
    boolean accepted(UUID owner,String id){return journal.award(owner,id)!=null;}
    void restoreCompleted(UUID owner){journal.awards(owner).forEach((id,a)->{if(a.ready())store.markDone(owner,id);});}
    boolean markAdminDone(UUID owner,String id) {
        try {
            if(conflict||!store.healthy()||!journal.healthy()||!store.canRetain(owner)||journal.pending(owner)||!QuestFinance.bind(journal,bank))return false;
            var award=journal.award(owner,id);
            if(award==null)journal.prepare(owner,id,0,null);
            else if(!award.ready())return false;
            store.markDone(owner,id); return true;
        } catch(Exception error){plugin.getLogger().log(java.util.logging.Level.WARNING,"[Quests] Не удалось зафиксировать отметку администратора",error);return false;}
    }
    void reconcile(Player player) throws IOException {
        if(!ready(player))return;
        for(var row:journal.awards(player.getUniqueId()).entrySet()) {
            if(!row.getValue().ready()) {
                var result=QuestFinance.recover(journal,player.getUniqueId(),row.getKey(),
                        ChargeInventory.inventory(player,WITNESS,()->eligible(player)));
                if(result==InventoryTransfer.Decision.CONFLICT) {
                    if(!conflict)plugin.getLogger().severe("[Quests] Неизвестный исход сдачи инвентаря для "+player.getName()+"/"+row.getKey()+"; журнал и playerdata нужно сверить.");
                    conflict=true;inventoryConflictOwner=player.getUniqueId();inventoryConflictQuest=row.getKey();return;
                }
            }
            var current=journal.award(player.getUniqueId(),row.getKey());
            if(current!=null&&current.ready())store.markDone(player.getUniqueId(),row.getKey());
        }
    }
    long quote(QuestReward reward) {
        var shop=Bukkit.getServicesManager().load(RewardMarketQuote.class);
        return QuestMoneyQuote.quote(reward,shop,plugin.coreApi().diamondRate()::scaleMinor);
    }
    String preview(QuestReward reward) {
        try {
            if(plugin.coreApi()==null||!plugin.coreApi().personalBankAvailable())return "недоступна";
            return "≈"+DiamondMoney.format(quote(reward))+" 💠 (на момент получения)";
        } catch(RuntimeException error) {return "недоступна";}
    }
    /** Returns the frozen amount. A failure never reverses a saved intent. */
    long accept(Player player,Quest quest) throws IOException {
        if(!ready(player)||!QuestFinance.bind(journal,bank))throw new IllegalStateException("банк/журнал недоступен");
        reconcile(player);
        if(conflict||journal.pending(player.getUniqueId())||accepted(player.getUniqueId(),quest.id()))throw new IllegalStateException("сдача уже сохранена или ожидает сверки");
        long previous=journal.totals().getOrDefault(player.getUniqueId(),0L);
        long receipt=bank.credited(journal.lineage(),player.getUniqueId());
        if(receipt<0||receipt>previous) {conflict=true;throw new IllegalStateException("журнал старше банковской квитанции");}
        long amount=quote(quest.reward());
        InventoryTransfer.Intent intent=null;
        if(quest.type()==QuestType.GATHER) {
            ItemStack[] before=ChargeInventory.copy(player.getInventory().getStorageContents());
            ItemStack[] after=ChargeInventory.copy(before); int left=quest.amount();
            for(int i=0;i<after.length&&left>0;i++) {
                var stack=after[i];if(stack==null||!quest.matchesMaterial(stack.getType()))continue;
                int take=Math.min(left,stack.getAmount());left-=take;
                if(take==stack.getAmount())after[i]=null;else stack.setAmount(stack.getAmount()-take);
            }
            if(left!=0)throw new IllegalStateException("предметы недоступны в основном инвентаре");
            intent=new InventoryTransfer.Intent(UUID.randomUUID(),player.getUniqueId(),ChargeInventory.encode(before),ChargeInventory.encode(after));
        }
        journal.prepare(player.getUniqueId(),quest.id(),amount,intent);
        reconcile(player);
        if(!journal.award(player.getUniqueId(),quest.id()).ready())throw new IllegalStateException("ожидается подтверждение инвентаря");
        return amount;
    }
    boolean admin(org.bukkit.command.CommandSender sender,String[] args) {
        if(args.length==1 && args[0].equalsIgnoreCase("recovery")) {
            plugin.messages().send(sender,"finance-status","healthy",journal.healthy(),"conflict",conflict,"lineage",journal.lineage());
            int shown=0;
            for(var owner:journal.snapshot().entrySet()) for(var row:owner.getValue().entrySet()) if(!row.getValue().ready() && shown++<20)
                plugin.messages().send(sender,"finance-pending","owner",owner.getKey(),"quest",row.getKey());
            return true;
        }
        if(args.length==4 && args[0].equalsIgnoreCase("resolve") && args[3].equalsIgnoreCase("confirm")) {
            try {
                UUID owner=UUID.fromString(args[1]); String id=args[2].toLowerCase(Locale.ROOT);
                var award=journal.award(owner,id);
                if(award==null||award.ready()||!store.healthy())throw new IllegalStateException("no pending inventory");
                journal.confirmInventory(owner,id);store.markDone(owner,id);plugin.requestSave();
                if(owner.equals(inventoryConflictOwner)&&id.equals(inventoryConflictQuest)) {conflict=false;inventoryConflictOwner=null;inventoryConflictQuest=null;}
                plugin.getLogger().warning("[Quests] Администратор "+sender.getName()+" подтвердил списание после сверки: "+owner+"/"+id);
                plugin.messages().send(sender,"finance-resolved");
            } catch(Exception error) {plugin.messages().send(sender,"claim-finance-wait");}
            return true;
        }
        return false;
    }
    void tick(){
        if(conflict||!store.healthy()||!journal.healthy())return;
        try {
            for(Player p:Bukkit.getOnlinePlayers()) if(ready(p)&&journal.pending(p.getUniqueId())) reconcile(p);
            if(!QuestFinance.bind(journal,bank))return;
            var totals=new ArrayList<>(journal.totals().entrySet());
            for(int i=0;i<Math.min(4,totals.size());i++) {
                var row=totals.get(Math.floorMod(cursor++,totals.size()));
                QuestFinance.payOne(journal,bank,row.getKey(),row.getValue());
            }
        } catch(Exception error){conflict=true;plugin.getLogger().log(java.util.logging.Level.SEVERE,"[Quests] Источник наград остановлен; сохраните quest-money.json, quests.json, bank.json и playerdata для сверки",error);}
    }
}
