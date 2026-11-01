package ru.cherdakmd.chrdkbp;

import java.util.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

/** Главная → задания/лента → подробные условия. Действия привязаны к UUID и снимку сезона. */
final class BattlePassGuide implements Listener {
    static final class Holder implements InventoryHolder {
        final UUID owner;final BattlePassConfig.Season season;
        final Map<Integer,Consumer<Player>> actions=new HashMap<>();
        boolean consumed;Inventory inventory;
        Holder(UUID owner,BattlePassConfig.Season season){this.owner=owner;this.season=season;}
        @Override public Inventory getInventory(){return inventory;}
    }
    private final BattlePassPlugin plugin;
    BattlePassGuide(BattlePassPlugin plugin){this.plugin=plugin;}
    private Holder window(Player p,String title) {
        if(!plugin.canUse(p)||!plugin.settings().menuEnabled()){plugin.messages().send(p,"access-unavailable");return null;}
        if(plugin.season()==null||plugin.season().empty()){plugin.messages().send(p,"no-season");return null;}
        Holder h=new Holder(p.getUniqueId(),plugin.season());h.inventory=Bukkit.createInventory(h,54,plugin.messages().get(title));return h;
    }
    private void put(Holder h,int slot,Material material,String title,String lore,Consumer<Player> action,Object... args) {
        ItemStack item=new ItemStack(material);var meta=item.getItemMeta();meta.displayName(plugin.messages().get(title,args));
        if(lore!=null)meta.lore(plugin.messages().getList(lore,args));item.setItemMeta(meta);h.inventory.setItem(slot,item);
        if(action!=null)h.actions.put(slot,action);
    }
    private void controls(Holder h) {
        put(h,45,Material.ARROW,"guide-back",null,this::home);
        put(h,49,Material.OAK_DOOR,"menu-close",null,Player::closeInventory);
    }
    void home(Player p) {
        Holder h=window(p,"guide-home");if(h==null)return;
        var season=h.season;long now=System.currentTimeMillis();
        var progress=plugin.store().progressMap(p.getUniqueId());int points=BattlePassScore.points(season,progress);
        int total=season.weeks().stream().flatMap(w->w.missions().stream()).mapToInt(BattlePassConfig.MissionDef::points).sum();
        int done=BattlePassScore.completed(season,progress);
        int count=season.weeks().stream().mapToInt(w->w.missions().size()).sum();
        String end=season.hasEnd()?java.time.Instant.ofEpochMilli(season.endEpochMillis()).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString():plugin.messages().plain("guide-no-end");
        put(h,4,Material.NETHER_STAR,"guide-season","guide-overview",null,
                "season",BattlePassScreen.described(season.name()),"points",points,"total",total,
                "level",1+points/25,"max",1+total/25,"next",Math.max(0,Math.min(total,(points/25+1)*25)-points),
                "done",done,"count",count,"end",end,"status",plugin.messages().plain(season.ended(now)?"guide-ended":now<season.startEpochMillis()?"guide-not-started":"guide-running"));
        put(h,22,Material.WRITABLE_BOOK,"guide-tasks","guide-tasks-lore",x->plugin.menu().missions(x,0));
        put(h,24,Material.CHEST,"guide-rewards","guide-rewards-lore",x->rewards(x,0));
        put(h,30,Material.KNOWLEDGE_BOOK,"guide-help","guide-help-lore",this::help);
        put(h,31,Material.ENDER_CHEST,"menu-claim-all","guide-claim-lore",x->{plugin.claims().claimAll(x);home(x);});
        put(h,32,Material.GOLD_INGOT,"menu-premium-name","guide-tracks",x->{x.closeInventory();x.performCommand("pass shop");});
        put(h,40,Material.EXPERIENCE_BOTTLE,"guide-bank","guide-bank-lore",null,
                "minutes",(plugin.coreApi().rewardBoostRemainingMillis(p.getName())+59_999)/60_000);
        var recommended=season.weeks().stream().filter(w->now>=season.startEpochMillis()+w.unlockDay()*86_400_000L)
                .flatMap(w->w.missions().stream()).map(m->view(p,m)).filter(v->v.state()==BattlePassView.State.READY||!v.goalReached())
                .sorted(Comparator.<BattlePassView.Mission>comparingInt(v->v.state()==BattlePassView.State.READY?0:1)
                        .thenComparing(Comparator.comparingDouble((BattlePassView.Mission v)->v.shownProgress()/(double)v.def().target()).reversed())).findFirst();
        recommended.ifPresent(v->put(h,20,Material.TARGET,"guide-recommended","guide-recommended-lore",x->detail(x,v.def().id()),
                "desc",BattlePassScreen.described(v.def()),"progress",v.shownProgress(),"target",v.def().target()));
        put(h,49,Material.OAK_DOOR,"menu-close",null,Player::closeInventory);p.openInventory(h.inventory);
    }
    private BattlePassView.Mission view(Player p,BattlePassConfig.MissionDef m) {
        UUID id=p.getUniqueId();return new BattlePassView.Mission(m,plugin.store().progressOf(id,m.id()),
                plugin.store().isClaimed(id,m.id(),"FREE"),plugin.store().isClaimed(id,m.id(),"VIP"),plugin.vipActive(p),
                plugin.store().isClaimed(id,m.id(),"PREMIUM"),plugin.premiumActive(p));
    }
    void rewards(Player p,int requested) {
        Holder h=window(p,"guide-rewards");if(h==null)return;
        var missions=h.season.weeks().stream().flatMap(w->w.missions().stream()).toList();
        int pages=Math.max(1,(missions.size()+6)/7);int page=Math.clamp(requested,0,pages-1);
        put(h,4,Material.MAP,"guide-reward-page","guide-reward-page-lore",null,"page",page+1,"pages",pages);
        put(h,18,Material.LIME_DYE,"guide-free",null,null);put(h,27,Material.PURPLE_DYE,"guide-vip",null,null);put(h,36,Material.YELLOW_DYE,"guide-premium",null,null);
        for(int col=0;col<7&&page*7+col<missions.size();col++) {
            var m=missions.get(page*7+col);var v=view(p,m);
            put(h,10+col,Material.BOOK,"guide-mission-name","guide-recommended-lore",x->detail(x,m.id()),
                    "desc",BattlePassScreen.described(m),"progress",v.shownProgress(),"target",m.target());
            track(h,p,m,"FREE",m.freeHours(),true,19+col);
            track(h,p,m,"VIP",m.vipHours(),plugin.vipActive(p),28+col);
            track(h,p,m,"PREMIUM",m.premiumHours(),plugin.premiumActive(p),37+col);
        }
        controls(h);
        if(page>0)put(h,48,Material.ARROW,"menu-prev",null,x->rewards(x,page-1));
        if(page+1<pages)put(h,50,Material.ARROW,"menu-next",null,x->rewards(x,page+1));
        put(h,53,Material.ENDER_CHEST,"menu-claim-all","guide-claim-lore",x->{plugin.claims().claimAll(x);rewards(x,page);});
        p.openInventory(h.inventory);
    }
    private void track(Holder h,Player p,BattlePassConfig.MissionDef m,String track,int hours,boolean access,int slot) {
        var found=BattlePassConfig.find(h.season,m.id());
        boolean unlocked=BattlePassConfig.isUnlocked(h.season,found.weekIndex(),System.currentTimeMillis());
        boolean claimed=plugin.store().isClaimed(p.getUniqueId(),m.id(),track);
        String state=hours==0?"guide-no-reward":claimed?"guide-received":!unlocked?"guide-future":!access?"guide-access-locked":plugin.store().progressOf(p.getUniqueId(),m.id())<m.target()?"guide-in-progress":"guide-ready";
        put(h,slot,claimed?Material.GRAY_DYE:state.equals("guide-ready")?Material.LIME_CONCRETE:access?Material.EXPERIENCE_BOTTLE:Material.BARRIER,
                "guide-track-reward","guide-track-lore",x->detail(x,m.id()),"track",plugin.messages().plain("guide-"+track.toLowerCase(Locale.ROOT)),
                "hours",hours,"state",plugin.messages().plain(state));
    }
    void detail(Player p,String id) {
        Holder h=window(p,"guide-detail");if(h==null)return;var found=BattlePassConfig.find(h.season,id);if(found==null){home(p);return;}
        var m=found.mission();var v=view(p,m);
        String worlds=m.worlds().isEmpty()?plugin.messages().plain("guide-any-world"):String.join(", ",m.worlds());
        put(h,13,Material.BOOK,"guide-mission-name","guide-conditions",null,"desc",BattlePassScreen.described(m),
                "progress",v.shownProgress(),"target",m.target(),"remaining",Math.max(0,m.target()-v.shownProgress()),"points",m.points(),
                "worlds",worlds,"permission",m.permission().isEmpty()?plugin.messages().plain("guide-no-permission"):m.permission(),
                "day",found.week().unlockDay(),"date",java.time.Instant.ofEpochMilli(BattlePassConfig.unlockAt(h.season,found.weekIndex())).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString());
        // Названия целей переводит клиент Minecraft; технические enum не заменяют объяснение задания.
        List<Component> targets=new ArrayList<>();targets.add(plugin.messages().get("guide-targets"));
        for(String key:m.matchKey().split("\\|")) {
            if(key.equalsIgnoreCase("any")){targets.add(plugin.messages().get("guide-any-target"));continue;}
            try {
                if(m.type()==BattlePassConfig.MissionType.KILL||m.type()==BattlePassConfig.MissionType.BREED)
                    targets.add(Component.translatable(org.bukkit.entity.EntityType.valueOf(key.toUpperCase(Locale.ROOT)).getTranslationKey()));
                else {var mat=Material.matchMaterial(key);if(mat!=null)targets.add(Component.translatable(mat.getTranslationKey()));}
            } catch(IllegalArgumentException ignored){targets.add(Component.text(key));}
        }
        ItemStack target=new ItemStack(Material.SPYGLASS);var meta=target.getItemMeta();meta.displayName(plugin.messages().get("guide-targets"));meta.lore(targets);target.setItemMeta(meta);h.inventory.setItem(15,target);
        track(h,p,m,"FREE",m.freeHours(),true,20);track(h,p,m,"VIP",m.vipHours(),plugin.vipActive(p),22);track(h,p,m,"PREMIUM",m.premiumHours(),plugin.premiumActive(p),24);
        put(h,31,Material.ENDER_CHEST,"guide-claim-one","guide-claim-lore",x->{plugin.claims().claimOne(x,id);detail(x,id);});
        controls(h);put(h,45,Material.ARROW,"guide-tasks",null,x->plugin.menu().missions(x,0));p.openInventory(h.inventory);
    }
    void help(Player p) {
        Holder h=window(p,"guide-help");if(h==null)return;
        put(h,13,Material.KNOWLEDGE_BOOK,"guide-help","guide-help-lore",null);
        put(h,22,Material.SHIELD,"guide-fair","guide-fair-lore",null);
        put(h,31,Material.EXPERIENCE_BOTTLE,"guide-bank","guide-bank-lore",null,"minutes",(plugin.coreApi().rewardBoostRemainingMillis(p.getName())+59_999)/60_000);
        controls(h);p.openInventory(h.inventory);
    }
    void closeAll(){for(Player p:Bukkit.getOnlinePlayers())if(p.getOpenInventory().getTopInventory().getHolder() instanceof Holder h){h.consumed=true;p.closeInventory();}}
    @EventHandler public void click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder h))return;event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player p)||!h.owner.equals(p.getUniqueId())||!plugin.canUse(p)||h.consumed)return;
        if(event.getClick()!=ClickType.LEFT&&event.getClick()!=ClickType.RIGHT)return;
        var action=h.actions.get(event.getRawSlot());if(action==null)return;h.consumed=true;
        Bukkit.getScheduler().runTask(plugin,()->{
            if(!plugin.canUse(p)||p.getOpenInventory().getTopInventory().getHolder()!=h)return;
            if(h.season!=plugin.season()){home(p);return;}action.accept(p);
        });
    }
    @EventHandler public void drag(InventoryDragEvent event){if(event.getView().getTopInventory().getHolder() instanceof Holder)event.setCancelled(true);}
}
