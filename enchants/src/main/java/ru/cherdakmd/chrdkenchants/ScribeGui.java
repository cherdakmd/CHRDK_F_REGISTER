package ru.cherdakmd.chrdkenchants;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-only selection and confirmation. All mutations go through EnchantWorkshop. */
final class ScribeGui implements Listener {
    private static final int PAGE_SIZE=45;
    private final EnchantsPlugin plugin;
    ScribeGui(EnchantsPlugin plugin) { this.plugin=plugin; }
    private record Entry(String action,String id,int level,String label) { }
    private static final class Holder implements InventoryHolder {
        final UUID owner, world; final long generation; final int slot,page; final ItemStack book;
        final List<Entry> entries; final Entry selected; Inventory inventory;
        Holder(UUID owner,UUID world,long generation,int slot,int page,ItemStack book,List<Entry> entries,Entry selected) {
            this.owner=owner;this.world=world;this.generation=generation;this.slot=slot;this.page=page;this.book=book.clone();
            this.entries=List.copyOf(entries);this.selected=selected;
        }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }
    void open(Player player) { plugin.bookWorkshop().open(player,BookWorkshopGui.Mode.SCRIBE); }
    private void openLegacy(Player player) {
        if (!plugin.workshop().allowed(player,"split")) { plugin.messages().send(player,"workshop-rejected"); return; }
        ItemStack book=player.getInventory().getItemInMainHand();
        if (book.getType()!=Material.ENCHANTED_BOOK || book.getAmount()!=1) { plugin.messages().send(player,"scribe-book-required");return; }
        var custom=plugin.items().validated(book);
        if (custom==null || !custom.isEmpty() && !WorkshopPolicy.valid(custom)) { plugin.messages().send(player,"workshop-rejected");return; }
        var entries=new ArrayList<Entry>();
        custom.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(e -> entries.add(new Entry("split",e.getKey(),e.getValue(),
                plugin.messages().plain("en."+e.getKey()+".name"))));
        if (book.getItemMeta() instanceof EnchantmentStorageMeta meta) meta.getStoredEnchants().entrySet().stream()
                .sorted(Comparator.comparing(e->e.getKey().getKey().toString()))
                .forEach(e->entries.add(new Entry("splitvanilla",e.getKey().getKey().toString(),e.getValue(),e.getKey().getKey().toString())));
        if(entries.size()<2){plugin.messages().send(player,"scribe-book-required");return;}
        show(player,new Holder(player.getUniqueId(),player.getWorld().getUID(),plugin.sourceGeneration(),player.getInventory().getHeldItemSlot(),0,book,entries,null));
    }
    private void show(Player player,Holder holder) {
        Inventory inventory=Bukkit.createInventory(holder,54,plugin.messages().get(holder.selected==null?"scribe-title":"scribe-confirm-title"));
        holder.inventory=inventory;
        if(holder.selected==null) {
            int start=holder.page*PAGE_SIZE;
            for(int i=0;i<PAGE_SIZE && start+i<holder.entries.size();i++) {
                Entry e=holder.entries.get(start+i);
                inventory.setItem(i,icon(Material.ENCHANTED_BOOK,Component.text(e.label()+" · "+e.level()),
                        plugin.messages().get("scribe-choice")));
            }
            if(holder.page>0)inventory.setItem(45,icon(Material.ARROW,plugin.messages().get("scribe-prev")));
            if(start+PAGE_SIZE<holder.entries.size())inventory.setItem(53,icon(Material.ARROW,plugin.messages().get("scribe-next")));
            inventory.setItem(49,icon(Material.PAPER,plugin.messages().get("scribe-cost"),plugin.messages().get("scribe-explain")));
        } else {
            inventory.setItem(22,icon(Material.ENCHANTED_BOOK,Component.text(holder.selected.label()+" · "+holder.selected.level()),plugin.messages().get("scribe-explain")));
            inventory.setItem(30,icon(Material.LIME_DYE,plugin.messages().get("scribe-confirm"),plugin.messages().get("scribe-cost")));
            inventory.setItem(32,icon(Material.BARRIER,plugin.messages().get("scribe-back")));
        }
        player.openInventory(inventory);
    }
    private ItemStack icon(Material material,Component name,Component... lore) {
        var stack=new ItemStack(material);var meta=stack.getItemMeta();meta.displayName(name);meta.lore(List.of(lore));stack.setItemMeta(meta);return stack;
    }
    private boolean valid(Player player,Holder holder) {
        return player.isOnline() && !player.isDead() && plugin.isEnabled() && plugin.coreApi()!=null
                && plugin.coreApi().isAuthenticated(player) && player.hasPermission("chrdkenchants.use")
                && holder.owner.equals(player.getUniqueId()) && holder.world.equals(player.getWorld().getUID()) && holder.generation==plugin.sourceGeneration()
                && player.getInventory().getHeldItemSlot()==holder.slot
                && Objects.equals(holder.book,player.getInventory().getItemInMainHand());
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder))return;
        boolean cancelled=event.isCancelled(); event.setCancelled(true);
        if(cancelled || !(event.getWhoClicked() instanceof Player player) || !valid(player,holder)
                || (event.getClick()!=ClickType.LEFT && event.getClick()!=ClickType.RIGHT)
                || event.getRawSlot()<0 || event.getRawSlot()>=54)return;
        int slot=event.getRawSlot();
        if(holder.selected==null) {
            int index=holder.page*PAGE_SIZE+slot;
            if(slot<PAGE_SIZE && index<holder.entries.size()) { next(player,holder,holder.page,holder.entries.get(index));return; }
            if(slot==45 && holder.page>0){next(player,holder,holder.page-1,null);return;}
            if(slot==53 && (holder.page+1)*PAGE_SIZE<holder.entries.size())next(player,holder,holder.page+1,null);
        } else if(slot==32) next(player,holder,holder.page,null);
        else if(slot==30) Bukkit.getScheduler().runTask(plugin,()->{
            if(!valid(player,holder) || player.getOpenInventory().getTopInventory().getHolder()!=holder)return;
            player.closeInventory();
            // InventoryClickEvent has completed; the existing workshop rebuilds and rechecks the full quote.
            if(valid(player,holder))plugin.workshop().confirmedGui(player,holder.selected.action(),holder.selected.id());
        });
    }
    private void next(Player player,Holder holder,int page,Entry selected) {
        Bukkit.getScheduler().runTask(plugin,()->{
            if(valid(player,holder) && player.getOpenInventory().getTopInventory().getHolder()==holder)
                show(player,new Holder(holder.owner,holder.world,holder.generation,holder.slot,page,holder.book,holder.entries,selected));
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof Holder)event.setCancelled(true);
    }
    void closeAll() { for(Player player:Bukkit.getOnlinePlayers()) if(player.getOpenInventory().getTopInventory().getHolder() instanceof Holder)player.closeInventory(); }
}
