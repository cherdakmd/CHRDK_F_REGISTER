package ru.cherdakmd.chrdkguilds;

import java.util.UUID;

/** Чистые правила: план расходов и состояние публикуются адаптером одной WAL-операцией. */
final class GuildPeaceService {
    static final int DAILY = 10;
    static final int FINE = 100;
    static final long INCIDENT = 60_000L;
    static final long WARMUP = 60_000L;
    static final long MODE_COOLDOWN = GuildPeaceTerm.DAY;
    private final GuildStore store;
    GuildPeaceService(GuildStore store) { this.store=store; }
    record Plan(GuildResult result, UUID payer, int debit, boolean collectingDebt) { }
    private Plan result(GuildResult result) { return new Plan(result,null,0,false); }

    boolean protects(UUID player,long now) {
        Guild guild=store.of(player);
        return guild!=null && guild.peaceful() && guild.peaceTerm(player)!=null && guild.peaceTerm(player).protectedAt(now);
    }
    GuildResult mode(GuildPurse purse,UUID actor,GuildMode mode,long now) {
        Guild guild=store.of(actor);
        if(guild==null)return GuildResult.fail("not-in-guild");
        if(!guild.leader().equals(actor))return GuildResult.fail("not-leader");
        if(guild.mode()==mode)return GuildResult.fail("peace-same-mode");
        if(store.warOf(guild.id())!=null)return GuildResult.fail("peace-war-busy");
        if(guild.memberIds().stream().anyMatch(store::combatBlocked))return GuildResult.fail("peace-combat");
        if(guild.modeChangedAt()>0 && now<Math.addExact(guild.modeChangedAt(),MODE_COOLDOWN))return GuildResult.fail("peace-mode-cooldown");
        if(guild.peaceful()&&guild.peaceTerms().values().stream().anyMatch(term->term.paidUntil()<=now))return GuildResult.fail("peace-settle-first");
        if(mode==GuildMode.BATTLE) { guild.mode(mode,now);return GuildResult.ok("peace-mode-battle"); }
        if(guild.memberIds().stream().anyMatch(id->store.sanction(id).branded(now)))return GuildResult.fail("discipline-brand-block");
        if(guild.memberIds().stream().anyMatch(id->store.sanction(id).debt()>0))return GuildResult.fail("peace-debt-mode");
        long cost=Math.multiplyExact(guild.size(),DAILY);
        long fromTreasury=Math.min(cost,guild.treasury());
        long personal=cost-fromTreasury;
        if(purse==null || purse.take(actor,personal)!=personal)return GuildResult.fail("peace-no-money");
        guild.withdraw(fromTreasury);
        guild.mode(mode,now);
        long active=Math.addExact(now,WARMUP);
        for(UUID member:guild.memberIds())guild.peaceTerm(member,new GuildPeaceTerm(Math.addExact(active,GuildPeaceTerm.DAY),0,active));
        return GuildResult.ok("peace-mode-on","cost",cost,"treasury",fromTreasury,"personal",personal,"seconds",WARMUP/1000);
    }
    GuildResult prepay(UUID actor,int days,long now) {
        Guild guild=store.of(actor);
        if(guild==null)return GuildResult.fail("not-in-guild");
        if(!guild.leader().equals(actor))return GuildResult.fail("not-leader");
        if(!guild.peaceful())return GuildResult.fail("peace-not-peaceful");
        if(days<1 || days>30)return GuildResult.fail("peace-days");
        long cost=Math.multiplyExact(Math.multiplyExact((long)guild.size(),days),DAILY);
        if(guild.treasury()<cost)return GuildResult.fail("peace-treasury-low","cost",cost);
        for(var term:guild.peaceTerms().values()) {
            long until=Math.addExact(term.paidUntil(),Math.multiplyExact(days,GuildPeaceTerm.DAY));
            if(until<=now)return GuildResult.fail("peace-prepay-behind");
            if(until>Math.addExact(now,31*GuildPeaceTerm.DAY+WARMUP))return GuildResult.fail("peace-prepay-cap");
        }
        guild.withdraw(cost);
        for(UUID member:guild.memberIds()) {
            var before=guild.peaceTerm(member);
            long until=Math.addExact(before.paidUntil(),Math.multiplyExact(days,GuildPeaceTerm.DAY));
            guild.peaceTerm(member,new GuildPeaceTerm(until,0,before.activatesAt()));
        }
        return GuildResult.ok("peace-prepaid","days",days,"cost",cost,"members",guild.size());
    }
    /** Вызов только с авторитетным расходуемым балансом; неизвестный исход не равен нулю. */
    Plan daily(UUID member,long now,long spendable) {
        if(spendable<0)return result(GuildResult.fail("finance-paused"));
        Guild guild=store.of(member);
        if(guild==null || !guild.peaceful())return result(GuildResult.fail("peace-idle"));
        var term=guild.peaceTerm(member);
        if(term==null || now<term.paidUntil())return result(GuildResult.fail("peace-idle"));
        boolean leader=member.equals(guild.leader());
        long treasury=leader?guild.treasury():0;
        long required=Math.addExact((now-term.paidUntil())/GuildPeaceTerm.DAY,1);
        int days=(int)Math.min(Math.min(required,100_000),(Math.min(spendable,1_000_000)+treasury)/DAILY);
        if(days>0) {
            long cost=(long)days*DAILY, fromTreasury=Math.min(treasury,cost);
            int debit=Math.toIntExact(cost-fromTreasury);
            guild.withdraw(fromTreasury);
            guild.peaceTerm(member,term.paid(days,now));
            return new Plan(GuildResult.ok("peace-paid","days",days,"cost",cost),member,debit,false);
        }
        if(term.graceSince()==0) {
            guild.peaceTerm(member,term.unpaid(now));
            return result(GuildResult.ok(leader?"peace-leader-grace":"peace-member-grace","deadline",Math.addExact(now,GuildPeaceTerm.DAY)));
        }
        if(now<term.deadline())return result(GuildResult.fail("peace-idle"));
        if(leader) {
            for(UUID owner:guild.memberIds())store.peaceNotice(owner,"Клан «"+guild.name()+"» распущен: мирный режим лидера не оплачен за дополнительные сутки. Остатки казны и военных возвратов уничтожены.");
            store.remove(guild);
            enforce(member,now);
            return result(GuildResult.ok("peace-auto-disband","name",guild.name(),"treasury",guild.treasury(),"returns",guild.warReturns()));
        }
        store.peaceNotice(member,"Вы исключены из клана «"+guild.name()+"»: мирный режим не оплачен за дополнительные сутки.");
        guild.removeMember(member);store.reindex(guild);
        return result(GuildResult.ok("peace-auto-kick","name",guild.name()));
    }
    GuildResult fine(UUID attacker,long now) {
        return fine(attacker,attacker,UUID.randomUUID(),"melee",false,now);
    }
    /** Штраф, доказательство, клеймо и исключение входят в один снимок домена/WAL. */
    GuildResult fine(UUID attacker,UUID victim,UUID source,String kind,boolean peacefulAction,long now) {
        var before=store.sanction(attacker);
        if(now<before.nextIncidentAt() || before.seen(source))return GuildResult.fail("peace-incident-active");
        Guild guild=store.of(attacker);
        int previous=before.violations(now);
        var event=new GuildViolation(UUID.randomUUID(),now,victim,source,kind,peacefulAction,false);
        var after=before.incident(event,Math.addExact(now,INCIDENT));
        store.sanction(attacker,after);
        String key="peace-fined";
        if(peacefulAction && !before.branded(now)) {
            int count=after.violations(now);
            boolean leader=guild!=null&&guild.leader().equals(attacker);
            if(count>=GuildSanction.LIMIT && !leader) {
                punish(attacker,now,"Десять нарушений мирного режима за 30 дней");
                key="discipline-expelled";
            } else {
                key=leader&&count>=GuildSanction.LIMIT?"discipline-leader-warning"
                        :count>=9?"discipline-last-warning":count>=7?"discipline-warning":"discipline-fined";
                if(leader && previous<GuildSanction.LIMIT && count>=GuildSanction.LIMIT) {
                    store.sanction(attacker,after.log(now,"system","leader-threshold","Глава достиг 10 нарушений мирного режима"));
                    notice(guild,"Глава клана достиг 10 нарушений мирного режима. Атаки блокируются, личные штрафы сохраняются. Глава освобождён от исключения и клейма.");
                }
            }
        }
        return GuildResult.ok(key,"fine",FINE,"debt",store.sanction(attacker).debt(),
                "count",after.violations(now),"until",store.sanction(attacker).brandUntil(),"id",event.id());
    }
    /** Проверяется и после передачи руководства: должность не обнуляет накопленные нарушения. */
    boolean enforce(UUID owner,long now) {
        var before=store.sanction(owner);
        Guild guild=store.of(owner);
        if(before.branded(now) || before.violations(now)<GuildSanction.LIMIT
                || (guild!=null&&guild.leader().equals(owner)))return false;
        punish(owner,now,"Проверка накопленных нарушений после изменения роли/состава");
        return true;
    }
    private void punish(UUID owner,long now,String reason) {
        var before=store.sanction(owner);
        store.sanction(owner,before.brand(now,reason));
        Guild guild=store.of(owner);
        if(guild!=null) {
            notice(guild,"Участник [player:"+owner+"] с позором исключён за систематическое нарушение мира. Клеймо «Нарушитель мира» — 7 дней.");
            guild.removeMember(owner);store.reindex(guild);
        }
        store.peaceNotice(owner,"Вы получили клеймо «Нарушитель мира» на 7 дней. Защита мирного клана потеряна. Вступление в мирные кланы запрещено; штрафной долг сохраняется.");
    }
    private void notice(Guild guild,String text) { for(UUID member:guild.memberIds())store.peaceNotice(member,text); }
    GuildResult forgive(UUID owner,UUID incident,long now,String actor,String reason) {
        var before=store.sanction(owner);
        if(before.incidents().stream().noneMatch(v->v.id().equals(incident)&&!v.forgiven()))return GuildResult.fail("discipline-no-incident");
        store.sanction(owner,before.forgive(incident,now,actor,reason));
        store.peaceNotice(owner,"Администратор отменил нарушение "+incident+". Причина: "+reason+". Денежный долг не изменён; снятие клейма оформляется отдельно.");
        return GuildResult.ok("discipline-admin-done");
    }
    GuildResult unbrand(UUID owner,long now,String actor,String reason) {
        var before=store.sanction(owner);
        if(!before.branded(now))return GuildResult.fail("discipline-no-brand");
        store.sanction(owner,before.unbrand(now,actor,reason));
        store.peaceNotice(owner,"Администратор снял клеймо. Причина: "+reason+". Денежный долг сохраняется; прежнее членство автоматически не восстанавливается.");
        return GuildResult.ok("discipline-admin-done");
    }
    Plan collect(UUID owner,long available) {
        var before=store.sanction(owner);
        int debit=(int)Math.min(1_000_000,Math.min(before.debt(),Math.max(0,available)));
        if(debit==0)return result(GuildResult.fail("peace-idle"));
        store.sanction(owner,before.debt(before.debt()-debit));
        return new Plan(GuildResult.ok("peace-debt-collected","paid",debit,"debt",before.debt()-debit),owner,debit,true);
    }
}
