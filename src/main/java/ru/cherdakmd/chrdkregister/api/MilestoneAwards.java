package ru.cherdakmd.chrdkregister.api;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

/** API 20: once-награды в /bal. Сбой банка/потолок/неизвестный UUID сохраняют обязательство.
 * Внешние немонетарные награды и async progress не объявляются общей транзакцией с банком. */
public final class MilestoneAwards {
    public interface Bank {
        boolean available();
        boolean registered(String source,UUID lineage);
        boolean register(String source,UUID lineage);
        boolean known(UUID owner);
        long credited(String source,UUID lineage,UUID owner);
        boolean credit(String source,UUID lineage,UUID owner,long total);
    }
    /** Чистый production-исполнитель, проверяемый настоящим BankStore без Bukkit. */
    public static boolean deliver(MilestoneStore store,Bank bank,String source,UUID owner,long total) throws IOException {
        if(!store.healthy()||!bank.available()) return false;
        if(store.registered()) { if(!bank.registered(source,store.lineage())) throw new IllegalStateException("award lineage unavailable"); }
        else { if(!bank.register(source,store.lineage())) throw new IllegalStateException("award lineage conflict"); store.markRegistered(); }
        if(owner==null||total<=0||!bank.known(owner)) return false;
        if(!Objects.equals(store.totals().get(owner),total)) throw new IllegalArgumentException("unbacked award total");
        long credited=bank.credited(source,store.lineage(),owner);
        if(credited<0||credited>total) throw new IllegalStateException("award source older than bank receipt");
        return bank.credit(source,store.lineage(),owner,total);
    }
    private final JavaPlugin plugin;
    private final Supplier<ChrdkRegisterApi> api;
    private final String source;
    private final Supplier<ru.cherdakmd.chrdkregister.Messages> messages;
    private final org.bukkit.scheduler.BukkitTask task;
    private final MilestoneStore store;
    private boolean conflict;
    private int cursor, playerCursor;
    private java.util.function.Consumer<Player> reconcile=player->{};
    public void reconciler(java.util.function.Consumer<Player> action) { reconcile=Objects.requireNonNull(action); }
    private final Bank bank=new Bank() {
        public boolean available() { return api.get()!=null&&api.get().personalBankAvailable(); }
        public boolean registered(String s,UUID l) { return api.get().incomeSourceAvailable(s,l); }
        public boolean register(String s,UUID l) { return api.get().registerIncomeSource(s,l); }
        public boolean known(UUID owner) { return api.get().canReceiveBankIncome(owner); }
        public long credited(String s,UUID l,UUID owner) { return api.get().creditedEntitlement(s,l,owner.toString(),owner); }
        public boolean credit(String s,UUID l,UUID owner,long total) { return api.get().creditEntitlement(s,l,owner.toString(),owner,total); }
    };
    public MilestoneAwards(JavaPlugin plugin,Supplier<ChrdkRegisterApi> api,Supplier<ru.cherdakmd.chrdkregister.Messages> messages,String source,Map<UUID,Set<String>> legacy) throws Exception {
        if(!source.matches("[a-z][a-z0-9-]{0,47}")||api.get()==null||ChrdkRegisterApi.class.getField("API_VERSION").getInt(null)<20) throw new IllegalStateException("Нужен Core API 20+");
        this.plugin=plugin; this.api=api; this.messages=messages; this.source=source;
        store=new MilestoneStore(plugin.getDataFolder().toPath()); store.initialize(legacy); tick();
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20L,20L);
    }
    public void close() { task.cancel(); }
    public MilestoneStore store() { return store; }
    public boolean ready(Player player) { return Bukkit.isPrimaryThread()&&!conflict&&store.healthy()&&player!=null&&player.isOnline()&&!player.hasMetadata("NPC")&&api.get()!=null&&api.get().isAuthenticated(player); }
    /** Вызывать до legacy markClaimed и до объявления успеха. Повтор не меняет замороженную сумму. */
    public boolean reserve(Player player,String key,long amount) {
        return reserveAll(player,Map.of(key,amount));
    }
    public boolean reserveAll(Player player,Map<String,Long> awards) {
        if(!ready(player)) return false;
        try { store.reserveAll(player.getUniqueId(),awards); return true; }
        catch(Exception error) { conflict=true; failure(error); return false; }
    }
    public long amount(Player player,String key) { return store.amount(player.getUniqueId(),key); }
    public void tick() {
        if(conflict||!store.healthy()) return;
        try {
            var online=new ArrayList<>(Bukkit.getOnlinePlayers());
            for(int i=0;i<Math.min(4,online.size());i++) {
                var player=online.get(Math.floorMod(playerCursor++,online.size()));
                if(ready(player)) reconcile.accept(player);
            }
            var rows=new ArrayList<>(store.totals().entrySet());
            // Даже пустой источник регистрируется, чтобы потеря ledger не выглядела новой установкой.
            if(rows.isEmpty()) { deliver(store,bank,source,null,0); return; }
            for(int i=0;i<Math.min(16,rows.size());i++) {
                var row=rows.get(Math.floorMod(cursor++,rows.size())); deliver(store,bank,source,row.getKey(),row.getValue());
            }
        } catch(Exception error) { conflict=true; failure(error); }
    }
    private void failure(Exception error) { plugin.getLogger().log(java.util.logging.Level.SEVERE,"Источник наград приостановлен; не удаляйте milestone-awards.json",error); }
    /** Обёртка вызывается только за штатным admin permission модуля. */
    public boolean admin(CommandSender sender,String[] args) {
        if(args.length!=1||!args[0].equalsIgnoreCase("awards")) return false;
        tick();
        messages.get().send(sender,"finance.award-status","source",source,"lineage",store.lineage(),"count",store.totals().size(),"healthy",store.healthy(),"conflict",conflict);
        return true;
    }
}
