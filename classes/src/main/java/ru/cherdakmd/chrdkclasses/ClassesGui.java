package ru.cherdakmd.chrdkclasses;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import ru.cherdakmd.chrdkregister.Messages;
import java.util.*;

/** Выбор четырёх ролей. Весь прогресс и распределение показывает DevelopmentGui. */
final class ClassesGui implements Listener {
    private final ClassesPlugin plugin;
    private static final int[] SLOTS={19,21,23,25};
    private static final class Holder implements InventoryHolder {
        final Player owner;
        final PlayerClass candidate;
        Inventory inventory;
        boolean consumed;
        Holder(Player owner,PlayerClass candidate){this.owner=owner;this.candidate=candidate;}
        public Inventory getInventory(){return inventory;}
    }
    ClassesGui(ClassesPlugin plugin){this.plugin=plugin;}
    void open(Player p){if(p!=null)plugin.developmentGui().open(p);}
    void openTalents(Player p){if(p!=null)plugin.developmentGui().tree(p,0);}
    void openRespec(Player p){if(p!=null&&plugin.ready(p))plugin.developmentGui().reset(p);}
    private void button(Holder h,int slot,Material material,String key,List<Component> lore,Object...args){
        ItemStack item=new ItemStack(material);var meta=item.getItemMeta();
        meta.displayName(plugin.messages().get(key,args));meta.lore(lore);item.setItemMeta(meta);h.inventory.setItem(slot,item);
    }
    private Holder holder(Player p,PlayerClass candidate){
        Holder h=new Holder(p,candidate);h.inventory=Bukkit.createInventory(h,54,plugin.messages().get("menu-title"));
        button(h,45,Material.ARROW,"menu-back",List.of());button(h,49,Material.BARRIER,"menu-close",List.of());return h;
    }
    void openChoices(Player p){
        if(!plugin.ready(p)){plugin.messages().send(p,"development-pending");return;}
        Holder h=holder(p,null);
        button(h,4,Material.EXPERIENCE_BOTTLE,"development-passport",plugin.developmentGui().progress(p.getUniqueId()));
        for(int i=0;i<SLOTS.length;i++){
            var role=PlayerClass.values()[i];List<Component> lore=new ArrayList<>();
            lore.add(plugin.messages().get("class."+role.id()+".desc"));
            for(var entry:ClassCatalog.passive(role.id()).entrySet())lore.add(plugin.messages().get("effect."+entry.getKey().id(),"amount",ClassMath.amount(entry.getKey(),entry.getValue())));
            lore.add(plugin.messages().get(plugin.service().classOf(p.getUniqueId())==role?"menu-class-current":"menu-class-click"));
            button(h,SLOTS[i],Material.matchMaterial(role.iconName()),"class."+role.id()+".name",lore);
        }
        p.openInventory(h.inventory);
    }
    void openInfo(Player p,PlayerClass role){
        if(role==null||!plugin.ready(p))return;
        Holder h=holder(p,role);
        button(h,4,Material.matchMaterial(role.iconName()),"class."+role.id()+".name",List.of(plugin.messages().get("class."+role.id()+".desc")));
        for(int page=0;page<3;page++){
            List<Component> lore=new ArrayList<>();lore.add(plugin.messages().get("development-tree-hint"));
            for(Talent node:ClassTrees.page(role.id(),page))lore.add(plugin.messages().get("talent."+node.id()+".name"));
            button(h,20+page*2,Material.ENCHANTED_BOOK,"development-spec-"+role.id()+"-"+page,lore);
        }
        ClassResult check=plugin.service().checkChoose(p.getUniqueId(),role.id(),System.currentTimeMillis());
        List<Component> lore=new ArrayList<>();
        lore.add(plugin.messages().get("development-choose-consequences"));
        lore.add(plugin.messages().get(check.key(),ClassText.localise(plugin.messages(),check).toArray()));
        button(h,31,check.ok()?Material.LIME_DYE:Material.GRAY_DYE,"menu-confirm-name",lore);
        p.openInventory(h.inventory);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void click(InventoryClickEvent event){
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder h))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player p)||p!=h.owner||event.getRawSlot()<0||event.getRawSlot()>=54)return;
        if(!p.hasPermission("chrdkclasses.use")||!plugin.ready(p)){p.closeInventory();return;}
        int slot=event.getRawSlot();
        if(slot==49){p.closeInventory();return;}
        if(slot==45){if(h.candidate==null)open(p);else openChoices(p);return;}
        if(h.candidate==null){for(int i=0;i<SLOTS.length;i++)if(slot==SLOTS[i])openInfo(p,PlayerClass.values()[i]);return;}
        if(slot==20||slot==22||slot==24){plugin.developmentGui().catalog(p,h.candidate.id(),(slot-20)/2);return;}
        if(slot!=31||h.consumed)return;
        h.consumed=true;
        ClassResult result=plugin.service().choose(p.getUniqueId(),h.candidate.id(),System.currentTimeMillis());
        plugin.messages().send(p,result.key(),ClassText.localise(plugin.messages(),result).toArray());
        if(result.ok()){
            plugin.gameplay().clear(p.getUniqueId());plugin.effects().apply(p);plugin.requestSave();
            plugin.progress().baseline(p.getUniqueId(),plugin.service().level(p.getUniqueId()));open(p);
        }else openInfo(p,h.candidate);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void drag(InventoryDragEvent event){if(event.getView().getTopInventory().getHolder() instanceof Holder)event.setCancelled(true);}
}
