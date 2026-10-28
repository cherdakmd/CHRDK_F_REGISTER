package ru.cherdakmd.chrdktreasures;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.api.MilestoneAwards;
import java.io.IOException;
import java.util.*;

/** Звания и коллекция → /bal. Shards и предметы не являются денежным кошельком. */
public final class TreasureFinance implements AutoCloseable {
    private final TreasuresPlugin plugin;
    private final TreasureStore store;
    private final BukkitTask task;
    private boolean conflict;
    private int cursor,progressCursor;
    private final MilestoneAwards.Bank bank=new MilestoneAwards.Bank() {
        public boolean available() { return plugin.coreApi()!=null&&plugin.coreApi().personalBankAvailable(); }
        public boolean registered(String s,UUID l) { return plugin.coreApi().incomeSourceAvailable(s,l); }
        public boolean register(String s,UUID l) { return plugin.coreApi().registerIncomeSource(s,l); }
        public boolean known(UUID id) { return plugin.coreApi().canReceiveBankIncome(id); }
        public long credited(String s,UUID l,UUID id) { return plugin.coreApi().creditedEntitlement(s,l,id.toString(),id); }
        public boolean credit(String s,UUID l,UUID id,long total) { return plugin.coreApi().creditEntitlement(s,l,id.toString(),id,total); }
    };
    public TreasureFinance(TreasuresPlugin plugin) {
        this.plugin=plugin; store=plugin.store();
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20L,20L);
    }
    /** Тот же production executor проверяется настоящим BankStore. */
    public static boolean deliver(TreasureStore store,MilestoneAwards.Bank bank,UUID owner,long total) throws IOException {
        if(!store.healthy()||!bank.available()) return false;
        String source=TreasureStore.SOURCE;
        if(store.registered()) { if(!bank.registered(source,store.lineage())) throw new IllegalStateException("treasures lineage unavailable"); }
        else { if(!bank.register(source,store.lineage())) throw new IllegalStateException("treasures lineage conflict"); store.markRegistered(); }
        if(owner==null||!bank.known(owner)) return false;
        if(store.income(owner)!=total) throw new IllegalArgumentException("unbacked treasures income");
        long credited=bank.credited(source,store.lineage(),owner);
        if(credited<0||credited>total) throw new IllegalStateException("treasures source older than bank");
        return total>0&&bank.credit(source,store.lineage(),owner,total);
    }
    public boolean paused() { return conflict||!store.healthy(); }
    public boolean eligible(Player p) {
        // Админская/API-выдача предмета допустима и вне Survival. Добыча отдельно проверяет honest().
        return Bukkit.isPrimaryThread()&&store.healthy()&&p!=null&&p.isOnline()&&!p.isDead()&&!p.hasMetadata("NPC")
                &&p.hasPermission("chrdktreasures.use")&&plugin.coreApi()!=null&&plugin.coreApi().isAuthenticated(p);
    }
    /** null означает отказ ДО world/inventory grants, а не успешно найденный пустой клад. */
    public Integer record(Player p,TreasureTier tier,String item,long now,long window) {
        if(paused()||!eligible(p)) return null;
        final int result;
        try { result=store.recordRewardedTreasure(p.getUniqueId(),tier,item,now,window,plugin.config()); }
        catch(Exception error) { fail(error); return null; }
        // Source уже принят: недоступность/ошибка банка не отменяет предметную часть находки.
        try { deliver(store,bank,p.getUniqueId(),store.income(p.getUniqueId())); }
        catch(Exception error) { fail(error); }
        return result;
    }
    public void tick() {
        if(paused()) return;
        try {
            var online=new ArrayList<>(Bukkit.getOnlinePlayers());
            for(int i=0;i<Math.min(16,online.size());i++) {
                Player player=online.get(Math.floorMod(progressCursor++,online.size()));
                if(eligible(player)) store.reconcile(player.getUniqueId(),plugin.config());
            }
            var rows=new ArrayList<>(store.incomes().entrySet());
            if(rows.isEmpty()) { deliver(store,bank,null,0); return; }
            for(int i=0;i<Math.min(16,rows.size());i++) {
                var row=rows.get(Math.floorMod(cursor++,rows.size())); deliver(store,bank,row.getKey(),row.getValue());
            }
        } catch(Exception error) { fail(error); }
    }
    private void fail(Exception error) {
        conflict=true; plugin.getLogger().log(java.util.logging.Level.SEVERE,"Денежные награды Treasures приостановлены; не удаляйте treasures.json",error);
        if(!store.healthy()) Bukkit.getPluginManager().disablePlugin(plugin);
    }
    public void status(org.bukkit.command.CommandSender sender) {
        tick(); plugin.messages().send(sender,"finance-status","source",TreasureStore.SOURCE,"lineage",store.lineage(),"healthy",store.healthy(),"conflict",conflict,"count",store.incomes().size());
    }
    @Override public void close() { task.cancel(); }
}
