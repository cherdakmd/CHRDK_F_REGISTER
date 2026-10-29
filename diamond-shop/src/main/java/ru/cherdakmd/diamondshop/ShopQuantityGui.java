package ru.cherdakmd.diamondshop;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

/** Выбор штук и явное подтверждение. Котировка не меняется незаметно при клике. */
public final class ShopQuantityGui implements Listener {
    private final DiamondShopPlugin plugin;
    ShopQuantityGui(DiamondShopPlugin plugin){this.plugin=plugin;}
    static boolean owns(Inventory inventory){return inventory.getHolder() instanceof Holder;}

    void open(Player player,ShopConfig.ShopItem item,boolean buy,ShopCategory category,int page,
              ShopService service,int batch,long price){
        if (!buy && !item.sellEnabled()) { plugin.messages().send(player,"quantity.buy-only"); return; }
        var h=new Holder(player.getUniqueId(),item,buy,category,page,service,batch,price);
        if(!current(player,h)){plugin.messages().send(player,"trading.price-changed");plugin.gui().open(player,category,page);return;}
        h.units=buy?1:TradeAmounts.minimumSale(batch,price);
        h.inventory=Bukkit.createInventory(h,54,plugin.messages().get(buy?"quantity.buy-title":"quantity.sell-title"));
        render(player,h);player.openInventory(h.inventory);
    }
    private boolean current(Player player,Holder h){
        var s=plugin.shopService();
        return h.owner.equals(player.getUniqueId())&&player.getGameMode()==GameMode.SURVIVAL&&h.service==s
            &&(h.buy||h.item.sellEnabled())&&s.ready(player)&&s.findItem(h.item.material()).filter(h.item::equals).isPresent()
            &&h.batch==s.batchSize(h.item)&&h.price==(h.buy?s.askPrice(h.item):s.bidPrice(h.item))
            &&TradeAmounts.minimumSale(h.batch,h.price)>0;
    }
    private ItemStack named(Material material,String key,Object... values){
        var icon=new ItemStack(material);var meta=icon.getItemMeta();
        if(meta!=null){meta.displayName(plugin.messages().get(key,values));icon.setItemMeta(meta);}return icon;
    }
    private void render(Player player,Holder h){
        Inventory inv=h.inventory;inv.clear();
        Material material=Material.matchMaterial(h.item.material());
        var icon=new ItemStack(material==null?Material.BARRIER:material);
        icon.setAmount(Math.min(h.units,icon.getMaxStackSize()));inv.setItem(4,icon);
        int[] slots={10,11,12,14,15,16};int[] changes={-64,-8,-1,1,8,64};
        for(int i=0;i<slots.length;i++)inv.setItem(slots[i],named(changes[i]<0?Material.RED_DYE:Material.LIME_DYE,"quantity.change","amount",(changes[i]>0?"+":"")+changes[i]));
        int[] presets={1,8,16,32,64};for(int i=0;i<presets.length;i++)inv.setItem(28+i,named(Material.PAPER,"quantity.preset","amount",presets[i]));
        int minimum=TradeAmounts.minimumSale(h.batch,h.price);
        if(!h.buy)inv.setItem(33,named(Material.GOLD_NUGGET,"quantity.minimum","minimum",minimum));
        long money=TradeAmounts.quote(h.batch,h.price,h.units,h.buy);
        var quote=named(Material.PAPER,"quantity.total","amount",h.units,"money",money<0?"—":money);
        ItemMeta meta=quote.getItemMeta();
        if(meta!=null){meta.lore(plugin.messages().getList(h.buy?"quantity.buy-details":"quantity.details","batch",h.batch,"price",h.price,"minimum",minimum,"have",material==null?0:ShopService.countItems(player.getInventory(),material),"balance",h.service.balance(player),"physical",h.service.physical(player)));quote.setItemMeta(meta);}inv.setItem(22,quote);
        inv.setItem(40,named(money>0?Material.LIME_DYE:Material.BARRIER,money>0?"quantity.confirm":"quantity.unavailable"));
        inv.setItem(45,named(Material.ARROW,"quantity.back"));
        if(!h.buy)inv.setItem(49,named(Material.CHEST,"quantity.maximum"));
    }
    @EventHandler(ignoreCancelled=true)
    public void click(InventoryClickEvent event){
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder h))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player)||!h.session.editable(player.getUniqueId())
            ||event.getClickedInventory()!=h.inventory||(!event.getClick().isLeftClick()&&!event.getClick().isRightClick()))return;
        if(!current(player,h)){plugin.messages().send(player,"trading.price-changed");navigate(player,h);return;}
        int slot=event.getRawSlot();
        if(slot==45){navigate(player,h);return;}
        int delta=switch(slot){case 10->-64;case 11->-8;case 12->-1;case 14->1;case 15->8;case 16->64;default->0;};
        if(delta!=0){h.units=TradeAmounts.adjust(h.units,delta);render(player,h);return;}
        int preset=switch(slot){case 28->1;case 29->8;case 30->16;case 31->32;case 32->64;case 33->h.buy?0:TradeAmounts.minimumSale(h.batch,h.price);default->0;};
        if(preset>0){h.units=preset;render(player,h);return;}
        if(slot==49&&!h.buy){int max=h.service.maximumSale(player,h.item,h.batch,h.price);if(max>0)h.units=max;else plugin.messages().send(player,"quantity.sellall-none");render(player,h);return;}
        if(slot!=40)return;
        long money=TradeAmounts.quote(h.batch,h.price,h.units,h.buy);
        if(money<=0){plugin.messages().send(player,money==0?"quantity.too-small":"trading.invalid-number","material",h.item.material().toLowerCase(Locale.ROOT),"value",h.units);return;}
        if(!h.session.submit(player.getUniqueId()))return;int units=h.units;
        plugin.getServer().getScheduler().runTask(plugin,()->{
            if(!active(player,h))return;
            if(!current(player,h)){plugin.messages().send(player,"trading.price-changed");plugin.gui().open(player,h.category,h.page);return;}
            var result=h.service.quoted(player,h.item,units,h.buy,h.batch,h.price);
            if(result.success()){
                if(h.buy)plugin.messages().send(player,"trading.payment-detail","account",result.bankPaid(),"physical",result.physicalPaid(),"change",result.change());
                plugin.messages().send(player,h.buy?"trading.buy-done":"trading.sell-done","material",h.item.material().toLowerCase(Locale.ROOT),"units",result.units(),"cost",result.money(),"income",result.money(),"balance",h.service.balance(player),"physical",h.service.physical(player));
            }else plugin.messages().send(player,result.errorKey(),"material",h.item.material().toLowerCase(Locale.ROOT),"cost",result.money(),"value",units,"units",units,"balance",h.service.balance(player),"limit",h.service.config().dailyTurnoverLimit());
            if(active(player,h))plugin.gui().open(player,h.category,h.page);
        });
    }
    private boolean active(Player p,Holder h){return plugin.isEnabled()&&p.isOnline()&&h.session.pending(p.getUniqueId())&&p.getOpenInventory().getTopInventory()==h.inventory;}
    private void navigate(Player p,Holder h){if(!h.session.submit(p.getUniqueId()))return;plugin.getServer().getScheduler().runTask(plugin,()->{if(active(p,h))plugin.gui().open(p,h.category,h.page);});}
    @EventHandler public void close(InventoryCloseEvent e){if(e.getInventory().getHolder() instanceof Holder h)h.session.close();}
    @EventHandler(ignoreCancelled=true)public void drag(InventoryDragEvent e){if(owns(e.getView().getTopInventory()))e.setCancelled(true);}
    private static final class Holder implements InventoryHolder {
        final UUID owner;final ShopConfig.ShopItem item;final boolean buy;final ShopCategory category;final int page;
        final ShopService service;final int batch;final long price;final QuantitySession session;int units;Inventory inventory;
        Holder(UUID owner,ShopConfig.ShopItem item,boolean buy,ShopCategory category,int page,ShopService service,int batch,long price){this.owner=owner;this.session=new QuantitySession(owner);this.item=item;this.buy=buy;this.category=category;this.page=page;this.service=service;this.batch=batch;this.price=price;}
        @Override public @NotNull Inventory getInventory(){return inventory;}
    }
}
