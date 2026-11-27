package ru.cherdakmd.chrdkenchants;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/** Real input slots. Icons are not items owned by the player and cannot be taken. */
final class BookWorkshopGui implements Listener {
    enum Mode { SCRIBE, CLEANSE, COMBINE }
    private static final int FIRST=10, SECOND=16, START=27, PAGE=18;
    private final EnchantsPlugin plugin;
    BookWorkshopGui(EnchantsPlugin plugin) { this.plugin=plugin; }
    private record Choice(String action, String id, int level, String label) { }
    private static final class Holder implements InventoryHolder {
        final UUID owner, world; final long generation; final Mode mode;
        Inventory inv; List<Choice> choices=List.of(); Choice selected; int page;
        ItemStack input1,input2; ItemStack[] storage; boolean armed;
        Holder(Player p,long gen,Mode mode) { owner=p.getUniqueId();world=p.getWorld().getUID();generation=gen;this.mode=mode; }
        @Override public @NotNull Inventory getInventory() { return inv; }
    }
    void open(Player player,Mode mode) {
        String action=mode==Mode.SCRIBE?"split":mode==Mode.CLEANSE?"cleanse":"combine";
        if (!plugin.workshop().allowed(player,action)) { plugin.messages().send(player,"workshop-rejected"); return; }
        Holder h=new Holder(player,plugin.sourceGeneration(),mode);
        h.inv=Bukkit.createInventory(h,54,plugin.messages().get(switch(mode) {
            case SCRIBE -> "slots-scribe-title"; case CLEANSE -> "slots-cleanse-title"; case COMBINE -> "slots-combine-title";
        }));
        render(h); player.openInventory(h.inv);
    }
    private ItemStack icon(String visual,Material m,Component title,Component... lore) {
        var result=new ItemStack(m); var meta=result.getItemMeta(); meta.displayName(title); meta.lore(List.of(lore));
        BookWorkshopVisuals.apply(meta,visual,plugin.getConfig().getBoolean("textures.workshop-menu",false));
        result.setItemMeta(meta); return result;
    }
    private static ItemStack clone(ItemStack s) { return s==null?null:s.clone(); }
    private static ItemStack[] copy(ItemStack[] items) { return Arrays.stream(items).map(BookWorkshopGui::clone).toArray(ItemStack[]::new); }
    private boolean valid(Player p,Holder h) {
        return p.isOnline() && !p.isDead() && plugin.isEnabled() && h.owner.equals(p.getUniqueId())
                && h.world.equals(p.getWorld().getUID()) && h.generation==plugin.sourceGeneration()
                && p.getOpenInventory().getTopInventory().getHolder()==h
                && plugin.workshop().permitted(p,h.mode==Mode.SCRIBE?"split":h.mode==Mode.CLEANSE?"cleanse":"combine");
    }
    private void render(Holder h) {
        Inventory inv=h.inv;
        for(int i=0;i<54;i++) if(i!=FIRST && i!=SECOND) inv.setItem(i,null);
        inv.setItem(4,icon("instructions",Material.PAPER,plugin.messages().get("slots-instructions"),plugin.messages().get(h.mode==Mode.COMBINE?"slots-two":"slots-one")));
        inv.setItem(1,icon("left",Material.HOPPER,plugin.messages().get("slots-left")));
        if(h.mode==Mode.COMBINE) inv.setItem(7,icon("right",Material.HOPPER,plugin.messages().get("slots-right")));
        inv.setItem(13,icon(BookWorkshopVisuals.cost(h.mode),Material.ANVIL,plugin.messages().get(h.mode==Mode.SCRIBE?"slots-scribe-cost":h.mode==Mode.CLEANSE?"hub-cleanse-cost":"hub-combine-cost")));
        if(h.mode!=Mode.COMBINE) {
            ItemStack item=inv.getItem(FIRST); var entries=new ArrayList<Choice>();
            var data=plugin.items().validated(item);
            if(item!=null && item.getAmount()==1 && data!=null) {
                data.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{
                    var def=EnchantRegistry.byId(e.getKey());
                    if(def!=null && (h.mode==Mode.SCRIBE || def.curse()))
                        entries.add(new Choice(h.mode==Mode.SCRIBE?"split":"cleanse",e.getKey(),e.getValue(),plugin.messages().plain("en."+e.getKey()+".name")));
                });
                if(h.mode==Mode.SCRIBE && item.getType()==Material.ENCHANTED_BOOK && item.getItemMeta() instanceof EnchantmentStorageMeta meta)
                    meta.getStoredEnchants().entrySet().stream().sorted(Comparator.comparing(e->e.getKey().getKey().toString()))
                            .forEach(e->entries.add(new Choice("splitvanilla",e.getKey().getKey().toString(),e.getValue(),e.getKey().getKey().toString())));
            }
            h.choices=List.copyOf(entries); h.page=Math.max(0,Math.min(h.page,Math.max(0,(entries.size()-1)/PAGE)));
            for(int i=0;i<PAGE && h.page*PAGE+i<entries.size();i++) {
                Choice c=entries.get(h.page*PAGE+i);
                inv.setItem(START+i,icon(c.equals(h.selected)?"selected":"choice",c.equals(h.selected)?Material.ENCHANTED_BOOK:Material.BOOK,Component.text(c.label()+" · "+c.level()),plugin.messages().get("slots-choose")));
            }
            if(h.page>0) inv.setItem(45,icon("prev",Material.ARROW,plugin.messages().get("scribe-prev")));
            if((h.page+1)*PAGE<entries.size()) inv.setItem(53,icon("next",Material.ARROW,plugin.messages().get("scribe-next")));
        } else inv.setItem(22,icon("combine",Material.BOOKSHELF,plugin.messages().get("hub-combine-effect"),plugin.messages().get("hub-combine-requirements")));
        inv.setItem(49,icon(h.armed?"confirm":"prepare",h.armed?Material.LIME_DYE:Material.YELLOW_DYE,plugin.messages().get(h.armed?"slots-confirm":"slots-prepare")));
        inv.setItem(50,icon("close",Material.BARRIER,plugin.messages().get("hub-close")));
    }
    private void reset(Holder h) { h.armed=false;h.input1=null;h.input2=null;h.storage=null; }
    static boolean safeAction(InventoryAction action) {
        return switch(action) {
            case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME, PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR, NOTHING -> true;
            default -> false;
        };
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void click(InventoryClickEvent e) {
        if(!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        if(e.isCancelled()) return;
        if(!(e.getWhoClicked() instanceof Player p) || !valid(p,h)) { e.setCancelled(true); return; }
        int slot=e.getRawSlot();
        if(slot>=54 || slot==FIRST || slot==SECOND && h.mode==Mode.COMBINE) {
            if((e.getClick()!=ClickType.LEFT && e.getClick()!=ClickType.RIGHT) || !safeAction(e.getAction())) { e.setCancelled(true); return; }
            reset(h); if(slot==FIRST){h.selected=null;h.page=0;}
            Bukkit.getScheduler().runTask(plugin,()->{if(valid(p,h))render(h);});
            return;
        }
        e.setCancelled(true);
        if(e.getClick()!=ClickType.LEFT && e.getClick()!=ClickType.RIGHT) return;
        if(slot==50) { Bukkit.getScheduler().runTask(plugin,()->{if(valid(p,h))p.closeInventory();}); return; }
        if(slot>=START && slot<START+PAGE && h.mode!=Mode.COMBINE) {
            int index=h.page*PAGE+slot-START;
            if(index<h.choices.size()){h.selected=h.choices.get(index);reset(h);render(h);} return;
        }
        if(slot==45 && h.page>0){h.page--;reset(h);render(h);return;}
        if(slot==53 && (h.page+1)*PAGE<h.choices.size()){h.page++;reset(h);render(h);return;}
        if(slot!=49) return;
        if(p.getItemOnCursor()!=null && !p.getItemOnCursor().getType().isAir()) { plugin.messages().send(p,"slots-cursor");return; }
        if(h.mode!=Mode.COMBINE && (h.selected==null || !h.choices.contains(h.selected))) { plugin.messages().send(p,"slots-select");return; }
        if(!h.armed) {
            if(h.inv.getItem(FIRST)==null || h.mode==Mode.COMBINE && h.inv.getItem(SECOND)==null) {plugin.messages().send(p,"slots-input");return;}
            h.input1=clone(h.inv.getItem(FIRST));h.input2=clone(h.inv.getItem(SECOND));h.storage=copy(p.getInventory().getStorageContents());h.armed=true;render(h);return;
        }
        if(!Objects.equals(h.input1,h.inv.getItem(FIRST)) || !Objects.equals(h.input2,h.inv.getItem(SECOND))
                || !Arrays.equals(h.storage,p.getInventory().getStorageContents())) {reset(h);render(h);plugin.messages().send(p,"slots-changed");return;}
        String action=h.mode==Mode.COMBINE?"combine":h.selected.action(); String id=h.selected==null?null:h.selected.id();
        reset(h);
        plugin.workshop().applyGui(p,action,id,h.inv,FIRST,SECOND);
        h.selected=null; h.page=0; render(h);
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void drag(InventoryDragEvent e) { if(e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true); }
    private void returnInputs(Player p,Holder h) {
        for(int slot:new int[]{FIRST,SECOND}) {
            ItemStack item=h.inv.getItem(slot); h.inv.setItem(slot,null);
            if(item!=null && !item.getType().isAir()) for(ItemStack left:p.getInventory().addItem(item).values()) p.getWorld().dropItemNaturally(p.getLocation(),left);
        }
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void close(InventoryCloseEvent e) { if(e.getInventory().getHolder() instanceof Holder h && e.getPlayer() instanceof Player p && h.owner.equals(p.getUniqueId())) returnInputs(p,h); }
    @EventHandler(priority=EventPriority.LOWEST)
    public void death(PlayerDeathEvent e) {
        Player p=e.getEntity(); if(!(p.getOpenInventory().getTopInventory().getHolder() instanceof Holder h))return;
        if(!e.getKeepInventory()) {
            for(int slot:new int[]{FIRST,SECOND}) { ItemStack item=h.inv.getItem(slot);h.inv.setItem(slot,null);if(item!=null && !item.getType().isAir())e.getDrops().add(item); }
        }
        p.closeInventory();
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void quit(PlayerQuitEvent e) { if(e.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof Holder)e.getPlayer().closeInventory(); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void teleport(PlayerTeleportEvent e) { if(e.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof Holder)e.getPlayer().closeInventory(); }
    void closeAll() { for(Player p:Bukkit.getOnlinePlayers()) if(p.getOpenInventory().getTopInventory().getHolder() instanceof Holder)p.closeInventory(); }
}
