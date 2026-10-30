package ru.cherdakmd.chrdkregister;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.FormattedCommandAlias;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Защита уровня Bukkit: не заменяет firewall, пароль, TOTP и корректные permissions. */
final class ServerGuard implements Listener {
    private record Reservation(String address,long since,boolean returning) { }
    private static final long RESERVATION_NANOS=15_000_000_000L;
    private static final class Session {
        GuardRates.Bucket normal, unauthenticated;
        Player player;
        String address;
        long unauthSince;
        boolean waiting=true, returning, ending;
        Session(GuardSettings s,long now,Player player,String address,boolean returning) {
            reset(s,now);unauthSince=now;this.player=player;this.address=address;this.returning=returning;
        }
        void reset(GuardSettings s,long now) {
            normal=new GuardRates.Bucket(s.commandBurst(),s.commandsPerSecond(),now);
            unauthenticated=new GuardRates.Bucket(s.unauthCommandBurst(),s.unauthCommandsPerSecond(),now);
        }
        GuardDefense.Key key() { return new GuardDefense.Key(player.getUniqueId(),address); }
    }
    private final Supplier<Messages> messages;
    private final Predicate<Player> authenticated;
    private final Function<String,Command> commands;
    private final Supplier<Map<String,String[]>> aliases;
    private final Consumer<String> warning;
    private final LongSupplier clock;
    private final IntSupplier maxPlayers;
    // Обе коллекции ниже доступны только из главного потока. Резерв связан с соединением, не только UUID.
    private final Map<UUID,Session> sessions=new HashMap<>();
    private final Map<Player,Reservation> reservations=new IdentityHashMap<>();
    private final AtomicLong loginRejected=new AtomicLong(), spamKicks=new AtomicLong(),
            blockedCommands=new AtomicLong(), slotKicks=new AtomicLong(), timeoutKicks=new AtomicLong();
    private final AtomicLong[] reasons=java.util.stream.Stream.of(GuardRates.Admission.values())
            .map(reason -> new AtomicLong()).toArray(AtomicLong[]::new);
    private final GuardDefense defense;
    private volatile GuardSettings settings;
    private long lastReport, reported;
    private boolean reportedAttack;

    ServerGuard(GuardSettings settings,Supplier<Messages> messages,Predicate<Player> authenticated,
                Function<String,Command> commands,Supplier<Map<String,String[]>> aliases,
                Consumer<String> warning,LongSupplier clock) {
        this(settings,messages,authenticated,commands,aliases,warning,clock,()->Integer.MAX_VALUE);
    }
    ServerGuard(GuardSettings settings,Supplier<Messages> messages,Predicate<Player> authenticated,
                Function<String,Command> commands,Supplier<Map<String,String[]>> aliases,
                Consumer<String> warning,LongSupplier clock,IntSupplier maxPlayers) {
        this.messages=messages;this.authenticated=authenticated;this.commands=commands;
        this.aliases=aliases;this.warning=warning;this.clock=clock;this.maxPlayers=maxPlayers;
        this.settings=settings;lastReport=clock.getAsLong();defense=new GuardDefense(settings,lastReport);
    }
    private Messages messages() { return messages.get(); }
    void reload(GuardSettings settings) {
        long now=clock.getAsLong();defense.configure(settings,now);this.settings=settings;
        sessions.values().forEach(session -> session.reset(settings,now));
    }
    private static String address(Player player) {
        var socket=player.getAddress();
        return socket==null || socket.getAddress()==null ? "unknown" : socket.getAddress().getHostAddress();
    }
    private Session session(Player player,long now) {
        return sessions.computeIfAbsent(player.getUniqueId(),id -> new Session(settings,now,player,address(player),false));
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if(!settings.enabled() || event.getLoginResult()!=AsyncPlayerPreLoginEvent.Result.ALLOWED)return;
        long now=clock.getAsLong();
        var key=new GuardDefense.Key(event.getUniqueId(),event.getAddress().getHostAddress());
        var result=defense.enter(key,now);
        if(result==GuardRates.Admission.ALLOW)return;
        loginRejected.incrementAndGet();reasons[result.ordinal()].incrementAndGet();
        if(result==GuardRates.Admission.COOLDOWN)
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,messages().get("security.cooldown","seconds",defense.cooldownSeconds(key,now)));
        else event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,messages().get("security.login-rate"));
    }

    private void cleanReservations(long now) {
        reservations.values().removeIf(r -> now-r.since()>=RESERVATION_NANOS);
    }
    private int pendingLimit(int authed) {
        long capacity=Math.max(1L,(long)maxPlayers.getAsInt()-authed-settings.defense().reservedSlots());
        return (int)Math.min(settings.unauthGlobal(),capacity);
    }
    private int ordinaryLimit(int total) {
        return total-Math.min(settings.defense().reservedSlots(),total-1);
    }

    /** Последний допуск до join/загрузки игрока в мир. Не трогаем ранний handshake и forwarding. */
    @EventHandler(priority=EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        if(!settings.enabled() || event.getResult()!=PlayerLoginEvent.Result.ALLOWED)return;
        long now=clock.getAsLong();cleanReservations(now);
        Player player=event.getPlayer();String ip=event.getAddress().getHostAddress();
        var key=new GuardDefense.Key(player.getUniqueId(),ip);
        long cooldown=defense.cooldownSeconds(key,now);
        if(cooldown>0) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,messages().get("security.cooldown","seconds",cooldown));
            loginRejected.incrementAndGet();reasons[GuardRates.Admission.COOLDOWN.ordinal()].incrementAndGet();return;
        }
        int pending=0, ordinary=0, perIp=0, authed=0;
        for(Session session:sessions.values()) {
            if(authenticated.test(session.player)) { authed++;continue; }
            pending++;if(!session.returning)ordinary++;if(session.address.equals(ip))perIp++;
        }
        for(var entry:reservations.entrySet()) {
            if(entry.getKey()==player)continue;
            var r=entry.getValue();pending++;if(!r.returning())ordinary++;if(r.address().equals(ip))perIp++;
        }
        int limit=pendingLimit(authed);
        boolean returning=defense.returning(key,now);
        if(pending>=limit || (!returning && ordinary>=ordinaryLimit(limit)) || perIp>=settings.unauthPerIp()) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,messages().get("security.login-slots"));
            slotKicks.incrementAndGet();defense.slotRejected(ip,now);return;
        }
        reservations.put(player,new Reservation(ip,now,returning));
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onLoginFinal(PlayerLoginEvent event) {
        if(event.getResult()!=PlayerLoginEvent.Result.ALLOWED)reservations.remove(event.getPlayer());
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player=event.getPlayer();Reservation r=reservations.remove(player);
        if(!settings.enabled())return;
        long now=clock.getAsLong();
        var session=new Session(settings,now,player,r==null?address(player):r.address(),
                r==null?defense.returning(new GuardDefense.Key(player.getUniqueId(),address(player)),now):r.returning());
        if(r!=null)session.unauthSince=r.since();
        sessions.put(player.getUniqueId(),session);
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        reservations.remove(event.getPlayer());
        Session session=sessions.get(event.getPlayer().getUniqueId());
        if(session!=null && session.player==event.getPlayer())sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        GuardSettings s=settings;if(!s.enabled())return;
        Player player=event.getPlayer();long now=clock.getAsLong();
        Session existing=sessions.get(player.getUniqueId());
        if(existing!=null && existing.player!=player) { event.setCancelled(true);return; }
        Session session=session(player,now);
        if(session.ending) { event.setCancelled(true);return; }
        boolean authed=authenticated.test(player);
        // Уже отменённые команды тоже расходуют лимит, но один kick даёт лишь один strike за соединение.
        if(event.getMessage().length()>s.maxCommandLength() || !session.normal.take(now)
                || (!authed && !session.unauthenticated.take(now))) {
            event.setCancelled(true);session.ending=true;spamKicks.incrementAndGet();defense.strike(session.key(),now);
            player.kick(messages().get("security.command-spam"));return;
        }
        if(GuardPolicy.consoleOnly(event.getMessage(),s.consoleOnly(),this::resolve)) {
            blockedCommands.incrementAndGet();
            if(!event.isCancelled())messages().send(player,"security.console-only");event.setCancelled(true);
        }
    }
    private GuardPolicy.Target resolve(String root) {
        Command command=commands.apply(root);
        String canonical=command==null?GuardPolicy.label(root):command.getName();String[] expansion=null;
        for(var entry:aliases.get().entrySet()) {
            if(GuardPolicy.token(entry.getKey()).equals(GuardPolicy.token(canonical))) { expansion=entry.getValue();break; }
        }
        if(expansion==null && command instanceof FormattedCommandAlias)
            return new GuardPolicy.Target(canonical,List.of("$unknown-alias"));
        return new GuardPolicy.Target(canonical,expansion==null?List.of():List.of(expansion));
    }

    /** Резерв до join — основной барьер; pulse контролирует logout, сроки и смену настроек. */
    void pulse(Collection<? extends Player> online) {
        GuardSettings s=settings;if(!s.enabled())return;
        long now=clock.getAsLong();cleanReservations(now);
        var connected=new java.util.HashSet<UUID>();var waiting=new ArrayList<Session>();int authed=0;
        for(Player player:online) {
            connected.add(player.getUniqueId());Session session=session(player,now);
            if(authenticated.test(player)) {
                authed++;session.waiting=false;defense.authenticated(session.key(),now);continue;
            }
            if(!session.waiting) {
                session.waiting=true;session.unauthSince=now;session.returning=defense.returning(session.key(),now);
            }
            waiting.add(session);
        }
        sessions.keySet().removeIf(id -> !connected.contains(id));
        waiting.sort(Comparator.comparing((Session session)->!session.returning).thenComparingLong(session->session.unauthSince));
        int pending=0, ordinary=0, limit=pendingLimit(authed);Map<String,Integer> perIp=new HashMap<>();
        for(Session session:waiting) {
            if(session.ending)continue;
            if(now-session.unauthSince>=s.loginSeconds()*1_000_000_000L) {
                session.ending=true;timeoutKicks.incrementAndGet();session.player.kick(messages().get("security.login-timeout"));continue;
            }
            int ipCount=perIp.getOrDefault(session.address,0);
            if(pending>=limit || (!session.returning && ordinary>=ordinaryLimit(limit)) || ipCount>=s.unauthPerIp()) {
                session.ending=true;slotKicks.incrementAndGet();defense.slotRejected(session.address,now);
                session.player.kick(messages().get("security.login-slots"));continue;
            }
            pending++;if(!session.returning)ordinary++;perIp.put(session.address,ipCount+1);
        }
        var snapshot=defense.snapshot(now);
        if(now-lastReport>=30_000_000_000L) {
            long total=loginRejected.get()+spamKicks.get()+blockedCommands.get()+slotKicks.get()+timeoutKicks.get();
            if(total!=reported || snapshot.attack()!=reportedAttack)
                warning.accept("Защита CHRDK: повышенный режим="+snapshot.attack()+", отказов за интервал="+(total-reported)
                        +", входы="+loginRejected.get()+", спам="+spamKicks.get()+", команды="+blockedCommands.get()
                        +", слоты="+slotKicks.get()+", таймауты="+timeoutKicks.get()+" (с запуска)");
            lastReport=now;reported=total;reportedAttack=snapshot.attack();
        }
    }

    boolean status(CommandSender sender) {
        if(!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            messages().send(sender,"security.status-console");return true;
        }
        GuardSettings s=settings;var snapshot=defense.snapshot(clock.getAsLong());
        int authed=0;for(Session session:sessions.values())if(authenticated.test(session.player))authed++;
        int effective=pendingLimit(authed);
        messages().send(sender,"security.status","enabled",s.enabled(),"addresses",snapshot.addresses(),
                "logins",loginRejected.get(),"spam",spamKicks.get(),"blocked",blockedCommands.get(),
                "slots",slotKicks.get(),"timeouts",timeoutKicks.get());
        messages().send(sender,"security.defense-status","attack",snapshot.attack(),"history",snapshot.history(),
                "cooling",snapshot.cooling(),"reservations",reservations.size(),"ip",reasons[GuardRates.Admission.IP_RATE.ordinal()].get(),
                "global",reasons[GuardRates.Admission.GLOBAL_RATE.ordinal()].get(),"capacity",reasons[GuardRates.Admission.CAPACITY.ordinal()].get(),
                "restricted",reasons[GuardRates.Admission.ATTACK_RATE.ordinal()].get(),"cooldown",reasons[GuardRates.Admission.COOLDOWN.ordinal()].get());
        messages().send(sender,"security.rate-status","ip_burst",s.ipBurst(),"ip_seconds",s.ipRefillSeconds(),
                "global_burst",s.globalBurst(),"global_rate",s.globalPerSecond(),"pending",s.unauthGlobal(),"per_ip",s.unauthPerIp(),
                "reserved",s.defense().reservedSlots(),"seconds",s.loginSeconds(),"attack_burst",s.defense().attackBurst(),
                "attack_rate",s.defense().attackPerSecond(),"command_burst",s.commandBurst(),"command_rate",s.commandsPerSecond(),
                "pre_burst",s.unauthCommandBurst(),"pre_rate",s.unauthCommandsPerSecond(),"length",s.maxCommandLength(),
                "effective",effective,"ordinary",ordinaryLimit(effective),"recovery_burst",s.defense().recoveryBurst(),
                "recovery_rate",s.defense().recoveryPerSecond());
        messages().send(sender,"security.policy-status","automatic",s.defense().automatic(),"windows",s.defense().attackWindows(),
                "rejects",s.defense().attackRejects(),"addresses",s.defense().attackAddresses(),"quiet",s.defense().quietSeconds(),
                "strikes",s.defense().strikes(),"window",s.defense().strikeSeconds(),"initial",s.defense().cooldownSeconds(),
                "max",s.defense().maxCooldownSeconds(),"recent",s.defense().recentSeconds(),"capacity",s.maxAddresses());
        return true;
    }
}
