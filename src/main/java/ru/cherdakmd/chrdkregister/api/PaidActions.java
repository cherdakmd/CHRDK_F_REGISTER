package ru.cherdakmd.chrdkregister.api;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.Messages;
import java.io.IOException;
import java.util.*;
import java.util.function.*;

/** Main-thread адаптер оплаты услуг. Не запускает повторно услугу после неизвестного исхода. */
public final class PaidActions {
    private final JavaPlugin plugin;
    private final Supplier<ChrdkRegisterApi> api;
    private final Supplier<Messages> messages;
    private final String source;
    private final PaidActionStore store;
    private final ChargeJournal journal;
    private int refundCursor;
    public PaidActions(JavaPlugin plugin, Supplier<ChrdkRegisterApi> api, Supplier<Messages> messages, String source) throws IOException {
        this.plugin=plugin; this.api=api; this.messages=messages; this.source=source;
        store=new PaidActionStore(plugin.getDataFolder().toPath());
        var initial=new ChargeJournal(plugin.getDataFolder().toPath(),source,store.lineage());
        if (initial.pending()!=null && !ChargeInventory.valid(initial.pending())) throw new IOException("Неверный предметный план услуги");
        var core=api.get();
        UUID external=core instanceof IncomeSourceLineageProvider provider?provider.incomeSourceLineage(source):null;
        if(external!=null&&!external.equals(store.lineage())&&registrationRecoverable(store,initial)) {
            store.adoptLineage(external);
            plugin.getLogger().info("Источник "+source+": принят lineage банка; финансовых обязательств не было, значения сохранены.");
        }
        journal=new ChargeJournal(plugin.getDataFolder().toPath(),source,store.lineage());
        store.bind(this::paused);
        recover(null);
    }
    public PaidActionStore store() { return store; }
    public boolean paused() {
        if (!store.healthy() || !journal.healthy() || journal.pending()!=null) return true;
        var core=api.get(); if(core==null) return true;
        long bankSequence=core.bankChargeSequence(source,store.lineage());
        return store.registered()&&bankSequence!=journal.nextSequence()-1&&!registrationRecoverable();
    }
    /** Ни оплата, ни refund/resolution ещё не существовали: потерян только ACK/lineage. */
    private boolean registrationRecoverable() { return registrationRecoverable(store,journal); }
    public static boolean registrationRecoverable(PaidActionStore store,ChargeJournal journal) {
        return store.healthy()&&journal.healthy()&&journal.pending()==null&&journal.nextSequence()==1
                &&store.actions().isEmpty()&&store.refunds().isEmpty()&&store.resolutions().isEmpty();
    }
    public boolean occupied(UUID player) { return paused() || store.occupied(player); }
    private void say(CommandSender p,String key,Object...args) { messages.get().send(p,"finance.service-"+key,args); }
    private boolean ready() throws IOException {
        var core=api.get(); if (paused() || core==null || !core.personalBankAvailable()) return false;
        if (core.incomeSourceAvailable(source,store.lineage())) { store.markRegistered(); return true; }
        if (!registrationRecoverable()||!core.registerIncomeSource(source,store.lineage())) return false;
        store.markRegistered(); return true;
    }
    private boolean eligible(Player p) {
        var core=api.get(); return p!=null && p.isOnline() && !p.isDead() && !p.hasMetadata("NPC") && core!=null && core.isAuthenticated(p)
                && (p.getGameMode()==GameMode.SURVIVAL || p.getGameMode()==GameMode.ADVENTURE);
    }
    private boolean pay(Player payer,long price,Supplier<String> preview) throws IOException {
        if (!eligible(payer)) { say(payer,"mode"); return false; }
        if (!ready()) { say(payer,"wait"); return false; }
        var before=ChargeInventory.copy(payer.getInventory().getStorageContents());
        var quote=api.get().diamondRate();
        var plan=store.minor()?ChargeInventory.planMinor(before,price,api.get().spendableBankBalanceMinor(payer.getUniqueId()),quote.rateBps())
                :ChargeInventory.plan(before,price,api.get().spendableBankBalance(payer.getUniqueId()));
        if (plan==null) { say(payer,"insufficient","cost",store.minor()?DiamondMoney.format(price):price); return false; }
        String afterState=preview.get();
        var payment=store.minor()?journal.prepareMinor(payer.getUniqueId(),price,plan.debit(),plan.cash(),plan.change(),quote.rateBps(),ChargeInventory.encode(before),ChargeInventory.encode(plan.after()),store.snapshot(),afterState)
                :journal.prepare(payer.getUniqueId(),price,plan.debit(),plan.cash(),plan.change(),ChargeInventory.encode(before),ChargeInventory.encode(plan.after()),store.snapshot(),afterState);
        api.get().commitBankCharge(payer,payment,before);
        var decision=ChargeInventory.recover(journal,api.get(),payer,store);
        if (decision==ChargeRecovery.Decision.ROLLBACK) { say(payer,"refused"); return false; }
        if (decision==ChargeRecovery.Decision.WAIT || decision==ChargeRecovery.Decision.CONFLICT) {
            if (plan.cash()>0) lock(payer); say(payer,"wait"); return false;
        }
        return true;
    }
    /** Вызвать только ПОСЛЕ warmup, безопасной точки и проверок прав/мира. false включает отменённый Bukkit teleport. */
    public boolean perform(Player payer,Player actor,int price,String kind,String payload,BooleanSupplier operation) {
        if (!eligible(payer) || !eligible(actor)) { if (payer!=null) say(payer,"mode"); return false; }
        if (occupied(payer.getUniqueId()) || occupied(actor.getUniqueId())) { say(payer,"wait"); return false; }
        if (price<1 || price>1_000_000) { say(payer,"refused"); return false; }
        int charged=store.minor()?Math.toIntExact(api.get().diamondRate().scaleMinor(price*100L)):price;
        var a=new PaidActionStore.Action(UUID.randomUUID(),payer.getUniqueId(),actor.getUniqueId(),charged,kind,payload,PaidActionStore.Phase.READY);
        try {
            if (!pay(payer,charged,()->store.preview(a))) return false;
            boolean success=execute(store,a.id(),operation);
            if (!success) { flushRefunds(); say(payer,"refunding","cost",store.minor()?DiamondMoney.format(charged):price); }
            return success;
        } catch (Exception error) { failure(payer,error); return false; }
    }
    /** Исполнитель production-пути; исключение native вызова оставляет durable EXECUTING. */
    public static boolean execute(PaidActionStore store, UUID id, BooleanSupplier operation) throws IOException {
        if (!store.begin(id)) return false;
        boolean success=operation.getAsBoolean();
        if (!store.finish(id,success)) throw new IllegalStateException("Потерян резерв услуги");
        return success;
    }
    /** Позиция сохраняется до закрытия резерва. Исключение saveData остаётся неизвестным исходом, не false. */
    public static boolean teleport(Player actor, org.bukkit.Location target) {
        if (!actor.teleport(target)) return false;
        actor.saveData(); return true;
    }
    /** Доменная запись (платный sethome) публикуется атомарно внутри source, без второго JSON. */
    public boolean putValue(Player payer,int price,String key,String value) {
        try {
            if (occupied(payer.getUniqueId())) { say(payer,"wait"); return false; }
            if (price==0) { store.putValue(key,value); return true; }
            long charged=store.minor()?api.get().diamondRate().scaleMinor(price*100L):price;
            return pay(payer,charged,()->store.previewValue(key,value));
        } catch (Exception error) { failure(payer,error); return false; }
    }
    /** API 31: ежедневная оплата/взыскание с offline UUID, без чтения playerdata и без предметной доплаты. */
    public boolean putGuildBankValue(UUID payer, int price, String key, String value, boolean collectingDebt) {
        try {
            if (!source.equals("guilds") || payer == null || price < 1 || price > 1_000_000 || occupied(payer) || !ready()) return false;
            String empty = ChargeInventory.encode(new org.bukkit.inventory.ItemStack[36]);
            long charged=store.minor()?api.get().diamondRate().scaleMinor(price*100L):price;
            var payment=store.minor()?journal.prepareMinor(payer,charged,charged,0,0,api.get().diamondRate().rateBps(),empty,empty,store.snapshot(),store.previewValue(key,value))
                    :journal.prepare(payer,price,price,0,0,empty,empty,store.snapshot(),store.previewValue(key,value));
            api.get().commitGuildBankCharge(payment, collectingDebt);
            var decision = ChargeInventory.recover(journal, api.get(), null, store);
            return decision == ChargeRecovery.Decision.COMPLETE;
        } catch (Exception error) { failure(null, error); return false; }
    }

    /** API 19: снятие из коллективного источника — только в банк, без предметной выдачи.
     * После успешного source replace ошибка доставки не отменяет уже созданное обязательство. */
    public boolean creditValue(Player player, String key, String value, long amount) {
        try {
            if (!eligible(player)) { say(player,"mode"); return false; }
            if (occupied(player.getUniqueId()) || !ready()) { say(player,"wait"); return false; }
            store.creditValue(key,value,player.getUniqueId(),amount);
        } catch (Exception error) { failure(player,error); return false; }
        try { flushRefunds(); }
        catch (Exception error) { plugin.getLogger().log(java.util.logging.Level.SEVERE,"Выплата сохранена и ожидает доставки в банк",error); }
        return true;
    }
    public boolean recover(Player player) {
        try {
            var p=journal.pending();
            var decision=ChargeInventory.recover(journal,api.get(),player,store);
            if (decision==ChargeRecovery.Decision.WAIT || decision==ChargeRecovery.Decision.CONFLICT) {
                if (player!=null && p!=null && p.payment().payer().equals(player.getUniqueId()) && p.payment().cash()>0) lock(player);
                return false;
            }
            if (paused()) return false;
            // READY доказывает: native-вызов ещё не начинался. После restart услуга уже не нужна — полный refund.
            for (var a:store.actions()) if (a.phase()==PaidActionStore.Phase.READY) store.refundReady(a.id());
            flushRefunds(); if(journal.pending()==null)store.enableMinor(); return true;
        } catch (Exception error) { failure(player,error); return false; }
    }
    public void tick() { if (store.healthy() && journal.healthy()) recover(null); }
    public void flushRefunds() throws IOException {
        if (!ready()) return;
        var rows=new ArrayList<>(store.refunds().entrySet()); if (rows.isEmpty()) return;
        for (int i=0;i<Math.min(16,rows.size());i++) {
            var row=rows.get(Math.floorMod(refundCursor++,rows.size()));
            if(store.minor()) api.get().creditEntitlementMinor(source,store.lineage(),row.getKey().toString(),row.getKey(),row.getValue());
            else api.get().creditEntitlement(source,store.lineage(),row.getKey().toString(),row.getKey(),row.getValue());
        }
    }
    public boolean admin(CommandSender sender,String[] args) {
        try {
            if (args.length==1 && args[0].equalsIgnoreCase("recover")) {
                var p=journal.pending(); recover(p==null || p.payment().cash()==0 ? null:Bukkit.getPlayer(p.payment().payer()));
                say(sender,paused()?"wait":"checked"); return true;
            }
            if (args.length==1 && args[0].equalsIgnoreCase("finance")) {
                if (journal.pending()!=null) say(sender,"payment","id",journal.pending().payment().transaction(),"payer",journal.pending().payment().payer());
                for (var a:store.actions()) say(sender,"action","id",a.id(),"payer",a.payer(),"actor",a.actor(),"price",a.price(),"phase",a.phase(),"kind",a.kind(),"target",a.payload());
                say(sender,"status","count",store.actions().size(),"paused",paused()); return true;
            }
            if (args.length>0 && args[0].equalsIgnoreCase("resolve")) {
                if (args.length!=4 || !args[3].equalsIgnoreCase("confirm") || !(args[2].equalsIgnoreCase("success") || args[2].equalsIgnoreCase("refund"))) { say(sender,"resolve-usage"); return true; }
                boolean success=args[2].equalsIgnoreCase("success");
                if (store.resolve(UUID.fromString(args[1]),success,sender.getName())) {
                    plugin.getLogger().warning("Ручная сверка услуги: " + sender.getName()+" / "+args[1]+" / "+args[2]); flushRefunds(); say(sender,"checked");
                } else say(sender,"refused");
                return true;
            }
            return false;
        } catch (Exception error) { plugin.getLogger().log(java.util.logging.Level.SEVERE,"Ошибка проверки оплаченной услуги",error); say(sender,"wait"); return true; }
    }
    private void failure(Player p,Exception error) {
        plugin.getLogger().log(java.util.logging.Level.SEVERE,"Оплаченная услуга приостановлена; резерв/журнал не сброшены",error);
        var pending=journal.pending();
        if (p!=null && (!journal.healthy() || pending!=null && pending.payment().cash()>0 && pending.payment().payer().equals(p.getUniqueId()))) lock(p);
        if (p!=null) say(p,"wait");
    }
    private void lock(Player p) {
        net.kyori.adventure.text.Component reason=net.kyori.adventure.text.Component.text("Незавершённая предметная оплата: нужна сверка банка. Повторите вход после восстановления.");
        try { reason=messages.get().get("finance.service-lock"); } catch (RuntimeException ignored) { }
        p.kick(reason);
    }
}
