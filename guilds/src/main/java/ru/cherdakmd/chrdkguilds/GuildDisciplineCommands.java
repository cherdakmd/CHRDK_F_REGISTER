package ru.cherdakmd.chrdkguilds;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.Arrays;
import java.util.UUID;

/** Публичный статус и адресная амнистия с подтверждением, причиной и durable-журналом. */
final class GuildDisciplineCommands {
    private final GuildsPlugin plugin;
    GuildDisciplineCommands(GuildsPlugin plugin) { this.plugin=plugin; }
    void status(CommandSender sender,String[] args) {
        UUID owner=args.length>1?resolve(args[1]):sender instanceof Player p?p.getUniqueId():null;
        if(owner==null){plugin.messages().send(sender,"discipline-public-help");return;}
        plugin.messages().send(sender,"discipline-player","player",name(owner));
        plugin.peace().disciplineStatus(sender,owner);
    }
    void admin(CommandSender sender,String[] args) {
        if(!sender.hasPermission("chrdkguilds.admin")){plugin.messages().send(sender,"no-permission");return;}
        if(args.length<3){plugin.messages().send(sender,"discipline-admin-help");return;}
        UUID owner=resolve(args[1]);
        if(owner==null){plugin.messages().send(sender,"target-not-found","player",args[1]);return;}
        long now=System.currentTimeMillis();
        var sanction=plugin.store().sanction(owner);
        if(args[2].equalsIgnoreCase("history")) {
            plugin.peace().disciplineStatus(sender,owner);
            var events=sanction.incidents();var audit=sanction.audit();
            int pages=Math.max(1,Math.max((events.size()+19)/20,(audit.size()+9)/10));
            int page=1;
            if(args.length>3)try{page=Integer.parseInt(args[3]);}catch(NumberFormatException e){plugin.messages().send(sender,"discipline-admin-help");return;}
            if(page<1||page>pages){plugin.messages().send(sender,"discipline-admin-help");return;}
            plugin.messages().send(sender,"discipline-page","page",page,"pages",pages);
            int end=Math.max(0,events.size()-(page-1)*20);
            for(var v:events.subList(Math.max(0,end-20),end))
                plugin.messages().send(sender,"discipline-history","id",v.id(),"time",GuildPeaceController.time(v.at()),
                        "victim",name(v.victim()),"kind",v.kind(),"peaceful",v.peaceful(),"forgiven",v.forgiven(),"source",v.source());
            int auditEnd=Math.max(0,audit.size()-(page-1)*10);
            for(var row:audit.subList(Math.max(0,auditEnd-10),auditEnd))
                plugin.messages().send(sender,"discipline-audit","time",GuildPeaceController.time(row.at()),
                        "actor",row.actor(),"action",row.action(),"reason",row.reason());
            return;
        }
        boolean forgive=args[2].equalsIgnoreCase("forgive");
        boolean unbrand=args[2].equalsIgnoreCase("unbrand");
        int confirmation=forgive?4:3;
        if((!forgive&&!unbrand)||args.length<=confirmation+1||!args[confirmation].equalsIgnoreCase("confirm")) {
            plugin.messages().send(sender,"discipline-admin-help");return;
        }
        UUID incident=null;
        if(forgive)try{incident=UUID.fromString(args[3]);}catch(IllegalArgumentException e){plugin.messages().send(sender,"discipline-no-incident");return;}
        String reason=String.join(" ",Arrays.copyOfRange(args,confirmation+1,args.length));
        if(reason.isBlank()||reason.length()>160||reason.chars().anyMatch(Character::isISOControl)) {
            plugin.messages().send(sender,"discipline-reason");return;
        }
        if(plugin.payments().paused()){plugin.messages().send(sender,"finance-paused");return;}
        UUID id=incident;
        String actor=sender instanceof Player p?p.getUniqueId().toString():"console";
        GuildResult result=plugin.store().transaction(copy->forgive
                ?new GuildPeaceService(copy).forgive(owner,id,now,actor,reason)
                :new GuildPeaceService(copy).unbrand(owner,now,actor,reason),GuildResult.fail("finance-paused"));
        plugin.messages().send(sender,result.key(),result.array());
        if(result.ok()) {
            plugin.getLogger().info("Мирный режим: "+actor+" / "+args[2]+" / "+owner+" / "+id+" / "+reason);
            plugin.peace().deliverNotices(owner);
        }
    }
    private static UUID resolve(String value) {
        try{return UUID.fromString(value);}catch(IllegalArgumentException ignored){ }
        Player online=Bukkit.getPlayerExact(value);
        if(online!=null)return online.getUniqueId();
        var cached=Bukkit.getOfflinePlayerIfCached(value);
        return cached==null?null:cached.getUniqueId();
    }
    private static String name(UUID id) {
        var player=Bukkit.getOfflinePlayer(id);
        return player.getName()==null?id.toString():player.getName();
    }
}
