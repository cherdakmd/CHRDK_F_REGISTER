package ru.cherdakmd.chrdkguilds;

import java.util.UUID;

/** Личные взносы → коллективная казна: чистый план, публикация только банковским WAL. */
final class GuildDuesService {
    private final GuildStore store;
    private final GuildConfig config;
    GuildDuesService(GuildStore store,GuildConfig config){this.store=store;this.config=config;}
    static boolean overdue(Guild guild,UUID owner,long now){return guild!=null&&guild.duesTerm(owner)!=null&&guild.duesTerm(owner).dueAt()<=now;}
    GuildResult rate(UUID actor,int amount,long now){
        Guild g=store.of(actor);
        if(g==null)return GuildResult.fail("not-in-guild");
        if(!g.leader().equals(actor))return GuildResult.fail("not-leader");
        if(amount<10||amount>1_000_000)return GuildResult.fail("dues-range");
        if(g.duesRate()==amount)return GuildResult.fail("dues-same");
        if(g.duesChangedAt()>0&&now<g.duesChangedAt()+GuildPeaceTerm.DAY)return GuildResult.fail("dues-cooldown");
        if(g.memberIds().stream().anyMatch(id->overdue(g,id,now)))return GuildResult.fail("dues-settle-first");
        // Следующий уже объявленный счёт каждого участника остаётся прежним.
        g.duesRate(amount,now);
        return GuildResult.ok("dues-rate-set","rate",amount);
    }
    private GuildPeaceService.Plan plan(GuildResult r){return new GuildPeaceService.Plan(r,null,0,false);}
    GuildPeaceService.Plan daily(UUID owner,long now,long spendable){
        if(spendable<0)return plan(GuildResult.fail("finance-paused"));
        Guild g=store.of(owner);if(g==null)return plan(GuildResult.fail("peace-idle"));
        GuildDuesTerm t=g.duesTerm(owner);
        if(t==null||t.dueAt()>now)return plan(GuildResult.fail("peace-idle"));
        long required=(now-t.dueAt())/GuildPeaceTerm.DAY+1;
        boolean affordsAll=spendable>=Math.addExact(t.amount(),Math.multiplyExact(required-1,(long)g.duesRate()));
        long budget=Math.min(1_000_000,spendable);
        long days=budget<t.amount()?0:1+Math.min(required-1,(budget-t.amount())/g.duesRate());
        if(!affordsAll&&days<required&&t.graceSince()>0&&now>=t.deadline())return expire(g,owner);
        if(days>0){
            int cost=Math.toIntExact(t.amount()+(days-1)*g.duesRate());
            long due=Math.addExact(t.dueAt(),Math.multiplyExact(days,GuildPeaceTerm.DAY));
            long grace=due>now?0:(t.graceSince()==0&&!affordsAll?now:t.graceSince());
            g.creditDues(cost);g.duesTerm(owner,new GuildDuesTerm(due,grace,g.duesRate()));
            return new GuildPeaceService.Plan(GuildResult.ok("dues-paid","cost",cost,"days",days,"due",due,"deadline",grace==0?due+GuildPeaceTerm.DAY:grace+GuildPeaceTerm.DAY),owner,cost,false);
        }
        if(t.graceSince()==0){
            g.duesTerm(owner,t.unpaid(now));
            return plan(GuildResult.ok(owner.equals(g.leader())?"dues-leader-grace":"dues-member-grace","deadline",now+GuildPeaceTerm.DAY));
        }
        return plan(GuildResult.fail("peace-idle"));
    }
    private GuildPeaceService.Plan expire(Guild g,UUID owner){
        if(owner.equals(g.leader())){
            for(UUID id:g.memberIds())store.peaceNotice(id,"Клан «"+g.name()+"» распущен: лидер не оплатил обязательный взнос в казну за дополнительные сутки. Казна и коллективные резервы уничтожены.");
            // Ставки не исчезают: незакрытая война завершается по прежним правилам роспуска.
            new GuildService(config,store).cancelWarsOnDisband(g);store.remove(g);
            return plan(GuildResult.ok("dues-auto-disband","name",g.name()));
        }
        store.peaceNotice(owner,"Вы исключены из клана «"+g.name()+"»: обязательный взнос в казну не оплачен за дополнительные сутки.");
        g.removeMember(owner);store.reindex(g);
        return plan(GuildResult.ok("dues-auto-kick","name",g.name()));
    }
}
