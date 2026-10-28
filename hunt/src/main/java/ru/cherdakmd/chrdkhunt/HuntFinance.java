package ru.cherdakmd.chrdkhunt;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.api.MilestoneAwards;
import java.io.IOException;
import java.util.*;

/** Финансовый адаптер охоты: только выход в /bal, не конвертация остальных игровых единиц. */
public final class HuntFinance implements AutoCloseable {
    private final HuntPlugin plugin;
    private final HuntStore store;
    private final BukkitTask task;
    private boolean conflict;
    private int cursor;
    private final MilestoneAwards.Bank bank=new MilestoneAwards.Bank() {
        public boolean available() { return plugin.coreApi()!=null&&plugin.coreApi().personalBankAvailable(); }
        public boolean registered(String s,UUID l) { return plugin.coreApi().incomeSourceAvailable(s,l); }
        public boolean register(String s,UUID l) { return plugin.coreApi().registerIncomeSource(s,l); }
        public boolean known(UUID id) { return plugin.coreApi().canReceiveBankIncome(id); }
        public long credited(String s,UUID l,UUID id) { return plugin.coreApi().creditedEntitlement(s,l,id.toString(),id); }
        public boolean credit(String s,UUID l,UUID id,long total) { return plugin.coreApi().creditEntitlement(s,l,id.toString(),id,total); }
    };
    public HuntFinance(HuntPlugin plugin) {
        this.plugin=plugin; store=plugin.store(); tick();
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20L,20L);
    }
    /** Production executor, проверяемый настоящим BankStore. */
    public static boolean deliver(HuntStore store,MilestoneAwards.Bank bank,UUID owner,long total) throws IOException {
        if(!store.healthy()||!bank.available()) return false;
        String source=HuntStore.SOURCE;
        if(store.registered()) { if(!bank.registered(source,store.lineage())) throw new IllegalStateException("hunt lineage unavailable"); }
        else { if(!bank.register(source,store.lineage())) throw new IllegalStateException("hunt lineage conflict"); store.markRegistered(); }
        if(owner==null||!bank.known(owner)) return false;
        if(store.income(owner)!=total) throw new IllegalArgumentException("unbacked hunt income");
        long credited=bank.credited(source,store.lineage(),owner);
        if(credited<0||credited>total) throw new IllegalStateException("hunt source older than bank");
        return total>0&&bank.credit(source,store.lineage(),owner,total);
    }
    public boolean paused() { return conflict||!store.healthy(); }
    public boolean eligible(Player p) {
        return Bukkit.isPrimaryThread()&&store.healthy()&&p!=null&&p.isOnline()&&!p.isDead()&&!p.hasMetadata("NPC")
                &&p.hasPermission("chrdkhunt.use")&&plugin.coreApi()!=null&&plugin.coreApi().isAuthenticated(p)
                &&(p.getGameMode()==GameMode.SURVIVAL||p.getGameMode()==GameMode.ADVENTURE);
    }
    public boolean ready(Player p) {
        if(paused()) { plugin.messages().send(p,"finance-wait"); return false; }
        if(!eligible(p)) { plugin.messages().send(p,"finance-mode"); return false; }
        return true;
    }
    public boolean exchange(Player p,HuntConfig config,long now,int diamonds) {
        if(!ready(p)) return false;
        try {
            boolean accepted=store.exchangeToBank(p.getUniqueId(),p.getName(),now,diamonds,config.exchangeMin(),config.exchangePerDay(),config.coinsPerDiamond());
            if(!accepted) plugin.messages().send(p,"finance-refused"); return accepted;
        } catch(Exception error) { fail(error); plugin.messages().send(p,"finance-wait"); return false; }
    }
    public boolean chest(Player p,HuntFamily family,long now,long coins,ChestResult result) {
        if(!ready(p)) return false;
        try {
            boolean accepted=store.openChestToBank(p.getUniqueId(),p.getName(),family.id(),now,coins,result.shards(),result.diamonds());
            if(!accepted) plugin.messages().send(p,"chest-no-key","key",family.keyName()); return accepted;
        } catch(Exception error) { fail(error); plugin.messages().send(p,"finance-wait"); return false; }
    }
    public void tick() {
        if(paused()) return;
        try {
            var rows=new ArrayList<>(store.incomes().entrySet());
            if(rows.isEmpty()) { deliver(store,bank,null,0); return; }
            for(int i=0;i<Math.min(16,rows.size());i++) { var row=rows.get(Math.floorMod(cursor++,rows.size())); deliver(store,bank,row.getKey(),row.getValue()); }
        } catch(Exception error) { fail(error); }
    }
    private void fail(Exception error) { conflict=true; plugin.getLogger().log(java.util.logging.Level.SEVERE,"Денежный выход Hunt приостановлен; не удаляйте hunt.json",error); }
    public void status(org.bukkit.command.CommandSender sender) {
        tick(); plugin.messages().send(sender,"finance-status","source",HuntStore.SOURCE,"lineage",store.lineage(),"healthy",store.healthy(),"conflict",conflict,"count",store.incomes().size());
    }
    @Override public void close() { task.cancel(); }
}
