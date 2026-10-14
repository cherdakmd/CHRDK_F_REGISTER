package ru.cherdakmd.chrdkauction;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import net.kyori.adventure.text.Component;
import java.util.*;

final class AuctionGui implements Listener {
    private final AuctionPlugin plugin;
    private final AuctionService service;
    enum Mode { MARKET, MINE, MAIL, CONFIRM }
    private static final class Holder implements InventoryHolder {
        final UUID owner; final Mode mode; final int page; final long[] ids=new long[45];
        Inventory inventory;
        Holder(UUID owner,Mode mode,int page) { this.owner=owner;this.mode=mode;this.page=page; }
        @Override public Inventory getInventory() { return inventory; }
    }
    AuctionGui(AuctionPlugin plugin,AuctionService service) { this.plugin=plugin;this.service=service; }
    void open(Player p,Mode mode,int page) {
        if (!service.ready(p)) return;
        var lots=service.store.lots().stream().filter(l->mode!=Mode.MINE || l.seller().equals(p.getUniqueId())).toList();
        var mail=service.store.mail(p.getUniqueId()); int size=mode==Mode.MAIL ? mail.size() : lots.size();
        page=Math.max(0,Math.min(page,Math.max(0,(size-1)/45)));
        String title=mode==Mode.MAIL ? "auction.mail-title" : mode==Mode.MINE ? "auction.mine-title" : "auction.title";
        Holder h=new Holder(p.getUniqueId(),mode,page);
        h.inventory=Bukkit.createInventory(h,54,plugin.messages().get(title));
        int from=page*45;
        for (int slot=0;slot<45 && from+slot<size;slot++) {
            ItemStack icon;
            if (mode==Mode.MAIL) {
                var parcel=mail.get(from+slot);h.ids[slot]=parcel.id();icon=ItemCodec.decode(parcel.item());
                append(icon,plugin.messages().get("auction.parcel-lore","id",parcel.id()));
            } else {
                var lot=lots.get(from+slot);h.ids[slot]=lot.id();icon=lotIcon(lot);
                append(icon,plugin.messages().get(lot.seller().equals(p.getUniqueId())?"auction.click-cancel":"auction.click-buy"));
            }
            h.inventory.setItem(slot,icon);
        }
        if (page>0) h.inventory.setItem(45,button(Material.ARROW,"auction.prev"));
        h.inventory.setItem(46,button(Material.CHEST,"auction.mine"));
        h.inventory.setItem(47,button(Material.ENDER_CHEST,"auction.mail"));
        h.inventory.setItem(48,button(Material.DIAMOND,"auction.money","balance",service.store.balance(p.getUniqueId())));
        h.inventory.setItem(49,button(Material.PAPER,"auction.help"));
        h.inventory.setItem(50,button(Material.BOOK,"auction.back"));
        if (from+45<size) h.inventory.setItem(53,button(Material.ARROW,"auction.next"));
        p.openInventory(h.inventory);
    }
    private ItemStack lotIcon(AuctionStore.Lot lot) {
        ItemStack item=ItemCodec.decode(lot.item());
        long hours=Math.max(0,(lot.expires()-System.currentTimeMillis()+3599999)/3600000);
        append(item,plugin.messages().get("auction.lot-lore","id",lot.id(),"seller",lot.name(),
                "price",lot.price(),"fee",lot.fee(),"hours",hours)); return item;
    }
    private void confirm(Player p,long id) {
        if (!service.ready(p)) return;
        var lot=service.store.lot(id);
        if (lot==null || lot.seller().equals(p.getUniqueId())) { service.tell(p,"auction.unavailable"); return; }
        Holder h=new Holder(p.getUniqueId(),Mode.CONFIRM,0);h.ids[0]=id;
        h.inventory=Bukkit.createInventory(h,27,plugin.messages().get("auction.confirm-title"));
        h.inventory.setItem(13,lotIcon(lot));
        h.inventory.setItem(11,button(Material.LIME_CONCRETE,"auction.confirm","price",lot.price()));
        h.inventory.setItem(15,button(Material.RED_CONCRETE,"auction.back"));p.openInventory(h.inventory);
    }
    private ItemStack button(Material material,String key,Object...params) {
        ItemStack item=new ItemStack(material);var meta=item.getItemMeta();
        meta.displayName(plugin.messages().get(key,params));item.setItemMeta(meta);return item;
    }
    private static void append(ItemStack item,Component line) {
        var meta=item.getItemMeta(); List<Component> lore=meta.lore()==null?new ArrayList<>():new ArrayList<>(meta.lore());
        lore.add(line);meta.lore(lore);item.setItemMeta(meta);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !h.owner.equals(p.getUniqueId())
                || e.getRawSlot()<0 || e.getRawSlot()>=h.inventory.getSize()) return;
        if (e.getClick()!=ClickType.LEFT && e.getClick()!=ClickType.RIGHT) return;
        int slot=e.getRawSlot();
        Bukkit.getScheduler().runTask(plugin,()->{
            if (!p.isOnline() || p.getOpenInventory().getTopInventory()!=h.inventory) return;
            if (h.mode==Mode.CONFIRM) {
                if (slot==11) { p.closeInventory();service.buy(p,h.ids[0]); }
                else if (slot==15) open(p,Mode.MARKET,0);
            } else if (slot<45 && h.ids[slot]>0) {
                long id=h.ids[slot];
                if (h.mode==Mode.MAIL) { p.closeInventory();service.claim(p,id); }
                else {
                    var lot=service.store.lot(id);
                    if (lot!=null && lot.seller().equals(p.getUniqueId())) { p.closeInventory();service.cancel(p,id); }
                    else confirm(p,id);
                }
            } else if (slot==45) open(p,h.mode,h.page-1);
            else if (slot==53 && h.inventory.getItem(53)!=null) open(p,h.mode,h.page+1);
            else if (slot==46) open(p,Mode.MINE,0);
            else if (slot==47) open(p,Mode.MAIL,0);
            else if (slot==48) { p.closeInventory();service.money(p); }
            else if (slot==49) service.tell(p,"auction.usage");
            else if (slot==50) open(p,Mode.MARKET,0);
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
    void closeAll() {
        for (Player p:Bukkit.getOnlinePlayers()) if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) p.closeInventory();
    }
}
