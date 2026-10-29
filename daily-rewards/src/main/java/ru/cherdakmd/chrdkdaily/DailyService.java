package ru.cherdakmd.chrdkdaily;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import java.util.*;

/** Frozen UTC day/cycle/VIP before any payout. Money is only cumulative /bal income. */
public final class DailyService {
    private final DailyRewardsPlugin plugin;private final DailyStore store;
    private int cursor; private boolean conflict;
    private final DailyDelivery.Bank bank=new DailyDelivery.Bank(){
        public boolean available(){return plugin.coreApi()!=null&&plugin.coreApi().personalBankAvailable();}
        public boolean registered(UUID l){return plugin.coreApi().incomeSourceAvailable(DailyStore.SOURCE,l);}
        public boolean register(UUID l){return plugin.coreApi().registerIncomeSource(DailyStore.SOURCE,l);}
        public boolean known(UUID id){return plugin.coreApi().canReceiveBankIncome(id);}
        public long credited(UUID l,UUID id){return plugin.coreApi().creditedEntitlement(DailyStore.SOURCE,l,id.toString(),id);}
        public boolean credit(UUID l,UUID id,long total){return plugin.coreApi().creditEntitlement(DailyStore.SOURCE,l,id.toString(),id,total);}
    };
    public DailyService(DailyRewardsPlugin plugin,DailyStore store){this.plugin=plugin;this.store=store;}
    public DailyStore store(){return store;}
    public record ClaimResult(int streak,List<DailyConfig.ItemReward> items,int passHours,boolean vip,int cycleDay,long money){}
    private boolean eligible(Player p){return Bukkit.isPrimaryThread()&&p!=null&&p.isOnline()&&!p.isDead()&&!p.hasMetadata("NPC")&&p.hasPermission("chrdkdaily.use")&&plugin.coreApi()!=null&&plugin.coreApi().isAuthenticated(p);}
    public boolean canClaim(Player p){return !conflict&&eligible(p)&&store.canClaim(p.getUniqueId(),System.currentTimeMillis());}
    public ClaimResult claim(Player p){
        if(!canClaim(p))return null;
        try {
            if(!DailyDelivery.bind(store,bank)) {waitMessage(p);return null;}
            try{DailyDelivery.money(store,bank);}catch(Exception e){conflict=true;throw e;}
            boolean vip=plugin.coreApi().rankLevel(p.getName())>=Math.max(1,plugin.getConfig().getInt("vip.min-rank",2));
            var r=store.accept(p.getUniqueId(),System.currentTimeMillis(),plugin.config(),vip);if(r==null)return null;
            DailyDelivery.money(store,bank); // Cap/unknown UUID is not cancellation of a saved reward.
            if(!deliver(p)){waitMessage(p);return null;}
            return new ClaimResult(r.streak(),r.items(),r.hours(),r.vip(),r.cycleDay(),r.money());
        } catch(Exception e){failure(e);waitMessage(p);return null;}
    }
    private void waitMessage(Player p){plugin.messages().send(p,"finance.daily-wait");}
    private void failure(Exception e){plugin.getLogger().log(java.util.logging.Level.SEVERE,"DailyRewards: выдача ожидает сверки; не удаляйте daily-data.json",e);if(!store.healthy())conflict=true;}
    private boolean deliver(Player p)throws Exception {
        return DailyDelivery.nativeEffects(store,p.getUniqueId(),new DailyDelivery.Native(){
            public void item(DailyConfig.ItemReward reward)throws Exception {Material m=Material.matchMaterial(reward.material());if(m==null||!m.isItem())throw new IllegalStateException("frozen material unavailable");giveItems(p,m,reward.amount());p.saveData();}
            public void boost(int hours)throws Exception {plugin.coreApi().grantRewardBoostHours(p.getName(),hours,"daily-rewards");if(!plugin.coreApi().checkpointGameplayRewards())throw new java.io.IOException("boost checkpoint unavailable");}
        });
    }
    public void tick(){
        if(conflict||!store.healthy())return;
        try {
            try{DailyDelivery.money(store,bank);}catch(Exception e){conflict=true;throw e;}
            var online=new ArrayList<>(Bukkit.getOnlinePlayers());
            for(int i=0;i<Math.min(4,online.size());i++){var p=online.get(Math.floorMod(cursor++,online.size()));if(eligible(p)&&store.pending(p.getUniqueId())!=null)deliver(p);}
        }catch(Exception e){failure(e);}
    }
    public boolean admin(org.bukkit.command.CommandSender sender,String[] args){
        if(!sender.hasPermission("chrdkdaily.admin"))return false;
        if(args.length==1&&args[0].equalsIgnoreCase("recovery")){plugin.messages().send(sender,"finance.daily-status","healthy",store.healthy(),"lineage",store.lineage(),"registered",store.registered());return true;}
        if(args.length==3&&args[0].equalsIgnoreCase("resolve")&&args[2].equalsIgnoreCase("confirm")){
            try{var id=UUID.fromString(args[1]);var r=store.pending(id);if(r==null||!r.executing())throw new IllegalStateException("not executing");store.finishEffect(id);plugin.getLogger().warning("Daily native effect manually reconciled by "+sender.getName()+": owner="+id+" day="+r.day()+" effect="+r.completed());plugin.messages().send(sender,"finance.daily-resolved");}catch(Exception e){plugin.messages().send(sender,"finance.daily-wait");}return true;
        }
        return false;
    }
    public static void giveItems(Player player,Material material,int amount){
        if(DailyStore.moneyUnit(material.name())>0)throw new IllegalArgumentException("money must use /bal");
        boolean dropped=false;
        for(int remaining=amount;remaining>0;){int take=Math.min(material.getMaxStackSize(),remaining);var leftover=player.getInventory().addItem(new org.bukkit.inventory.ItemStack(material,take));for(var item:leftover.values()){player.getWorld().dropItemNaturally(player.getLocation(),item);dropped=true;}remaining-=take;}
        if(dropped)player.getWorld().save(); // Preserve overflow drops; uncertain world outcome remains EXECUTING.
    }
    public static String itemsText(List<DailyConfig.ItemReward> items){var parts=new ArrayList<String>();items.forEach(i->parts.add(i.material().toLowerCase(Locale.ROOT)+" ×"+i.amount()));return parts.isEmpty()?"—":String.join(", ",parts);}
}
