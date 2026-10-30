package ru.cherdakmd.chrdkregister;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Защита уровня Bukkit: не заменяет firewall, пароль, TOTP и корректные permissions. */
final class ServerGuard implements Listener {
    private record State(GuardSettings settings, GuardRates rates) { }
    private static final class Session {
        GuardRates.Bucket normal, unauthenticated;
        long unauthSince;
        boolean waiting=true;
        Session(GuardSettings s,long now) { reset(s,now); unauthSince=now; }
        void reset(GuardSettings s,long now) {
            normal=new GuardRates.Bucket(s.commandBurst(),s.commandsPerSecond(),now);
            unauthenticated=new GuardRates.Bucket(s.unauthCommandBurst(),s.unauthCommandsPerSecond(),now);
        }
    }
    private final Supplier<Messages> messages;
    private final Predicate<Player> authenticated;
    private final Function<String,Command> commands;
    private final Supplier<Map<String,String[]>> aliases;
    private final Consumer<String> warning;
    private final LongSupplier clock;
    private final Map<UUID,Session> sessions=new HashMap<>(); // Только главный поток.
    private final AtomicLong loginRejected=new AtomicLong(), spamKicks=new AtomicLong(),
            blockedCommands=new AtomicLong(), slotKicks=new AtomicLong(), timeoutKicks=new AtomicLong();
    private volatile State state;
    private long lastReport, reported;

    ServerGuard(GuardSettings settings, Supplier<Messages> messages, Predicate<Player> authenticated,
                Function<String,Command> commands, Supplier<Map<String,String[]>> aliases,
                Consumer<String> warning, LongSupplier clock) {
        this.messages=messages; this.authenticated=authenticated; this.commands=commands;
        this.aliases=aliases; this.warning=warning; this.clock=clock;
        lastReport=clock.getAsLong(); reload(settings);
    }

    void reload(GuardSettings settings) {
        long now=clock.getAsLong();
        state=new State(settings,new GuardRates(settings,now));
        sessions.values().forEach(session -> session.reset(settings,now));
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        State current=state;
        if(!current.settings.enabled() || event.getLoginResult()!=AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        if(current.rates.enter(event.getAddress().getHostAddress(),clock.getAsLong())!=GuardRates.Admission.ALLOW) {
            loginRejected.incrementAndGet();
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,messages.get().get("security.login-rate"));
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        if(state.settings.enabled()) sessions.put(event.getPlayer().getUniqueId(),new Session(state.settings,clock.getAsLong()));
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { sessions.remove(event.getPlayer().getUniqueId()); }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        GuardSettings s=state.settings;
        if(!s.enabled()) return;
        Player player=event.getPlayer();
        long now=clock.getAsLong();
        Session session=sessions.computeIfAbsent(player.getUniqueId(),id -> new Session(s,now));
        boolean authed=authenticated.test(player);
        // Считаем и уже отменённые команды: до входа спамер не получает бесплатный поток.
        if(event.getMessage().length()>s.maxCommandLength() || !session.normal.take(now)
                || (!authed && !session.unauthenticated.take(now))) {
            event.setCancelled(true); spamKicks.incrementAndGet();
            player.kick(messages.get().get("security.command-spam"));
            return;
        }
        if(GuardPolicy.consoleOnly(event.getMessage(),s.consoleOnly(),this::resolve)) {
            blockedCommands.incrementAndGet();
            if(!event.isCancelled()) messages.get().send(player,"security.console-only");
            event.setCancelled(true);
        }
    }

    private GuardPolicy.Target resolve(String root) {
        Command command=commands.apply(root);
        String canonical=command==null ? GuardPolicy.label(root) : command.getName();
        String[] expansion=aliases.get().get(GuardPolicy.label(canonical));
        return new GuardPolicy.Target(canonical,expansion==null ? List.of() : List.of(expansion));
    }

    /** Один проход в секунду; квоты считаются только по ещё не вошедшим игрокам. */
    void pulse(Collection<? extends Player> online) {
        GuardSettings s=state.settings;
        if(!s.enabled()) return;
        long now=clock.getAsLong();
        int pending=0;
        Map<String,Integer> pendingByAddress=new HashMap<>();
        for(Player player:online) {
            Session session=sessions.computeIfAbsent(player.getUniqueId(),id -> new Session(s,now));
            if(authenticated.test(player)) { session.waiting=false; continue; }
            if(!session.waiting) { session.waiting=true;session.unauthSince=now; }
            if(now-session.unauthSince>=s.loginSeconds()*1_000_000_000L) {
                timeoutKicks.incrementAndGet();player.kick(messages.get().get("security.login-timeout"));continue;
            }
            String ip=player.getAddress()==null ? "unknown" : player.getAddress().getAddress().getHostAddress();
            int perIp=pendingByAddress.getOrDefault(ip,0);
            if(pending>=s.unauthGlobal() || perIp>=s.unauthPerIp()) {
                slotKicks.incrementAndGet();player.kick(messages.get().get("security.login-slots"));continue;
            }
            pending++;pendingByAddress.put(ip,perIp+1);
        }
        if(now-lastReport>=30_000_000_000L) {
            long total=loginRejected.get()+spamKicks.get()+blockedCommands.get()+slotKicks.get()+timeoutKicks.get();
            if(total!=reported) warning.accept("Защита CHRDK: отказов за интервал="+(total-reported)
                    +", входы="+loginRejected.get()+", спам="+spamKicks.get()+", команды="+blockedCommands.get()
                    +", слоты="+slotKicks.get()+", таймауты="+timeoutKicks.get()+" (счётчики с запуска)");
            lastReport=now;reported=total;
        }
    }

    boolean status(CommandSender sender) {
        if(!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            messages.get().send(sender,"security.status-console");return true;
        }
        GuardSettings s=state.settings;
        messages.get().send(sender,"security.status","enabled",s.enabled(),"addresses",state.rates.tracked(),
                "logins",loginRejected.get(),"spam",spamKicks.get(),"blocked",blockedCommands.get(),
                "slots",slotKicks.get(),"timeouts",timeoutKicks.get());
        return true;
    }
}
