package ru.cherdakmd.chrdkauction;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.logging.Level;

final class AuctionCommands implements CommandExecutor,TabCompleter {
    private final AuctionPlugin plugin; private final AuctionService service; private final AuctionGui gui;
    AuctionCommands(AuctionPlugin plugin,AuctionService service,AuctionGui gui) { this.plugin=plugin;this.service=service;this.gui=gui; }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        String sub=args.length==0?"":args[0].toLowerCase(Locale.ROOT);
        if (command.getName().equalsIgnoreCase("auctionadmin")) {
            if (!sender.hasPermission("chrdkauction.admin")) { plugin.messages().send(sender,"auction.permission");return true; }
            switch(sub) {
                case "reload" -> {
                    try { plugin.reloadAuction(); plugin.messages().send(sender,"auction.reloaded"); }
                    catch(Exception e) { plugin.getLogger().log(Level.SEVERE,"Auction reload rejected",e);plugin.messages().send(sender,"auction.error"); }
                }
                case "status" -> {
                    var pending=service.store.pending();
                    plugin.messages().send(sender,"auction.status","count",service.store.lots().size(),"pending",
                            pending==null?"нет":pending.operation()+" / UUID "+pending.player());
                }
                case "recover" -> {
                    if (service.recoverWithoutPlayer()) { plugin.messages().send(sender,"auction.recovered"); break; }
                    var pending=service.store.pending();
                    Player p=pending==null?null:Bukkit.getPlayer(pending.player());
                    if (p==null || !plugin.api().isAuthenticated(p)) plugin.messages().send(sender,"auction.recover-offline");
                    else plugin.messages().send(sender,service.recover(p)?"auction.recovered":"auction.recovery");
                }
                default -> plugin.messages().send(sender,"auction.admin-usage");
            }
            return true;
        }
        if (!(sender instanceof Player p)) { plugin.messages().send(sender,"auction.player-only");return true; }
        if (!service.authenticated(p)) return true;
        try {
            switch(sub) {
                case "" -> gui.open(p,AuctionGui.Mode.MARKET,0);
                case "mine" -> gui.open(p,AuctionGui.Mode.MINE,0);
                case "mail" -> gui.open(p,AuctionGui.Mode.MAIL,0);
                case "money" -> service.money(p);
                case "sell" -> { if(args.length==2) service.sell(p,Integer.parseInt(args[1]));else service.tell(p,"auction.usage"); }
                case "buy" -> { if(args.length==3 && args[2].equalsIgnoreCase("confirm")) service.buy(p,Long.parseLong(args[1]));else service.tell(p,"auction.usage"); }
                case "cancel" -> { if(args.length==2) service.cancel(p,Long.parseLong(args[1]));else service.tell(p,"auction.usage"); }
                case "claim" -> { if(args.length==2) service.claim(p,Long.parseLong(args[1]));else service.tell(p,"auction.usage"); }
                default -> service.tell(p,"auction.usage");
            }
        } catch (NumberFormatException e) { service.tell(p,"auction.invalid"); }
        catch (Exception e) { plugin.getLogger().log(Level.SEVERE,"Auction command failed",e);service.tell(p,"auction.error"); }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        boolean admin=command.getName().equalsIgnoreCase("auctionadmin");
        if (!sender.hasPermission(admin?"chrdkauction.admin":"chrdkauction.use")) return List.of();
        if(args.length==1) {
            String prefix=args[0].toLowerCase(Locale.ROOT);
            return (admin?List.of("reload","status","recover"):List.of("sell","buy","cancel","mine","mail","claim","money"))
                    .stream().filter(s->s.startsWith(prefix)).toList();
        }
        if (!admin && args.length==3 && args[0].equalsIgnoreCase("buy") && "confirm".startsWith(args[2].toLowerCase(Locale.ROOT))) return List.of("confirm");
        return List.of();
    }
}
