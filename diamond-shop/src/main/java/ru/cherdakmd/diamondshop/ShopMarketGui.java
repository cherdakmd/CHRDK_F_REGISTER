package ru.cherdakmd.diamondshop;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.cherdakmd.chrdkregister.api.ShopBooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Новая витрина: главная с разделами и отдельный каталог товаров.
 * Все суммы берутся из minor-экономики ShopService; GUI не рассчитывает цены. */
public final class ShopMarketGui implements Listener {
    private static final int HOME_ALL = 45;
    private static final int HOME_SELL_ALL = 48;
    private static final int HOME_GUIDE = 50;
    private static final int HOME_PROMO = 31;
    private static final int[] HOME_SECTIONS = {10,11,12,13,14,15,16,21,23};

    private final DiamondShopPlugin plugin;

    public ShopMarketGui(DiamondShopPlugin plugin) { this.plugin = plugin; }

    /** /shop всегда открывает новую главную. */
    public void open(Player player, int ignoredPage) {
        if (!plugin.shopService().ready(player)) { plugin.messages().send(player,"trading.unavailable"); return; }
        ShopService service=plugin.shopService();
        Holder holder=new Holder(null,0,service,true);
        Inventory inventory=Bukkit.createInventory(holder,ShopLayout.SIZE,plugin.messages().get("market.home.title"));
        holder.bind(inventory);
        fill(inventory);
        List<ShopCategory> sections=ShopCatalog.tabs(service.config().items()).stream().filter(c->c!=ShopCategory.ALL).toList();
        for(int i=0;i<sections.size()&&i<HOME_SECTIONS.length;i++) {
            ShopCategory category=sections.get(i);
            inventory.setItem(HOME_SECTIONS[i],sectionCard(category,ShopCatalog.filter(service.config().items(),category).size()));
        }
        inventory.setItem(HOME_ALL,button(Material.CHEST,"market.home.all",List.of(plugin.messages().get("market.home.all-lore"))));
        inventory.setItem(ShopLayout.SLOT_FORGE,button(Material.ANVIL,"market.home.forge",List.of(plugin.messages().get("market.home.forge-lore"))));
        inventory.setItem(HOME_SELL_ALL,button(Material.HOPPER,"market.home.sellall",List.of(plugin.messages().get("market.home.sellall-lore"))));
        inventory.setItem(ShopLayout.SLOT_BALANCE,balanceIcon(player));
        inventory.setItem(HOME_GUIDE,button(Material.KNOWLEDGE_BOOK,"market.home.guide",List.of(
                plugin.messages().get("market.home.guide-click"),plugin.messages().get("market.home.guide-search"),plugin.messages().get("market.home.guide-money"))));
        inventory.setItem(ShopLayout.SLOT_CLOSE,button(Material.BARRIER,"trading.gui.close-name",List.of()));
        ShopPromoService promos=service.promos();
        if(promos!=null&&promos.enabled()) {
            long now=System.currentTimeMillis();
            inventory.setItem(HOME_PROMO,button(Material.GOLD_INGOT,"market.home.promo",List.of(
                    plugin.messages().get("market.home.promo-type","type",promos.typeLabel(now)),
                    plugin.messages().get("market.home.promo-items","items",promos.featuredLabel(now)),
                    plugin.messages().get("market.home.promo-left","left",ShopMoney.text(promos.remaining(player.getUniqueId(),now))))));
        }
        if(player.hasPermission("chrdkshop.admin")) inventory.setItem(ShopAdminGui.SHOP_EDIT_SLOT,button(Material.COMMAND_BLOCK,"trading.gui.admin-edit",List.of()));
        player.openInventory(inventory);
    }

    /** Раздел товаров. Параметры количества по-прежнему возвращают сюда. */
    public void open(Player player,ShopCategory category,int page) {
        if (!plugin.shopService().ready(player)) { plugin.messages().send(player,"trading.unavailable"); return; }
        ShopService service=plugin.shopService(); List<ShopConfig.ShopItem> all=service.config().items();
        List<ShopCategory> tabs=ShopCatalog.tabs(all);
        ShopCategory current=category==null||!tabs.contains(category)?ShopCategory.ALL:category;
        List<ShopConfig.ShopItem> view=ShopCatalog.filter(all,current);
        int pages=ShopLayout.pages(view.size()),safePage=ShopLayout.clampPage(page,view.size());
        Holder holder=new Holder(current,safePage,service,false);
        Component title=plugin.messages().get("market.category.title","category",plugin.messages().mini(current.messageKey()),"page",safePage+1,"pages",pages);
        Inventory inventory=Bukkit.createInventory(holder,ShopLayout.SIZE,title); holder.bind(inventory);
        for(int i=0;i<tabs.size()&&i<ShopLayout.MAX_TABS;i++) {
            ShopCategory tab=tabs.get(i); inventory.setItem(i,tabIcon(tab,tab==current,ShopCatalog.filter(all,tab).size()));
        }
        inventory.setItem(ShopLayout.SLOT_CLOSE,button(Material.BARRIER,"trading.gui.close-name",List.of()));
        inventory.setItem(ShopLayout.SLOT_FORGE,button(Material.ANVIL,"market.home.forge",List.of()));
        int from=safePage*ShopLayout.ITEMS_PER_PAGE;
        for(int i=0;i<ShopLayout.ITEMS_PER_PAGE&&from+i<view.size();i++) {
            ShopConfig.ShopItem item=view.get(from+i);
            Offer offer=new Offer(item.material(),service.batchSize(item),service.askPrice(item),service.bidPrice(item));
            int slot=ShopLayout.FIRST_ITEM_SLOT+i; holder.offers.put(slot,offer); inventory.setItem(slot,itemCard(item,offer));
        }
        if(view.isEmpty()) inventory.setItem(ShopLayout.FIRST_ITEM_SLOT+13,button(Material.BARRIER,"trading.gui.empty",List.of()));
        inventory.setItem(ShopLayout.SLOT_PREV,button(Material.ARROW,safePage>0?"market.category.previous":"market.home.back",List.of()));
        inventory.setItem(ShopLayout.SLOT_BALANCE,balanceIcon(player));
        if(safePage<pages-1) inventory.setItem(ShopLayout.SLOT_NEXT,button(Material.ARROW,"trading.gui.next-name",List.of()));
        if(player.hasPermission("chrdkshop.admin")) inventory.setItem(ShopAdminGui.SHOP_EDIT_SLOT,button(Material.COMMAND_BLOCK,"trading.gui.admin-edit",List.of()));
        player.openInventory(inventory);
    }

    private ItemStack sectionCard(ShopCategory category,int count) {
        List<Component> lore=new ArrayList<>();
        lore.add(plugin.messages().get("market.section."+category.id()));
        lore.add(Component.empty());
        lore.add(plugin.messages().get("market.section.count","count",count));
        lore.add(plugin.messages().get("market.section.open"));
        return button(material(category.icon(),Material.CHEST),category.messageKey(),lore);
    }

    private ItemStack tabIcon(ShopCategory category,boolean selected,int count) {
        List<Component> lore=new ArrayList<>(); lore.add(plugin.messages().get("trading.gui.tab-lore","count",count));
        if(selected) lore.add(plugin.messages().get("trading.gui.tab-current"));
        ItemStack icon=button(material(category.icon(),Material.PAPER),category.messageKey(),lore);
        if(selected) { var meta=icon.getItemMeta(); if(meta!=null) {meta.setEnchantmentGlintOverride(true);icon.setItemMeta(meta);} }
        return icon;
    }

    private ItemStack itemCard(ShopConfig.ShopItem item,Offer offer) {
        var spec=item.spec(); ItemStack icon;
        if(spec!=null) { icon=ShopBooks.create(spec,ShopBookInfo.displayName(spec)); if(icon==null)icon=new ItemStack(Material.BARRIER); }
        else icon=new ItemStack(material(item.material(),Material.BARRIER));
        var meta=icon.getItemMeta(); if(meta==null)return icon;
        ShopService service=plugin.shopService(); long now=System.currentTimeMillis();
        long stock=service.store().stockOf(item,now,service.config().stockRecoveryPerHour());
        List<Component> lore=new ArrayList<>();
        Component description=ShopBookInfo.description(spec); if(description!=null)lore.add(description);
        ShopPromoService promos=service.promos();
        if(promos!=null&&promos.enabled()&&promos.plan(now).featured().contains(item.material()))lore.add(plugin.messages().get("trading.gui.promo-tag"));
        lore.addAll(plugin.messages().getList(item.sellEnabled()?"market.item.lore":"market.item.buy-only-lore",
                "bid",ShopMoney.text(offer.bid()),"ask",ShopMoney.text(offer.ask()),"batch",offer.batch(),
                "stock",stock,"ref",item.refStock(),"trend",PriceEngine.trend(service.historyOf(item)),
                "history",PriceEngine.sparkline(service.historyOf(item)),"id",item.material().toLowerCase(Locale.ROOT)));
        meta.lore(lore); icon.setItemMeta(meta); return icon;
    }

    private ItemStack balanceIcon(Player player) {
        ShopService service=plugin.shopService();
        return button(Material.DIAMOND,"market.balance.name",List.of(
                plugin.messages().get("market.balance.wallet","balance",service.balanceText(player)),
                plugin.messages().get("market.balance.physical","physical",service.physical(player)),
                plugin.messages().get("market.balance.minor"),
                plugin.messages().get("market.balance.turnover","turnover",PriceEngine.round(service.store().turnover()))));
    }

    private ItemStack button(Material material,String nameKey,List<Component> lore) {
        ItemStack icon=new ItemStack(material); var meta=icon.getItemMeta();
        if(meta!=null) {meta.displayName(plugin.messages().get(nameKey));if(!lore.isEmpty())meta.lore(lore);icon.setItemMeta(meta);}
        return icon;
    }
    private static Material material(String name,Material fallback) { Material value=Material.matchMaterial(name);return value==null||!value.isItem()?fallback:value; }
    private static void fill(Inventory inventory) {
        ItemStack pane=new ItemStack(Material.GRAY_STAINED_GLASS_PANE);var meta=pane.getItemMeta();if(meta!=null){meta.displayName(Component.empty());meta.setHideTooltip(true);pane.setItemMeta(meta);}
        for(int slot=0;slot<inventory.getSize();slot++)inventory.setItem(slot,pane);
    }

    @EventHandler(ignoreCancelled=true)
    public void onInventoryClick(InventoryClickEvent event) {
        if(!(event.getInventory().getHolder() instanceof Holder holder))return;
        if(event.getClickedInventory()!=event.getView().getTopInventory()) {
            if(event.getClick().isShiftClick()||event.getClick()==ClickType.DOUBLE_CLICK||event.getClick()==ClickType.NUMBER_KEY)event.setCancelled(true);
            return;
        }
        event.setCancelled(true); if(!(event.getWhoClicked() instanceof Player player))return;
        int slot=event.getRawSlot();
        if(slot==ShopLayout.SLOT_CLOSE){player.closeInventory();return;}
        if(slot==ShopAdminGui.SHOP_EDIT_SLOT){if(player.hasPermission("chrdkshop.admin")&&plugin.shopAdminGui()!=null)plugin.shopAdminGui().open(player,0);return;}
        if(slot==ShopLayout.SLOT_FORGE){plugin.forgeShopGui().open(player);return;}
        if(holder.home) {
            List<ShopCategory> sections=ShopCatalog.tabs(plugin.shopService().config().items()).stream().filter(c->c!=ShopCategory.ALL).toList();
            for(int i=0;i<sections.size()&&i<HOME_SECTIONS.length;i++)if(slot==HOME_SECTIONS[i]){reopen(player,sections.get(i),0);return;}
            if(slot==HOME_ALL){reopen(player,ShopCategory.ALL,0);return;}
            if(slot==HOME_SELL_ALL){player.closeInventory();player.performCommand("shop sellall");return;}
            return;
        }
        if(ShopLayout.isTabSlot(slot)) {
            List<ShopCategory> tabs=ShopCatalog.tabs(plugin.shopService().config().items());
            if(slot<tabs.size())reopen(player,tabs.get(slot),0);return;
        }
        if(slot==ShopLayout.SLOT_PREV){if(holder.page>0)reopen(player,holder.category,holder.page-1);else reopenHome(player);return;}
        if(slot==ShopLayout.SLOT_NEXT){reopen(player,holder.category,holder.page+1);return;}
        int index=ShopLayout.itemIndex(holder.page,slot);if(index<0)return;
        List<ShopConfig.ShopItem> view=ShopCatalog.filter(plugin.shopService().config().items(),holder.category);if(index>=view.size())return;
        ShopConfig.ShopItem item=view.get(index);Offer offer=holder.offers.get(slot);ShopService service=plugin.shopService();
        if(holder.service!=service||offer==null||!offer.id().equals(item.material())||offer.batch()!=service.batchSize(item)
                ||offer.ask()!=service.askPrice(item)||offer.bid()!=service.bidPrice(item)) {
            plugin.messages().send(player,"trading.price-changed");reopen(player,holder.category,holder.page);return;
        }
        ClickType click=event.getClick();if(click==ClickType.NUMBER_KEY||click==ClickType.DROP||click==ClickType.CONTROL_DROP||(!click.isLeftClick()&&!click.isRightClick()))return;
        boolean buy=click.isLeftClick();if(!buy&&!item.sellEnabled()){plugin.messages().send(player,"quantity.buy-only");return;}
        Inventory expected=player.getOpenInventory().getTopInventory();
        plugin.getServer().getScheduler().runTask(plugin,()->{
            if(plugin.isEnabled()&&player.isOnline()&&player.getOpenInventory().getTopInventory()==expected)
                plugin.quantities().open(player,item,buy,holder.category,holder.page,holder.service,offer.batch(),buy?offer.ask():offer.bid());
        });
    }

    @EventHandler(ignoreCancelled=true) public void onInventoryDrag(InventoryDragEvent event) {if(event.getInventory().getHolder() instanceof Holder)event.setCancelled(true);}
    private void reopen(Player player,ShopCategory category,int page) {Inventory expected=player.getOpenInventory().getTopInventory();plugin.getServer().getScheduler().runTask(plugin,()->{if(plugin.isEnabled()&&player.isOnline()&&player.getOpenInventory().getTopInventory()==expected)open(player,category,page);});}
    private void reopenHome(Player player) {Inventory expected=player.getOpenInventory().getTopInventory();plugin.getServer().getScheduler().runTask(plugin,()->{if(plugin.isEnabled()&&player.isOnline()&&player.getOpenInventory().getTopInventory()==expected)open(player,0);});}
    public void closeAll() {for(var player:Bukkit.getOnlinePlayers())if(player.getOpenInventory().getTopInventory().getHolder() instanceof Holder||ShopQuantityGui.owns(player.getOpenInventory().getTopInventory()))player.closeInventory();}

    private record Offer(String id,int batch,long ask,long bid) { }
    private static final class Holder implements InventoryHolder {
        private final ShopCategory category; private final int page; private final ShopService service; private final boolean home;
        private final Map<Integer,Offer> offers=new java.util.HashMap<>(); private Inventory inventory;
        private Holder(ShopCategory category,int page,ShopService service,boolean home){this.category=category;this.page=page;this.service=service;this.home=home;}
        private void bind(Inventory inventory){this.inventory=inventory;}
        @Override public @NotNull Inventory getInventory(){return inventory;}
    }
}
