package ru.cherdakmd.chrdkregister.api;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.IOException;
import java.util.*;
import java.util.function.*;

/** Main-thread adapter: durable minor-unit obligations precede progress and notifications. */
public final class MoneyMilestoneBridge {
    private final JavaPlugin plugin; private final Supplier<ChrdkRegisterApi> api;
    private final FixedMoneyMilestones journal; private final String source; private final BukkitTask task;
    private boolean conflict; private int cursor,playerCursor;
    private Consumer<Player> reconcile=p->{};
    private final FixedMoneyMilestones.Bank bank=new FixedMoneyMilestones.Bank(){
        public boolean available(){return api.get()!=null&&api.get().personalBankAvailable();}
        public boolean registered(String s,UUID l){return api.get().incomeSourceAvailable(s,l);}
        public boolean register(String s,UUID l){return api.get().registerIncomeSource(s,l);}
        public boolean known(UUID id){return api.get().canReceiveBankIncome(id);}
        public long credited(String s,UUID l,UUID id){return api.get().creditedEntitlement(s,l,id.toString(),id);}
        public boolean credit(String s,UUID l,UUID id,long total){return api.get().creditEntitlementMinor(s,l,id.toString(),id,total);}
    };
    /** A missing old ledger plus an existing bank source cannot be treated as a fresh install. */
    public static void requireOldJournalOrFreshInstall(JavaPlugin plugin,ChrdkRegisterApi api,String oldSource) throws IOException {
        var folder=plugin.getDataFolder().toPath();
        if(java.nio.file.Files.exists(folder.resolve(oldSource).resolve("milestone-awards.json")))return;
        var root=folder.resolve("milestone-awards.json");
        if(java.nio.file.Files.exists(root)) {
            if(java.nio.file.Files.size(root)>32L*1024*1024)throw new IOException("Старый журнал слишком велик");
            var data=StrictFinancialJson.read(root);
            if(data.has("source")&&data.get("source").isJsonPrimitive()&&data.get("source").getAsJsonPrimitive().isString()
                    &&oldSource.equals(data.get("source").getAsString()))return;
        }
        if(api==null||!api.personalBankAvailable()||!(api instanceof IncomeSourceLineageProvider provider))throw new IOException("Нельзя проверить отсутствующий старый журнал наград");
        if(provider.incomeSourceLineage(oldSource)!=null)throw new IOException("Старый журнал отсутствует, но банк знает источник; нужна сверка копий");
    }
    public MoneyMilestoneBridge(JavaPlugin plugin,Supplier<ChrdkRegisterApi> api,String source,
            Map<UUID,Map<String,FixedMoneyMilestones.State>> legacy) throws IOException {
        this.plugin=plugin;this.api=api;this.source=source;
        journal=new FixedMoneyMilestones(plugin.getDataFolder().toPath().resolve(source),source);
        journal.importLegacy(legacy);
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20L,20L);
    }
    public void reconciler(Consumer<Player> action){reconcile=Objects.requireNonNull(action);}
    public void close(){task.cancel();}
    public boolean ready(Player p){return !conflict&&journal.healthy()&&Bukkit.isPrimaryThread()&&p!=null&&p.isOnline()
            &&!p.hasMetadata("NPC")&&api.get()!=null&&api.get().isAuthenticated(p);}
    public FixedMoneyMilestones.Award award(Player p,String key){return journal.award(p.getUniqueId(),key);}
    public Map<String,FixedMoneyMilestones.Award> awards(Player p){return journal.awards(p.getUniqueId());}
    public long quote(int diamonds,int hours,int unique){
        if(api.get()==null||!api.get().personalBankAvailable())throw new IllegalStateException("bank unavailable");
        return FixedMoneyMilestones.quote(diamonds,hours,unique,api.get().diamondRate());
    }
    public String preview(int diamonds,int hours,int unique){
        try{return "≈"+DiamondMoney.format(quote(diamonds,hours,unique))+" алмазов в /bal";}
        catch(RuntimeException e){return "оценка /bal недоступна";}
    }
    public FixedMoneyMilestones.Award accept(Player p,String key,int diamonds,int hours,int unique){
        if(!ready(p))return null;
        try {
            if(!journal.bind(bank))return null;
            var existing=journal.award(p.getUniqueId(),key);
            return journal.accept(bank,p.getUniqueId(),key,existing==null?quote(diamonds,hours,unique):existing.minor());
        } catch(Exception e){conflict=true;failure(e);return null;}
    }
    public void tick(){
        if(conflict||!journal.healthy())return;
        try {
            var online=new ArrayList<>(Bukkit.getOnlinePlayers());
            for(int i=0;i<Math.min(4,online.size());i++){var p=online.get(Math.floorMod(playerCursor++,online.size()));if(ready(p))reconcile.accept(p);}
            if(!journal.bind(bank))return;
            var owners=new ArrayList<>(journal.snapshot().keySet());
            for(int i=0;i<Math.min(8,owners.size());i++)journal.pay(bank,owners.get(Math.floorMod(cursor++,owners.size())));
        }catch(Exception e){conflict=true;failure(e);}
    }
    private void failure(Exception e){plugin.getLogger().log(java.util.logging.Level.SEVERE,"["+source+"] Денежные награды остановлены; сохраните журнал, прогресс и bank.json для сверки",e);}
    public boolean admin(CommandSender sender,String[] args){
        if(args.length!=1||!args[0].equalsIgnoreCase("money-recovery"))return false;
        long pending=journal.snapshot().values().stream().flatMap(rows->rows.values().stream()).filter(a->a.state()==FixedMoneyMilestones.State.LEGACY_UNCERTAIN).count();
        sender.sendMessage("["+source+"] journal="+journal.healthy()+", conflict="+conflict+", lineage="+journal.lineage()+", неоднозначных старых="+pending);
        int shown=0;
        for(var owner:journal.snapshot().entrySet())for(var row:owner.getValue().entrySet())
            if(row.getValue().state()==FixedMoneyMilestones.State.LEGACY_UNCERTAIN&&shown++<20)sender.sendMessage("  "+owner.getKey()+" / "+row.getKey());
        if(pending>20)sender.sendMessage("Показаны первые 20; полный список — в журнале fixed-money.json.");
        return true;
    }
}
