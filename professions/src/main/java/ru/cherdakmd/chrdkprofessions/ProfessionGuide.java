package ru.cherdakmd.chrdkprofessions;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import java.util.*;

/** Навигация Aura-подобной структуры: источники, открытия, способности, испытания и паспорт. */
final class ProfessionGuide implements Listener {
    enum Page { SOURCES, ROADMAP, ABILITIES, TRIALS, PASSPORT }
    private static final int PER_PAGE=28;
    private final ProfessionsPlugin plugin;
    private static final class Holder implements InventoryHolder {
        final UUID owner;final Profession profession;final Page page;final int offset;Inventory inventory;
        Holder(Player player,Profession profession,Page page,int offset){owner=player.getUniqueId();this.profession=profession;this.page=page;this.offset=offset;}
        public Inventory getInventory(){return inventory;}
    }
    ProfessionGuide(ProfessionsPlugin plugin){this.plugin=plugin;}
    void open(Player player,Profession profession,Page page,int offset){
        if(profession==null){plugin.gui().open(player);return;}
        int count=page==Page.SOURCES?ProfessionCatalog.sources(profession).size():page==Page.ROADMAP?100:page==Page.TRIALS?4:page==Page.ABILITIES?3:8;
        int last=Math.max(0,(count-1)/PER_PAGE),selected=Math.clamp(offset,0,last);
        Holder holder=new Holder(player,profession,page,selected);
        holder.inventory=Bukkit.createInventory(holder,54,plugin.messages().get("guide.title", "page",plugin.messages().get("guide.page."+page.name().toLowerCase(Locale.ROOT)),"profession",ProfessionText.nameComponent(plugin.messages(),profession)));
        for(int i=0;i<5;i++)holder.inventory.setItem(i+2,item(new Material[]{Material.BOOK,Material.EXPERIENCE_BOTTLE,Material.NETHER_STAR,Material.WRITABLE_BOOK,Material.PLAYER_HEAD}[i],"guide.nav",List.of(plugin.messages().get("guide.page."+Page.values()[i].name().toLowerCase(Locale.ROOT))),"name",plugin.messages().get("guide.page."+Page.values()[i].name().toLowerCase(Locale.ROOT))));
        for(int i=0;i<PER_PAGE;i++){
            int index=selected*PER_PAGE+i;if(index>=count)break;int slot=10+(i/7)*9+i%7;
            holder.inventory.setItem(slot,switch(page){
                case SOURCES -> source(ProfessionCatalog.sources(profession).get(index));
                case ROADMAP -> level(player,profession,index+1);
                case TRIALS -> trial(player,profession,index);
                case ABILITIES -> ability(player,profession,index);
                case PASSPORT -> passport(player,Profession.values()[index]);
            });
        }
        holder.inventory.setItem(45,item(Material.ARROW,"menu-back",List.of()));
        if(selected>0)holder.inventory.setItem(48,item(Material.ARROW,"guide.previous",List.of()));
        holder.inventory.setItem(49,item(Material.BARRIER,"menu-close",List.of()));
        if(selected<last)holder.inventory.setItem(50,item(Material.ARROW,"guide.next",List.of()));
        holder.inventory.setItem(53,item(Material.PAPER,"guide.pagination",List.of(plugin.messages().get("guide.conditions")),"current",selected+1,"total",last+1));
        player.openInventory(holder.inventory);
    }
    private ItemStack source(ProfessionCatalog.Source row){
        String xp=row.action().equals("brew")?"8–12":row.action().equals("anvil")?"2–10":Integer.toString(row.xp());
        return item(row.material(),"guide.source",List.of(plugin.messages().get("guide.source-xp","xp",xp),plugin.messages().get("guide.action."+row.action()),plugin.messages().get("guide.condition."+row.action())),"item",Component.translatable(row.material().translationKey()));
    }
    private ItemStack level(Player player,Profession profession,int level){
        int current=plugin.service().level(player.getUniqueId(),profession);
        List<Component> lore=new ArrayList<>();lore.add(plugin.messages().get(current>=level?"guide.unlocked":"guide.locked"));
        lore.add(plugin.messages().get("guide.total-xp","xp",ProfessionMath.totalTo(level,100)));
        lore.add(plugin.messages().get(level==1?"guide.unlock-start":level==5?"guide.unlock-mastery":level%25==0?"guide.unlock-trial":"guide.unlock-practice"));
        if(level%25==0)lore.add(plugin.messages().get("guide.unlock-quality"));
        return item(current>=level?Material.LIME_STAINED_GLASS_PANE:Material.GRAY_STAINED_GLASS_PANE,"guide.level",lore,"level",level);
    }
    private ItemStack trial(Player player,Profession profession,int index){
        var progress=plugin.service().trials().progress(player.getUniqueId(),profession,index);var t=progress.trial();
        List<Component> lore=new ArrayList<>();
        lore.add(plugin.messages().get("guide.trial-level","level",t.level(),"current",plugin.service().level(player.getUniqueId(),profession)));
        lore.add(plugin.messages().get("guide.trial-units","current",progress.units(),"required",t.units()));
        lore.add(plugin.messages().get("guide.trial-sources","current",progress.sources(),"required",t.sources()));
        lore.add(plugin.messages().get("guide.trial-works","current",progress.works(),"required",t.works()));
        lore.add(plugin.messages().get("guide.trial-reward"));
        lore.add(plugin.messages().get(progress.claimed()?"guide.trial-claimed":progress.ready()?"guide.trial-ready":"guide.trial-wait"));
        return item(progress.claimed()?Material.LIME_DYE:progress.ready()?Material.EMERALD:Material.PAPER,"guide.trial",lore,"rank",index+1);
    }
    private ItemStack ability(Player player,Profession profession,int index){
        UUID id=player.getUniqueId();
        if(index==0)return item(Material.SHIELD,"guide.passive",List.of(
                plugin.messages().get("guide.passive-effect","effect",plugin.service().active(id)!=profession?plugin.messages().get("guide.inactive"):!plugin.service().perkEnabled(profession)?plugin.messages().get("guide.effects-disabled"):ProfessionText.perk(plugin.messages(),profession,plugin.service().level(id,profession),plugin.service().powerCap(id))),
                plugin.messages().get(plugin.service().active(id)==profession?"guide.active":"guide.inactive"),plugin.messages().get("guide.power-cap")));
        var mastery=ProfessionMastery.of(profession).get(index-1);
        return item(Material.matchMaterial(mastery.icon()),mastery.nameKey(),List.of(plugin.messages().get(mastery.descKey()),plugin.messages().get(mastery.costKey()),
                plugin.messages().get("guide.batch","amount",plugin.service().trials().batch(id,mastery)),
                plugin.messages().get("mastery-interval","time",ProfessionMath.clock(plugin.service().trials().cooldown(id,mastery))),
                plugin.messages().get("guide.remaining","time",ProfessionMath.clock(plugin.store().workUntil(id,profession)>System.currentTimeMillis()?(plugin.store().workUntil(id,profession)-System.currentTimeMillis()+999)/1000:0)),
                plugin.messages().get(plugin.store().mastery(id,profession)==mastery?"mastery-active":"mastery-pick"),plugin.messages().get("guide.no-xp")));
    }
    private ItemStack passport(Player player,Profession profession){
        UUID id=player.getUniqueId();var mastery=plugin.store().mastery(id,profession);
        return item(Material.matchMaterial(profession.iconName()),"guide.passport",List.of(
                plugin.messages().get("guide.passport-level","level",plugin.service().level(id,profession),"xp",plugin.service().xp(id,profession),"rank",plugin.service().trials().rank(id,profession)),
                plugin.messages().get("mastery-status","specialization",mastery==null?plugin.messages().get("mastery-none"):plugin.messages().get(mastery.nameKey())),
                plugin.messages().get(plugin.service().active(id)==profession?"guide.active":"guide.inactive"),plugin.messages().get("guide.passport-open")),"profession",ProfessionText.nameComponent(plugin.messages(),profession));
    }
    private ItemStack item(Material material,String name,List<Component> lore,Object... args){
        if(material!=null&&!material.isItem())material=switch(material){case CARROTS->Material.CARROT;case POTATOES->Material.POTATO;case BEETROOTS->Material.BEETROOT;case COCOA->Material.COCOA_BEANS;case SWEET_BERRY_BUSH->Material.SWEET_BERRIES;default->Material.PAPER;};
        ItemStack stack=new ItemStack(material==null?Material.PAPER:material);var meta=stack.getItemMeta();meta.displayName(plugin.messages().get(name,args));meta.lore(lore);stack.setItemMeta(meta);return stack;
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void click(InventoryClickEvent event){
        if(!(event.getInventory().getHolder() instanceof Holder h))return;event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player)||!h.owner.equals(player.getUniqueId())||!player.hasPermission("chrdkprofessions.use")||!plugin.config().enabled()||!plugin.authenticated(player))return;
        int slot=event.getRawSlot();if(slot<0||slot>=54)return;
        if(slot==49){player.closeInventory();return;}
        if(slot==45){plugin.gui().openInfo(player,h.profession);return;}
        if(slot>=2&&slot<=6){open(player,h.profession,Page.values()[slot-2],0);return;}
        if(slot==48||slot==50){open(player,h.profession,h.page,h.offset+(slot==48?-1:1));return;}
        int row=(slot-10)/9,column=(slot-10)%9;
        if(slot<10||row<0||row>=4||column<0||column>=7)return;int index=h.offset*PER_PAGE+row*7+column;
        if(h.page==Page.TRIALS&&index<4){
            if(!plugin.authenticated(player))return;
            boolean ok=plugin.service().trials().claim(player.getUniqueId(),h.profession,index);
            if(ok)plugin.requestSave();plugin.messages().send(player,ok?"guide.claimed":"guide.cannot-claim");open(player,h.profession,h.page,h.offset);
        }else if(h.page==Page.PASSPORT&&index<8)open(player,Profession.values()[index],Page.ROADMAP,0);
        else if(h.page==Page.ABILITIES&&index>0&&index<3)plugin.gui().openMasteryConfirm(player,ProfessionMastery.of(h.profession).get(index-1));
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void drag(InventoryDragEvent event){if(event.getInventory().getHolder() instanceof Holder)event.setCancelled(true);}
}
