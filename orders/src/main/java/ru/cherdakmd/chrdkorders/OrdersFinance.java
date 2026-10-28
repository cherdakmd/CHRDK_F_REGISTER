package ru.cherdakmd.chrdkorders;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.api.*;
import java.io.IOException;
import java.util.*;

/** orders.json — единый источник доски, лимитов, intent и накопительного дохода.
 * Банк получает только delta. Внешний void boost не объявляется ACID с этим протоколом. */
public final class OrdersFinance implements AutoCloseable {
    private static final NamespacedKey WITNESS=new NamespacedKey("chrdk_core","income_orders-income");
    private final OrdersPlugin plugin;
    private final OrdersStore store;
    private final BukkitTask task;
    private boolean conflict;
    private int cursor;
    private final MilestoneAwards.Bank bank=new MilestoneAwards.Bank() {
        public boolean available() { return plugin.coreApi()!=null&&plugin.coreApi().personalBankAvailable(); }
        public boolean registered(String s,UUID l) { return plugin.coreApi().incomeSourceAvailable(s,l); }
        public boolean register(String s,UUID l) { return plugin.coreApi().registerIncomeSource(s,l); }
        public boolean known(UUID owner) { return plugin.coreApi().canReceiveBankIncome(owner); }
        public long credited(String s,UUID l,UUID owner) { return plugin.coreApi().creditedEntitlement(s,l,owner.toString(),owner); }
        public boolean credit(String s,UUID l,UUID owner,long total) { return plugin.coreApi().creditEntitlement(s,l,owner.toString(),owner,total); }
    };
    public OrdersFinance(OrdersPlugin plugin) throws IOException {
        this.plugin=plugin; store=plugin.store();
        if(store.pending()!=null&&!valid(store.pending())) throw new IOException("Неверный предметный intent orders.json");
        tick(); task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20L,20L);
    }
    /** Реальный production-исполнитель банка; тесты используют настоящий BankStore. */
    public static boolean deliver(OrdersStore store,MilestoneAwards.Bank bank,UUID owner,long total) throws IOException {
        if(!store.healthy()||!bank.available()) return false;
        String source=OrdersStore.SOURCE;
        if(store.registered()) { if(!bank.registered(source,store.lineage())) throw new IllegalStateException("orders lineage unavailable"); }
        else { if(!bank.register(source,store.lineage())) throw new IllegalStateException("orders lineage conflict"); store.markRegistered(); }
        if(owner==null||!bank.known(owner)) return false;
        if(!Objects.equals(store.incomes().get(owner),total)) throw new IllegalArgumentException("unbacked orders income");
        long credited=bank.credited(source,store.lineage(),owner);
        if(credited<0||credited>total) throw new IllegalStateException("orders source older than bank");
        return total>0&&bank.credit(source,store.lineage(),owner,total);
    }
    public boolean paused() { return conflict||!store.idle(); }
    public boolean eligible(Player p) {
        var api=plugin.coreApi();
        return Bukkit.isPrimaryThread()&&p!=null&&p.isOnline()&&!p.isDead()&&!p.hasMetadata("NPC")&&p.hasPermission("chrdkorders.use")
                &&api!=null&&api.isAuthenticated(p)&&(p.getGameMode()==GameMode.SURVIVAL||p.getGameMode()==GameMode.ADVENTURE);
    }
    /** Storage-only; тот же isPlain, что использовался до смены денежного пути. */
    static ItemStack[] plan(ItemStack[] before,BotOrder order) {
        if(before==null||before.length!=36) return null;
        var material=order.materialOrNull(); if(material==null) return null;
        var after=ChargeInventory.copy(before); int remaining=order.amount();
        for(int slot=0;slot<after.length&&remaining>0;slot++) {
            var stack=after[slot]; if(stack==null||stack.getType()!=material||!OrdersService.isPlain(stack)) continue;
            int take=Math.min(remaining,stack.getAmount()); remaining-=take;
            if(take==stack.getAmount()) after[slot]=null; else stack.setAmount(stack.getAmount()-take);
        }
        return remaining==0?after:null;
    }
    static boolean valid(OrdersStore.Pending p) {
        try {
            var after=plan(ChargeInventory.decode(p.inventory().beforeInventory()),p.order());
            return after!=null&&ChargeInventory.matches(after,p.inventory().afterInventory());
        } catch(RuntimeException|LinkageError error) { return false; }
    }
    private InventoryIncome.Inventory inventory(Player p) {
        if(p==null) return null;
        return new InventoryIncome.Inventory() {
            public UUID owner() { return p.getUniqueId(); }
            public boolean available() { return p.isOnline()&&!p.isDead()&&!p.hasMetadata("NPC")&&plugin.coreApi()!=null&&plugin.coreApi().isAuthenticated(p); }
            public String witness() { return p.getPersistentDataContainer().get(WITNESS,PersistentDataType.STRING); }
            public boolean matches(String state) { return ChargeInventory.matches(p.getInventory().getStorageContents(),state); }
            public void checkpoint() { p.saveData(); }
            public void consume(String state,String witness) {
                p.getInventory().setStorageContents(ChargeInventory.decode(state));
                p.getPersistentDataContainer().set(WITNESS,PersistentDataType.STRING,witness);
                p.saveData();
            }
        };
    }
    public boolean complete(Player p,BotOrder order,long now) {
        if(p==null) return false;
        if(paused()) { waitMessage(p); return false; }
        if(!eligible(p)) { plugin.messages().send(p,"finance-mode"); return false; }
        final String beforeState,afterState;
        try {
            var before=ChargeInventory.copy(p.getInventory().getStorageContents()); var after=plan(before,order);
            if(after==null) { plugin.messages().send(p,"inventory-error"); return false; }
            beforeState=ChargeInventory.encode(before); afterState=ChargeInventory.encode(after);
        } catch(RuntimeException invalidInventory) {
            // Ничего ещё не записано/не списано. Непригодный для snapshot предмет
            // у одного игрока не должен переводить всю доску в IO-карантин.
            plugin.messages().send(p,"inventory-error"); return false;
        }
        try {
            var pending=store.prepare(p.getUniqueId(),p.getName(),order,now,plugin.config().maxPerDay(),plugin.config().cooldownSeconds(),beforeState,afterState);
            if(InventoryIncome.recover(pending.inventory(),inventory(p))!=InventoryIncome.Decision.CONSUMED) {
                conflict=true; lock(p); waitMessage(p); return false;
            }
            store.publish(pending); return true;
        } catch(Exception error) { fail(error); lock(p); waitMessage(p); return false; }
    }
    private void recover() throws IOException {
        var pending=store.pending(); if(pending==null) return;
        if(!valid(pending)) throw new IOException("Неверный предметный план сдачи");
        Player player=Bukkit.getPlayer(pending.inventory().owner());
        var result=InventoryIncome.recover(pending.inventory(),inventory(player));
        if(result==InventoryIncome.Decision.CONFLICT) { lock(player); throw new IOException("Inventory/witness не подтверждают сдачу"); }
        if(result==InventoryIncome.Decision.CONSUMED) {
            store.publish(pending);
            // До publish внешний boost ещё не запускался. Восстанавливаем best-effort,
            // но не обещаем durable ACK общего void API после этого commit.
            if(pending.order().rewardPassHours()>0&&plugin.coreApi()!=null) {
                try { plugin.coreApi().grantRewardBoostHours(player.getName(),pending.order().rewardPassHours(),"orders:"+pending.order().botId()); }
                catch(RuntimeException error) { plugin.getLogger().warning("Деньги за заказ сохранены, отдельный буст не подтверждён: "+error); }
            }
        }
    }
    public void tick() {
        if(conflict||!store.healthy()) return;
        try {
            recover(); var rows=new ArrayList<>(store.incomes().entrySet());
            if(rows.isEmpty()) { deliver(store,bank,null,0); return; }
            for(int i=0;i<Math.min(16,rows.size());i++) { var row=rows.get(Math.floorMod(cursor++,rows.size())); deliver(store,bank,row.getKey(),row.getValue()); }
        } catch(Exception error) { fail(error); var p=store.pending(); if(p!=null) lock(Bukkit.getPlayer(p.inventory().owner())); }
    }
    private void fail(Exception error) { conflict=true; plugin.getLogger().log(java.util.logging.Level.SEVERE,"Сдача заказов приостановлена; не удаляйте orders.json/playerdata",error); }
    private void lock(Player p) {
        if(p==null||!p.isOnline()) return;
        // Компонент сообщения из messages.yml, без сброса карантина или физической компенсации.
        p.kick(plugin.messages().get("finance-wait"));
    }
    private void waitMessage(Player p) { if(p!=null) plugin.messages().send(p,"finance-wait"); }
    public void status(org.bukkit.command.CommandSender sender) {
        tick(); plugin.messages().send(sender,"finance-status","source",OrdersStore.SOURCE,"lineage",store.lineage(),"healthy",store.healthy(),"pending",store.pending()!=null,"conflict",conflict,"count",store.incomes().size());
    }
    @Override public void close() { task.cancel(); }
}
