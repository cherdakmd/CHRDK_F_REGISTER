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
        store=new PaidActionStore(plugin.getDataFolder().toPath()); journal=new ChargeJournal(plugin.getDataFolder().toPath(),source,store.lineage());
        store.bind(this::paused);
        if (journal.pending()!=null && !ChargeInventory.valid(journal.pending())) throw new IOException("Неверный предметный план услуги");
        recover(null);
    }
    public PaidActionStore store() { return store; }
    public boolean paused() {
        if (!store.healthy() || !journal.healthy() || journal.pending()!=null) return true;
        var core=api.get(); return store.registered() && (core==null || core.bankChargeSequence(source,store.lineage())!=journal.nextSequence()-1);
    }
    public boolean occupied(UUID player) { return paused() || store.occupied(player); }
    private void say(CommandSender p,String key,Object...args) { messages.get().send(p,"finance.service-"+key,args); }
    private boolean ready() throws IOException {
        var core=api.get(); if (paused() || core==null || !core.personalBankAvailable()) return false;
        if (store.registered()) return core.incomeSourceAvailable(source,store.lineage());
        if (!core.registerIncomeSource(source,store.lineage())) return false; store.markRegistered(); return true;
    }
    private boolean eligible(Player p) {
        var core=api.get(); return p!=null && p.isOnline() && !p.isDead() && !p.hasMetadata("NPC") && core!=null && core.isAuthenticated(p)
                && (p.getGameMode()==GameMode.SURVIVAL || p.getGameMode()==GameMode.ADVENTURE);
    }
    private boolean pay(Player payer,int price,Supplier<String> preview) throws IOException {
        if (!eligible(payer)) { say(payer,"mode"); return false; }
        if (!ready()) { say(payer,"wait"); return false; }
        var before=ChargeInventory.copy(payer.getInventory().getStorageContents());
        var plan=ChargeInventory.plan(before,price,api.get().auctionBankBalance(payer.getUniqueId()));
        if (plan==null) { say(payer,"insufficient","cost",price); return false; }
        String afterState=preview.get();
        var payment=journal.prepare(payer.getUniqueId(),price,plan.debit(),plan.cash(),plan.change(),ChargeInventory.encode(before),ChargeInventory.encode(plan.after()),store.snapshot(),afterState);
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
        var a=new PaidActionStore.Action(UUID.randomUUID(),payer.getUniqueId(),actor.getUniqueId(),price,kind,payload,PaidActionStore.Phase.READY);
        try {
            if (!pay(payer,price,()->store.preview(a))) return false;
            boolean success=execute(store,a.id(),operation);
            if (!success) { flushRefunds(); say(payer,"refunding","cost",price); }
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
            return pay(payer,price,()->store.previewValue(key,value));
        } catch (Exception error) { failure(payer,error); return false; }
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
            flushRefunds(); return true;
        } catch (Exception error) { failure(player,error); return false; }
    }
    public void tick() { if (store.healthy() && journal.healthy()) recover(null); }
    public void flushRefunds() throws IOException {
        if (!ready()) return;
        var rows=new ArrayList<>(store.refunds().entrySet()); if (rows.isEmpty()) return;
        for (int i=0;i<Math.min(16,rows.size());i++) {
            var row=rows.get(Math.floorMod(refundCursor++,rows.size()));
            api.get().creditEntitlement(source,store.lineage(),row.getKey().toString(),row.getKey(),row.getValue());
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
