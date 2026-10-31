package ru.cherdakmd.chrdkguilds;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.function.Function;

/** Главный поток: ограниченный обход участников, банковский WAL и одноразовые котировки. */
final class GuildPeaceController {
    private final GuildsPlugin plugin;
    private final GuildCombatTracker combat=new GuildCombatTracker();
    private final Map<UUID,Quote> quotes=new HashMap<>();
    private int cursor;
    private int debtCursor;
    private final long started=System.currentTimeMillis();
    private record Quote(String action,int days,String state,long expires) { }
    GuildPeaceController(GuildsPlugin plugin) { this.plugin=plugin; }
    boolean combatBlocked(UUID player) { return combat.blocked(player,System.currentTimeMillis()); }
    long reserved(UUID owner) {
        try { return plugin.payments().store().healthy()?plugin.store().sanction(owner).debt():-1; }
        catch(RuntimeException error) { return -1; }
    }
    boolean protects(UUID player,long now) { return new GuildPeaceService(plugin.store()).protects(player,now); }
    boolean peaceful(UUID player) { Guild g=plugin.store().of(player);return g!=null&&g.peaceful(); }

    /** Возвращает true, если собственное правило кланов должно остановить PvP. */
    boolean attack(UUID attacker,UUID victim,boolean launchedPeaceful,long now) { return attack(attacker,victim,launchedPeaceful,true,now); }
    boolean attack(UUID attacker,UUID victim,boolean launchedPeaceful,boolean penalize,long now) {
        if(attacker==null || victim==null || attacker.equals(victim))return false;
        boolean protectedVictim=protects(victim,now);
        GuildPvpDecision decision=GuildPvpDecision.decide(false,peaceful(attacker),launchedPeaceful,protectedVictim,penalize);
        if(decision!=GuildPvpDecision.ALLOW) {
            if(decision==GuildPvpDecision.BLOCK_AND_FINE) {
                combat.mark(attacker,now);
                // Повторный удар не копирует/не сериализует весь домен: горячий путь — индекс UUID.
                if(now<plugin.store().sanction(attacker).nextIncidentAt())return true;
                GuildResult result=plugin.store().transaction(copy->new GuildPeaceService(copy).fine(attacker,now),GuildResult.fail("finance-paused"));
                if(result.ok()) {
                    tell(attacker,result);
                    collect(attacker);
                }
            }
            return true;
        }
        combat.mark(attacker,now);combat.mark(victim,now);
        return false;
    }
    void tick(long now) {
        combat.prune(now);quotes.entrySet().removeIf(e->e.getValue().expires()<now);
        if(plugin.payments().paused() || plugin.coreApi()==null || !plugin.coreApi().personalBankAvailable())return;
        List<UUID> debtors=new ArrayList<>(plugin.store().sanctions().entrySet().stream().filter(e->e.getValue().debt()>0).map(Map.Entry::getKey).toList());
        for(int i=0;i<Math.min(2,debtors.size());i++) {
            if(plugin.payments().paused())return;
            collect(debtors.get(Math.floorMod(debtCursor++,debtors.size())));
        }
        if(!plugin.config().enabled())return;
        List<UUID> roster=new ArrayList<>();
        for(Guild guild:plugin.store().guilds())roster.addAll(guild.memberIds());
        // Не более четырёх участников за такт: нет лавины fsync при общем сроке оплаты.
        for(int i=0;i<Math.min(4,roster.size());i++) {
            if(plugin.payments().paused())return;
            UUID owner=roster.get(Math.floorMod(cursor++,roster.size()));
            Guild guild=plugin.store().of(owner);
            if(guild==null)continue;
            if(guild.duesTerm(owner).dueAt()<=now){
                List<UUID> members=guild.memberIds();
                long spendable=plugin.coreApi().spendableBankBalance(owner);
                GuildResult dues=publish(copy->new GuildDuesService(copy,plugin.config()).daily(owner,now,spendable));
                if(dues!=null&&dues.ok()){
                    if(dues.key().equals("dues-auto-disband"))for(UUID id:members)deliverNotices(id);
                    else if(dues.key().equals("dues-auto-kick"))deliverNotices(owner);
                    else if(dues.key().equals("dues-leader-grace"))for(UUID id:members)tell(id,dues);
                    else tell(owner,dues);
                }
                guild=plugin.store().of(owner);
            }
            if(guild==null || !guild.peaceful() || guild.peaceTerm(owner).paidUntil()>now)continue;
            List<UUID> recipients=guild.memberIds();
            long balance=plugin.coreApi().spendableBankBalance(owner);
            GuildResult result=publish(copy->new GuildPeaceService(copy).daily(owner,now,balance));
            if(result!=null&&result.ok()) {
                if(result.key().equals("peace-auto-disband"))for(UUID id:recipients)deliverNotices(id);
                else if(result.key().equals("peace-auto-kick"))deliverNotices(owner);
                else if(result.key().equals("peace-leader-grace"))for(UUID id:recipients)tell(id,result);
                else tell(owner,result);
            }
        }
    }
    private void collect(UUID owner) {
        if(plugin.payments().paused() || plugin.coreApi()==null || !plugin.coreApi().personalBankAvailable())return;
        long available=plugin.coreApi().auctionBankBalance(owner);
        GuildResult result=publish(copy->new GuildPeaceService(copy).collect(owner,available));
        if(result!=null&&result.ok())tell(owner,result);
    }
    private GuildResult publish(Function<GuildStore,GuildPeaceService.Plan> operation) {
        try {
            GuildStore copy=plugin.store().copy();String before=copy.snapshotJson();
            var plan=operation.apply(copy);
            if(!plan.result().ok())return plan.result();
            String after=copy.snapshotJson();copy.detached(after);
            if(before.equals(after))return plan.result();
            if(plan.debit()>0) {
                if(!plugin.payments().putGuildBankValue(plan.payer(),plan.debit(),GuildStore.FINANCE_KEY,after,plan.collectingDebt()))return null;
            } else plugin.payments().store().putValue(GuildStore.FINANCE_KEY,after);
            // Данные live обновятся из durable source, не из изменённой копии.
            plugin.store().guilds();
            return plan.result();
        } catch(Exception error) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,"Мирный режим: финансовый исход не подтверждён; состав не изменён",error);
            return null;
        }
    }
    void command(Player player,String[] args) {
        UUID owner=player.getUniqueId();String action=args.length<2?"status":args[1].toLowerCase(Locale.ROOT);
        if(action.equals("status")) { status(player);return; }
        if(plugin.payments().paused()||plugin.coreApi()==null||!plugin.coreApi().personalBankAvailable()) {send(player,GuildResult.fail("finance-paused"));return;}
        int days=0;
        if(action.equals("prepay")) {
            if(args.length<3){send(player,GuildResult.fail("peace-days"));return;}
            try { days=Integer.parseInt(args[2]); }catch(NumberFormatException e){send(player,GuildResult.fail("peace-days"));return;}
        } else if(!(action.equals("peaceful")||action.equals("battle"))){send(player,GuildResult.fail("peace-usage"));return;}
        if(System.currentTimeMillis()<started+GuildPeaceService.WARMUP || combatBlocked(owner)){send(player,GuildResult.fail("peace-combat"));return;}
        boolean confirm=args[args.length-1].equalsIgnoreCase("confirm");long now=System.currentTimeMillis();
        if(!confirm) {
            int requested=days;
            GuildFinance.Preview preview=GuildFinance.preview(plugin.store(),plugin.config(),(service,purse)->action.equals("prepay")
                    ?new GuildPeaceService(service.store()).prepay(owner,requested,now)
                    :new GuildPeaceService(service.store()).mode(purse,owner,action.equals("peaceful")?GuildMode.PEACEFUL:GuildMode.BATTLE,now));
            if(!preview.result().ok()){send(player,preview.result());return;}
            quotes.put(owner,new Quote(action,days,plugin.store().snapshotJson(),now+30_000));
            send(player,GuildResult.ok("peace-confirm","action",action,"days",days,"personal",preview.debit(),"rate",GuildPeaceService.DAILY));
            Guild before=plugin.store().of(owner),after=plugin.store().detached(preview.domain()).of(owner);
            send(player,GuildResult.ok("peace-preview-cost","treasury",GuildFinance.treasurySpent(before,after),"personal",preview.debit()));
            return;
        }
        Quote quote=quotes.remove(owner);
        if(quote==null || quote.expires()<now || !quote.action().equals(action) || quote.days()!=days || !quote.state().equals(plugin.store().snapshotJson())) {
            send(player,GuildResult.fail("peace-quote-expired"));return;
        }
        GuildResult result;
        if(action.equals("prepay"))result=plugin.store().transaction(copy->new GuildPeaceService(copy).prepay(owner,quote.days(),now),GuildResult.fail("finance-paused"));
        else result=plugin.finance().mode(player,action.equals("peaceful")?GuildMode.PEACEFUL:GuildMode.BATTLE,now);
        send(player,result);
        if(result.ok()) {
            Guild guild=plugin.store().of(owner);
            if(guild!=null)for(UUID member:guild.memberIds()) {
                if(!member.equals(owner))tell(member,GuildResult.ok("peace-mode-notice","mode",guild.mode().label(),"rate",GuildPeaceService.DAILY));
            }
        }
    }
    boolean confirmJoin(Player player,boolean confirmed) {
        UUID owner=player.getUniqueId();long now=System.currentTimeMillis();
        if(!confirmed) {
            var preview=GuildFinance.preview(plugin.store(),plugin.config(),(service,purse)->service.accept(purse,owner,now));
            if(!preview.result().ok()){send(player,preview.result());return false;}
            quotes.put(owner,new Quote("join",0,plugin.store().snapshotJson(),now+30_000));
            Guild g=plugin.store().guild(plugin.store().inviteOf(owner).guildId());
            send(player,GuildResult.ok("dues-join-confirm","rate",g.duesRate(),"peace",g.peaceful()?GuildPeaceService.DAILY:0,"cost",preview.debit()));return false;
        }
        Quote quote=quotes.remove(owner);
        if(quote==null||!quote.action().equals("join")||quote.expires()<now||!quote.state().equals(plugin.store().snapshotJson())){
            send(player,GuildResult.fail("peace-quote-expired"));return false;
        }
        return true;
    }
    void status(Player player) {
        Guild guild=plugin.store().of(player.getUniqueId());
        long debt=reserved(player.getUniqueId());
        send(player,GuildResult.ok("peace-debt-status","debt",debt));
        if(guild==null){send(player,GuildResult.fail("not-in-guild"));return;}
        duesStatus(player,guild);
        send(player,GuildResult.ok("peace-status","mode",guild.mode().label(),"rate",GuildPeaceService.DAILY,"total",(long)guild.size()*GuildPeaceService.DAILY));
        if(guild.peaceful()) {
            var term=guild.peaceTerm(player.getUniqueId());
            send(player,GuildResult.ok("peace-term-status","paid",time(term.paidUntil()),"deadline",time(term.deadline()),"active",time(term.activatesAt())));
            if(term.graceSince()>0)send(player,GuildResult.ok("peace-grace-status","deadline",time(term.deadline())));
        }
        var leaderTerm=guild.peaceTerm(guild.leader());
        if(leaderTerm!=null&&leaderTerm.graceSince()>0)send(player,GuildResult.ok("peace-leader-risk","deadline",time(leaderTerm.deadline())));
        if(guild.leader().equals(player.getUniqueId()))send(player,GuildResult.ok("peace-leader-help"));
    }
    void duesStatus(Player player,Guild guild){
        GuildDuesTerm t=guild.duesTerm(player.getUniqueId());
        send(player,GuildResult.ok("dues-status","rate",guild.duesRate(),"amount",t.amount(),"due",time(t.dueAt()),"reserve",guild.duesReserve()));
        if(t.graceSince()>0)send(player,GuildResult.ok("dues-member-grace","deadline",t.deadline()));
        GuildDuesTerm leader=guild.duesTerm(guild.leader());
        if(leader.graceSince()>0)send(player,GuildResult.ok("dues-leader-grace","deadline",leader.deadline()));
    }
    void dues(Player player,String[] args){
        Guild guild=plugin.store().of(player.getUniqueId());
        if(guild==null){send(player,GuildResult.fail("not-in-guild"));return;}
        if(args.length<2||args[1].equalsIgnoreCase("status")){duesStatus(player,guild);return;}
        int rate;
        try{rate=Integer.parseInt(args[1]);}catch(NumberFormatException error){send(player,GuildResult.fail("dues-range"));return;}
        if(plugin.payments().paused()||!plugin.coreApi().personalBankAvailable()){send(player,GuildResult.fail("finance-paused"));return;}
        boolean confirmed=args.length==3&&args[2].equalsIgnoreCase("confirm");long now=System.currentTimeMillis();
        if(!confirmed){
            GuildResult check=new GuildDuesService(plugin.store().copy(),plugin.config()).rate(player.getUniqueId(),rate,now);
            if(!check.ok()){send(player,check);return;}
            quotes.put(player.getUniqueId(),new Quote("dues",rate,plugin.store().snapshotJson(),now+30_000));
            send(player,GuildResult.ok("dues-rate-confirm","rate",rate));return;
        }
        Quote q=quotes.remove(player.getUniqueId());
        if(q==null||!q.action().equals("dues")||q.days()!=rate||q.expires()<now||!q.state().equals(plugin.store().snapshotJson())){send(player,GuildResult.fail("peace-quote-expired"));return;}
        GuildResult result=plugin.store().transaction(copy->{
            GuildResult changed=new GuildDuesService(copy,plugin.config()).rate(player.getUniqueId(),rate,now);
            if(changed.ok())for(UUID id:copy.of(player.getUniqueId()).memberIds())copy.peaceNotice(id,"Лидер установил взнос в казну "+rate+" алмазов/сутки. Следующий ранее объявленный платёж каждого участника сохраняется; новый тариф — с последующего периода. Мирный режим оплачивается отдельно.");
            return changed;
        },GuildResult.fail("finance-paused"));
        send(player,result);
        if(result.ok()){plugin.gui().refresh();for(UUID id:guild.memberIds())deliverNotices(id);}
    }
    boolean confirmCreate(Player player,String name,String tag,boolean confirmed){
        if(plugin.payments().paused()||!plugin.coreApi().personalBankAvailable()){send(player,GuildResult.fail("finance-paused"));return false;}
        long now=System.currentTimeMillis();UUID owner=player.getUniqueId();
        String key="create:"+Guild.cleanName(name)+":"+Guild.cleanTag(tag);
        if(!confirmed){
            var plan=GuildFinance.preview(plugin.store(),plugin.config(),(service,purse)->service.create(purse,owner,name,tag,now));
            if(!plan.result().ok()){send(player,plan.result());return false;}
            quotes.put(owner,new Quote(key,0,plugin.store().snapshotJson(),now+30_000));
            send(player,GuildResult.ok("dues-create-confirm","cost",plan.debit(),"command","/clan create "+Guild.cleanName(name)+" "+Guild.cleanTag(tag)+" confirm"));return false;
        }
        Quote q=quotes.remove(owner);
        if(q==null||!q.action().equals(key)||q.expires()<now||!q.state().equals(plugin.store().snapshotJson())){send(player,GuildResult.fail("peace-quote-expired"));return false;}
        return true;
    }
    void deliverNotices(UUID owner) {
        Player player=Bukkit.getPlayer(owner);
        if(player==null||!player.isOnline()||!plugin.authenticated(player))return;
        List<String> lines=plugin.store().peaceNotices(owner);
        if(lines.isEmpty())return;
        for(String line:lines)plugin.messages().send(player,"peace-personal-notice","text",line);
        plugin.store().transaction(copy->{copy.clearPeaceNotices(owner);return true;},false);
    }
    void quit(UUID owner){quotes.remove(owner);}
    static String time(long millis){return java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));}
    private void tell(UUID owner,GuildResult result){
        Player player=Bukkit.getPlayer(owner);
        boolean personalPenalty=result.key().startsWith("dues-")||result.key().equals("peace-fined")||result.key().equals("peace-debt-collected");
        if(plugin.visible(player)||(personalPenalty&&player!=null&&player.isOnline()&&plugin.authenticated(player)))send(player,result);
    }
    private void send(Player player,GuildResult result){
        Object[] args=result.array();
        for(int i=0;i+1<args.length;i+=2)if((args[i].equals("deadline")||args[i].equals("due"))&&args[i+1].toString().matches("[0-9]+"))args[i+1]=time(Long.parseLong(args[i+1].toString()));
        plugin.messages().send(player,result.key(),args);
    }
}
