package ru.cherdakmd.chrdkclasses;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import ru.cherdakmd.chrdkregister.Messages;
import java.util.*;

/** Паспорт, три страницы специализаций и подтверждение любых замен сборки. */
final class DevelopmentGui implements Listener {
    private final ClassesPlugin plugin;
    private static final int[] NODES={10,11,12,13,14,19,20,21,22,23,28,29,30,31,32};
    private static final class Confirmation {
        final DevelopmentService.Quote quote;
        boolean used;
        Confirmation(DevelopmentService.Quote quote) { this.quote=quote; }
    }
    private static final class Holder implements InventoryHolder {
        final Player owner;
        final String screen;
        final int page;
        Inventory inventory;
        Confirmation confirmation;
        int pageLimit;
        String role;
        Holder(Player owner,String screen,int page) { this.owner=owner;this.screen=screen;this.page=page; }
        public Inventory getInventory() { return inventory; }
    }
    DevelopmentGui(ClassesPlugin plugin) { this.plugin=plugin; }
    private Component text(String key,Object... args) { return plugin.messages().get(key,args); }
    private ItemStack item(Material type,Component name,List<Component> lore) {
        ItemStack item=new ItemStack(type); var meta=item.getItemMeta();
        meta.displayName(name);meta.lore(lore);item.setItemMeta(meta);return item;
    }
    private void button(Holder h,int slot,Material type,String key,List<Component> lore,Object...args) {
        h.inventory.setItem(slot,item(type,text(key,args),lore));
    }
    private Holder holder(Player p,String screen,int page) {
        Holder h=new Holder(p,screen,page);
        h.inventory=Bukkit.createInventory(h,54,text("development-title"));
        button(h,45,Material.ARROW,"development-back",List.of());
        button(h,49,Material.BARRIER,"menu-close",List.of());
        return h;
    }
    List<Component> progress(UUID id) {
        ClassDevelopment d=plugin.store().development(id);
        if(d==null) return List.of(text("development-pending"));
        List<Component> out=new ArrayList<>();
        out.add(text(d.level()==50?"development-max-level":"development-xp","level",d.level(),"xp",d.xp(),"next",ClassDevelopmentRules.nextLevelIn(d.xp())));
        out.add(text("development-distribution","nodes",plugin.store().build(id)==null?0:plugin.store().build(id).ranks().size()));
        out.add(text("development-points","earned",d.earnedPoints(),"spent",plugin.service().spentPoints(id),"free",plugin.service().availablePoints(id)));
        out.add(text("development-sources","levels",d.levelPoints(),"trials",d.trialPoints(),"legacy",d.legacyCredit()));
        for(var source:ClassDevelopment.Source.values()) if(source!=ClassDevelopment.Source.EXPLORATION||d.xpSources().containsKey(source)) out.add(text("development-source","source",Messages.trusted(plugin.messages().raw("development-source-"+source.name().toLowerCase(Locale.ROOT))),"xp",d.xpSources().getOrDefault(source,0L)));
        out.add(text("development-trials","count",plugin.store().milestone(id,plugin.store().classOf(id)),"receipts",d.trialPoints()));
        for(int target:new int[]{10,50,150,400,1000,2000})out.add(text(d.trialAwards().contains("trial-"+target)?"development-trial-done":"development-trial-pending","target",target));
        out.add(text("development-trial-rules"));
        out.add(text("development-earn-rules-"+plugin.store().classOf(id)));
        out.add(text("development-limit"));
        out.add(text("development-respec-left","time",ClassMath.clock(plugin.service().respecCooldownLeft(id,System.currentTimeMillis()))));
        if(d.migration()!=null) {
            var m=d.migration();
            out.add(text("development-migration","before",m.earnedPoints(),"spent",m.spentPoints(),"floor",m.preservedPoints(),"after",d.earnedPoints()));
            out.add(text("development-level-migration","before",m.oldLevel(),"after",d.level()));
            out.add(text(d.firstBuildAvailable()?"development-first-build":"development-first-used"));
        }
        return out;
    }
    void open(Player p) {
        if(!plugin.ready(p)) { plugin.messages().send(p,"development-pending");return; }
        if(plugin.service().classOf(p.getUniqueId())==null) {plugin.gui().openChoices(p);return;}
        Holder h=holder(p,"main",0);
        button(h,4,Material.EXPERIENCE_BOTTLE,"development-passport",progress(p.getUniqueId()));
        for(int i=0;i<3;i++) button(h,20+i*2,Material.ENCHANTED_BOOK,"development-spec-"+plugin.store().classOf(p.getUniqueId())+"-"+i,List.of(text("development-tree-hint")));
        button(h,37,Material.BLAZE_ROD,"development-abilities",List.of(text("development-abilities-hint")));
        button(h,39,Material.WRITABLE_BOOK,"development-builds",List.of(text("development-builds-hint")));
        button(h,41,Material.GRINDSTONE,"development-respec",List.of(text("development-preview-hint")));
        button(h,43,Material.BEACON,"passport-effects-name",List.of(text("passport-effects-hint")));
        ClassBuild current=plugin.store().build(p.getUniqueId());
        for(int slot=1;slot<=2;slot++) {
            ClassAbility ability=plugin.service().development().equipped(p.getUniqueId(),slot);
            button(h,slot==1?46:48,Material.matchMaterial(ability.iconName()),"ability."+ability.key()+".name",List.of(text("development-slot","slot",slot),text("development-cast-hint")));
        }
        button(h,45,Material.COMPASS,"passport-choices",List.of());
        p.openInventory(h.inventory);
    }
    private void effects(Player p) {
        Holder h=holder(p,"effects",0);int slot=19;
        for(var view:plugin.service().effectDetails(p.getUniqueId()).values()) {
            List<Component> lines=new ArrayList<>(ClassPassportText.effect(plugin.messages(),view));
            lines.add(text(plugin.config().effectsEnabled()?"passport-effects-enabled":"passport-effects-disabled"));
            if(view.kind()==EffectKind.REGEN)lines.add(text("development-regen-rules","seconds",plugin.config().pulseSeconds()));
            if(view.kind()==EffectKind.SATURATION)lines.add(text("passport-effect-food"));
            h.inventory.setItem(slot++,item(Material.BEACON,text("effect."+view.kind().id(),"amount",ClassMath.amount(view.kind(),view.effective())),lines));
        }
        p.openInventory(h.inventory);
    }
    void tree(Player p,int page) {
        PlayerClass role=plugin.service().classOf(p.getUniqueId());
        if(role==null){plugin.gui().openChoices(p);return;}
        catalog(p,role.id(),page);
    }
    void catalog(Player p,String role,int page) {
        if(!plugin.ready(p) || PlayerClass.parse(role)==null || page<0 || page>2)return;
        Holder h=holder(p,"tree",page);h.role=role;
        ClassBuild active=plugin.store().build(p.getUniqueId());
        ClassBuild build=active!=null&&active.classId().equals(role)?active:ClassBuild.empty(role);
        List<Talent> nodes=ClassTrees.page(build.classId(),page);
        for(int n=0;n<nodes.size();n++) {
            Talent node=nodes.get(n);int rank=build.ranks().getOrDefault(node.id(),0);
            List<Component> lore=new ArrayList<>();
            lore.add(text("development-rank","rank",rank,"max",node.maxRank(),"cost",rank==node.maxRank()?0:node.rankCost(rank+1),"level",node.minLevel()));
            lore.add(text("development-node-id","id",node.id()));
            lore.add(text("development-node-effect","value",ClassMath.amount(node.kind(),node.value()),"effect",Messages.trusted(plugin.messages().raw("effect."+node.kind().id(),"amount",ClassMath.amount(node.kind(),node.value())))));
            ClassEffectView view=active!=null&&active.classId().equals(role)?plugin.service().effectDetails(p.getUniqueId()).get(node.kind())
                    :new ClassEffectView(node.kind(),ClassCatalog.passive(role).getOrDefault(node.kind(),0.0),0,ClassTrees.ceiling(role,node.kind()));
            if(node.hasParent())lore.add(text("development-parent","parent",Messages.trusted(plugin.messages().raw("talent."+node.requires()+".name"))));
            if(node.needsSkill()) {
                ClassDataRead skill=plugin.service().skillSnapshot(p.getUniqueId(),node.skill()),rankRead=plugin.service().rankSnapshot(p.getUniqueId());
                if(skill.usable()&&rankRead.usable())lore.add(text("development-skill-requirement","skill",Messages.trusted(plugin.messages().raw("skill."+node.skill()+".name")),"required",RankPerks.of(rankRead.value()).skillRequirement(node.skillLevel()),"have",skill.value()));
                else lore.add(text("passport-skill-unavailable"));
            }
            lore.add(text("development-node-gain","gain",ClassMath.amount(node.kind(),rank==node.maxRank()?0:view.gain(node.value())),"cap",ClassMath.amount(node.kind(),view.ceiling())));
            if(!node.exclusiveGroup().isEmpty())lore.add(text("development-exclusive"));
            var errors=plugin.service().development().learnProblems(p.getUniqueId(),node.id());
            for(ClassResult error:errors)lore.add(result(error));
            if(errors.isEmpty()) {
                lore.add(text("development-learn"));
                lore.add(text("development-free-after","points",plugin.service().availablePoints(p.getUniqueId())-node.rankCost(rank+1)));
            }
            if(rank<node.maxRank()&&view.gain(node.value())==0)lore.add(text("passport-talent-capped"));
            h.inventory.setItem(NODES[n],item(rank>0?Material.LIME_DYE:Material.ENCHANTED_BOOK,text("talent."+node.id()+".name"),lore));
        }
        for(int i=0;i<3;i++)button(h,47+i*2,Material.PAPER,"development-spec-"+build.classId()+"-"+i,List.of());
        p.openInventory(h.inventory);
    }
    void abilities(Player p) {
        if(!plugin.ready(p) || plugin.store().build(p.getUniqueId())==null)return;
        Holder h=holder(p,"abilities",0);ClassBuild build=plugin.store().build(p.getUniqueId());
        var all=ClassAbility.all(PlayerClass.parse(build.classId()));
        for(int i=0;i<all.size();i++) {
            var ability=all.get(i);
            button(h,19+i*2,Material.matchMaterial(ability.iconName()),"ability."+ability.key()+".name",List.of(
                text("ability."+ability.key()+".desc"),text("development-ability-level","level",ability.requiredLevel(),"cooldown",ability.cooldownSeconds()),
                text("development-ability-equipped","slot",build.abilities().indexOf(ability.key())+1,"left",(ClassAbility.remaining(plugin.store().lastAbility(p.getUniqueId(),ability.key()),System.currentTimeMillis(),ability.cooldownMillis(plugin.config().abilityCooldownMultiplier(),plugin.service().perks(p.getUniqueId()).cooldownFactor()))+999)/1000),
                text("development-abilities-hint")));
        }
        p.openInventory(h.inventory);
    }
    void builds(Player p) {
        if(!plugin.ready(p)||plugin.store().build(p.getUniqueId())==null)return;
        Holder h=holder(p,"builds",0);
        var saved=plugin.store().savedBuilds(p.getUniqueId());
        for(int slot=1;slot<=3;slot++)button(h,19+(slot-1)*3,Material.WRITABLE_BOOK,"development-build-slot",List.of(
                text("development-build-state","points",saved.containsKey(slot)?saved.get(slot).spent():0),text("development-builds-hint")),"slot",slot);
        p.openInventory(h.inventory);
    }
    private Component result(ClassResult r) {return plugin.messages().get(r.key(),ClassText.localise(plugin.messages(),r).toArray());}
    void reset(Player p) {
        ClassBuild b=plugin.store().build(p.getUniqueId());
        if(b!=null) preview(p,new ClassBuild(b.classId(),Map.of(),b.abilities()));
    }
    void preview(Player p,ClassBuild target) {
        if(!plugin.ready(p))return;
        var errors=plugin.service().development().problems(p.getUniqueId(),target);
        if(!errors.isEmpty()) {errors.forEach(e->p.sendMessage(result(e)));return;}
        Confirmation token=new Confirmation(plugin.service().development().quote(p.getUniqueId(),target,System.currentTimeMillis()));
        previewPage(p,token,0);
    }
    private void previewPage(Player p,Confirmation token,int page) {
        Holder h=holder(p,"confirm",page);h.confirmation=token;
        ClassBuild before=plugin.store().build(p.getUniqueId()),after=token.quote.target();
        List<ItemStack> rows=new ArrayList<>();
        for(Talent node:ClassTrees.talents(after.classId())) {
            int old=before.ranks().getOrDefault(node.id(),0),next=after.ranks().getOrDefault(node.id(),0);
            if(old!=0 || next!=0)rows.add(item(Material.PAPER,text("talent."+node.id()+".name"),List.of(text("development-rank-change","before",old,"after",next))));
        }
        var beforeEffects=plugin.service().effectDetails(p.getUniqueId());
        var afterEffects=plugin.service().effectDetails(p.getUniqueId(),after);
        for(var kind:EffectKind.values())rows.add(item(Material.BEACON,text("effect."+kind.id(),"amount",ClassMath.amount(kind,afterEffects.get(kind).effective())),List.of(
                text("development-effect-change","before",ClassMath.amount(kind,beforeEffects.get(kind).effective()),"after",ClassMath.amount(kind,afterEffects.get(kind).effective()),"cap",ClassMath.amount(kind,afterEffects.get(kind).ceiling())))));
        for(int i=0;i<2;i++)rows.add(item(Material.BLAZE_ROD,text("development-slot","slot",i+1),List.of(text("development-ability-change","before",before.abilities().get(i),"after",after.abilities().get(i)))));
        h.pageLimit=Math.max(0,(rows.size()-1)/28);
        button(h,4,Material.SUNFLOWER,"development-preview",List.of(text("development-point-change","before",before.spent(),"after",after.spent(),"free",plugin.service().earnedPoints(p.getUniqueId())-after.spent()),text("development-preview-hint")));
        for(int i=page*28;i<Math.min(rows.size(),(page+1)*28);i++)h.inventory.setItem(9+i-page*28,rows.get(i));
        button(h,46,Material.ARROW,"development-page-prev",List.of());
        button(h,48,Material.ARROW,"development-page-next",List.of());
        button(h,51,Material.LIME_DYE,"respec-preview-confirm",List.of());
        p.openInventory(h.inventory);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void click(InventoryClickEvent e) {
        if(!(e.getView().getTopInventory().getHolder() instanceof Holder h))return;
        e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p)||h.owner!=p||e.getRawSlot()<0||e.getRawSlot()>=54)return;
        if(!p.hasPermission("chrdkclasses.use")||!plugin.ready(p)){p.closeInventory();return;}
        int slot=e.getRawSlot();
        if(slot==49 && !h.screen.equals("tree")){p.closeInventory();return;}
        if(slot==45){if(h.screen.equals("main"))plugin.gui().openChoices(p);else open(p);return;}
        UUID id=p.getUniqueId();ClassBuild build=plugin.store().build(id);if(build==null && !h.screen.equals("tree"))return;
        switch(h.screen) {
            case "main" -> {if(slot==20||slot==22||slot==24)tree(p,(slot-20)/2);else if(slot==37)abilities(p);else if(slot==39)builds(p);else if(slot==41)reset(p);else if(slot==43)effects(p);else if(slot==46||slot==48){
                ClassResult r=plugin.abilities().use(p,slot==46?1:2);p.sendMessage(result(r));}}
            case "tree" -> {
                if(slot==47||slot==49||slot==51){catalog(p,h.role,(slot-47)/2);return;}
                for(int i=0;i<NODES.length;i++)if(slot==NODES[i]) {
                    ClassResult r=plugin.service().learn(id,ClassTrees.page(h.role,h.page).get(i).id());
                    p.sendMessage(result(r));if(r.ok()){plugin.effects().apply(p);plugin.requestSave();}catalog(p,h.role,h.page);break;
                }
            }
            case "abilities" -> {
                if(slot>=19 && slot<=25 && (slot-19)%2==0) {
                    var key=ClassAbility.all(PlayerClass.parse(build.classId())).get((slot-19)/2).key();
                    List<String> next=new ArrayList<>(build.abilities());int selected=e.isRightClick()?1:0;
                    int previous=next.indexOf(key);if(previous>=0)Collections.swap(next,previous,selected);else next.set(selected,key);
                    preview(p,new ClassBuild(build.classId(),build.ranks(),next));
                }
            }
            case "builds" -> {
                if(slot>=19&&slot<=25&&(slot-19)%3==0){int index=(slot-19)/3+1;
                    if(e.isRightClick()) {plugin.store().saveBuild(id,index);plugin.requestSave();builds(p);}
                    else {ClassBuild saved=plugin.store().savedBuilds(id).get(index);if(saved!=null)preview(p,saved);else plugin.messages().send(p,"development-build-empty");}
                }
            }
            case "confirm" -> {
                if(h.confirmation==null||h.confirmation.used)return;
                if(slot==46||slot==48){previewPage(p,h.confirmation,Math.clamp(h.page+(slot==46?-1:1),0,h.pageLimit));return;}
                if(slot==51){h.confirmation.used=true;ClassResult r=plugin.service().development().apply(id,h.confirmation.quote,System.currentTimeMillis());
                    p.sendMessage(result(r));if(r.ok()){plugin.gameplay().clear(id);plugin.effects().apply(p);plugin.requestSave();}open(p);}
            }
            default -> { }
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Holder)e.setCancelled(true);}
}
