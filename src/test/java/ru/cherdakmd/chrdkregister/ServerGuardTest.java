package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ServerGuardTest {
    private final Messages texts=Messages.from(getClass().getResourceAsStream("/messages.yml"),null,Logger.getAnonymousLogger());
    private final AtomicLong now=new AtomicLong();
    private final Set<UUID> authenticated=new HashSet<>();
    private final List<String> warnings=new ArrayList<>();
    private final Map<String,Command> registry=new HashMap<>();
    private final Map<String,String[]> aliases=new HashMap<>();
    private final YamlConfiguration config=new YamlConfiguration();
    private record Client(Player player,AtomicInteger kicks,List<Component> messages) { }
    private Client client(String ip) { return client(ip,UUID.randomUUID()); }
    private Client client(String ip,UUID id) {
        var kicks=new AtomicInteger();var sent=new ArrayList<Component>();
        Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(p,m,a)->switch(m.getName()) {
            case "getUniqueId"->id;
            case "getAddress"->new InetSocketAddress(ip,25565);
            case "kick"->{kicks.incrementAndGet();yield null;}
            case "sendMessage"->{if(a.length==1 && a[0] instanceof Component text)sent.add(text);yield null;}
            case "isOp", "hasPermission"->true;
            case "toString"->"GuardTestPlayer";
            case "hashCode"->System.identityHashCode(p);
            case "equals"->p==a[0];
            default->throw new AssertionError("Неожиданный вызов "+m);
        });
        return new Client(player,kicks,sent);
    }
    private ServerGuard guard() {
        return new ServerGuard(GuardSettings.parse(config),()->texts,p->authenticated.contains(p.getUniqueId()),registry::get,()->aliases,warnings::add,now::get);
    }
    private PlayerCommandPreprocessEvent command(Client c,String text,boolean cancelled) {
        var e=new PlayerCommandPreprocessEvent(c.player,text,new HashSet<>());e.setCancelled(cancelled);return e;
    }
    private AsyncPlayerPreLoginEvent login(String ip) throws Exception { return login(ip,UUID.randomUUID()); }
    private AsyncPlayerPreLoginEvent login(String ip,UUID id) throws Exception {
        var profile=(com.destroystokyo.paper.profile.PlayerProfile)Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class<?>[]{com.destroystokyo.paper.profile.PlayerProfile.class},(p,m,a)->m.getName().equals("getId")?id:null);
        return new AsyncPlayerPreLoginEvent("Player",InetAddress.getByName(ip),id,false,profile);
    }
    @Test void authenticatedOpStillCannotUseAttackCommands() {
        var guard=guard();var client=client("192.0.2.1");authenticated.add(client.player.getUniqueId());
        for(String raw:List.of("/minecraft:fill 0 0 0 1 1 1 air","/execute run op Player","/gamerule random_tick_speed 2147483647","/title @a times 2s 9999d 9999d")) {
            var e=command(client,raw,false);guard.onCommand(e);assertTrue(e.isCancelled());
        }
        assertEquals(0,client.kicks.get());assertEquals(4,client.messages.size());
    }
    @Test void cancelledPreAuthCommandsStillConsumeSpamQuota() {
        config.set("security.unauthenticated-command-burst",2);
        var guard=guard();var client=client("192.0.2.1");
        for(int i=0;i<3;i++)guard.onCommand(command(client,"/login private-password",true));
        assertEquals(1,client.kicks.get());assertTrue(client.messages.isEmpty());assertTrue(warnings.isEmpty());
    }
    @Test void normalLoginAndGameplayCommandsAreNotCancelled() {
        var guard=guard();var client=client("192.0.2.1");
        for(String raw:List.of("/login secret","/2fa 123456","/shop sellall","/claim menu")) {
            var e=command(client,raw,false);guard.onCommand(e);assertFalse(e.isCancelled());
        }
        assertEquals(0,client.kicks.get());
    }
    @Test void longCommandsAreRejectedBeforeResolution() {
        var guard=guard();var client=client("192.0.2.1");
        var e=command(client,"/msg friend "+"x".repeat(3000),false);guard.onCommand(e);
        assertTrue(e.isCancelled());assertEquals(1,client.kicks.get());
    }
    @Test void actualCommandRegistryAndConfiguredAliasesAreUsed() {
        registry.put("alias",new Command("fill") { public boolean execute(CommandSender s,String l,String[] a){throw new AssertionError();} });
        aliases.put("ReNaMe",new String[]{"minecraft:execute run say hello"});
        var guard=guard();var client=client("192.0.2.1");
        for(String raw:List.of("/alias","/rename")){var e=command(client,raw,false);guard.onCommand(e);assertTrue(e.isCancelled());}
    }
    @Test void preLoginThrottlesReconnectsButNeverOverridesAnotherPluginsBan() throws Exception {
        config.set("security.ip-burst",1);var guard=guard();
        var first=login("192.0.2.1");guard.onPreLogin(first);assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED,first.getLoginResult());
        var next=login("192.0.2.1");guard.onPreLogin(next);assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,next.getLoginResult());
        var banned=login("192.0.2.2");banned.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,Component.text("ban"));
        guard.onPreLogin(banned);assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,banned.getLoginResult());
        now.set(3_000_000_001L);var retry=login("192.0.2.1");guard.onPreLogin(retry);assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED,retry.getLoginResult());
    }
    @Test void pendingLimitsIgnoreAuthenticatedPlayersSharingAnAddress() {
        config.set("security.unauthenticated-per-ip",1);var guard=guard();
        var authed=client("192.0.2.1");var first=client("192.0.2.1");var second=client("192.0.2.1");
        authenticated.add(authed.player.getUniqueId());
        guard.pulse(List.of(authed.player,first.player,second.player));
        assertEquals(0,authed.kicks.get());assertEquals(0,first.kicks.get());assertEquals(1,second.kicks.get());
    }
    @Test void distributedUnauthenticatedSlotsAreAlsoBounded() {
        config.set("security.unauthenticated-global",1);var guard=guard();
        var first=client("192.0.2.1");var second=client("192.0.2.2");guard.pulse(List.of(first.player,second.player));
        assertEquals(0,first.kicks.get());assertEquals(1,second.kicks.get());
    }
    @Test void deadlineSurvivesReloadAndLogoutStartsANewDeadline() {
        var guard=guard();var client=client("192.0.2.1");
        guard.onJoin(new PlayerJoinEvent(client.player,Component.empty()));
        now.set(119_000_000_000L);guard.reload(GuardSettings.parse(config));
        now.set(120_000_000_000L);guard.pulse(List.of(client.player));assertEquals(1,client.kicks.get());
        guard.onQuit(new PlayerQuitEvent(client.player,Component.empty()));
        guard.onJoin(new PlayerJoinEvent(client.player,Component.empty()));
        authenticated.add(client.player.getUniqueId());guard.pulse(List.of(client.player));
        authenticated.clear();now.set(500_000_000_000L);guard.pulse(List.of(client.player));assertEquals(1,client.kicks.get());
        now.set(620_000_000_000L);guard.pulse(List.of(client.player));assertEquals(2,client.kicks.get());
        guard.onQuit(new PlayerQuitEvent(client.player,Component.empty()));
    }
    @Test void summaryIsRateLimitedAndContainsNoCommandOrPassword() {
        var guard=guard();var client=client("192.0.2.1");
        guard.onCommand(command(client,"/fill PRIVATE_SECRET",false));
        now.set(30_000_000_000L);guard.pulse(List.of(client.player));
        now.set(31_000_000_000L);guard.pulse(List.of(client.player));assertEquals(1,warnings.size());
        assertFalse(warnings.getFirst().contains("PRIVATE_SECRET"));assertFalse(warnings.getFirst().contains("192.0.2.1"));
    }
    @Test void statusIsOnlyForConsoleOrRconNotEvenAnOpPlayer() {
        var guard=guard();var client=client("192.0.2.1");assertTrue(guard.status(client.player));
        assertEquals(1,client.messages.size());
        for(Class<?> type:List.of(ConsoleCommandSender.class,RemoteConsoleCommandSender.class)) {
            var sent=new AtomicInteger();
            var sender=(CommandSender)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{type},(p,m,a)->{
                if(m.getName().equals("sendMessage")){sent.incrementAndGet();return null;}
                throw new AssertionError("Неожиданный вызов "+m);
            });
            assertTrue(guard.status(sender));assertEquals(4,sent.get());
        }
    }
    @Test void disabledModeLeavesEventsUntouched() throws Exception {
        config.set("security.enabled",false);var guard=guard();var client=client("192.0.2.1");
        var e=command(client,"/fill "+"x".repeat(3000),false);guard.onCommand(e);assertFalse(e.isCancelled());
        var login=login("192.0.2.1");guard.onPreLogin(login);assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED,login.getLoginResult());
        guard.pulse(List.of(client.player));assertEquals(0,client.kicks.get());
    }
    private PlayerLoginEvent gate(Client client) {
        return new PlayerLoginEvent(client.player,"localhost",client.player.getAddress().getAddress());
    }
    @Test void admissionReservesBeforeJoinAndReleasesAnotherPluginsDenial() {
        config.set("security.unauthenticated-global",2);config.set("security.defense.reserved-slots",0);
        var guard=guard();var first=client("192.0.2.1");var second=client("192.0.2.2");var third=client("192.0.2.3");
        var a=gate(first);var b=gate(second);var c=gate(third);guard.onLogin(a);guard.onLogin(b);guard.onLogin(c);
        assertEquals(PlayerLoginEvent.Result.ALLOWED,a.getResult());assertEquals(PlayerLoginEvent.Result.ALLOWED,b.getResult());
        assertEquals(PlayerLoginEvent.Result.KICK_OTHER,c.getResult());
        a.disallow(PlayerLoginEvent.Result.KICK_BANNED,Component.text("other ban"));guard.onLoginFinal(a);
        var retry=gate(third);guard.onLogin(retry);assertEquals(PlayerLoginEvent.Result.ALLOWED,retry.getResult());
    }
    @Test void duplicateUuidConnectionsDoNotOverwriteEachOthersReservations() {
        config.set("security.unauthenticated-global",2);config.set("security.defense.reserved-slots",0);
        var guard=guard();UUID id=UUID.randomUUID();
        var a=gate(client("192.0.2.1",id));var b=gate(client("192.0.2.2",id));var c=gate(client("192.0.2.3",id));
        guard.onLogin(a);guard.onLogin(b);guard.onLogin(c);assertEquals(PlayerLoginEvent.Result.KICK_OTHER,c.getResult());
    }
    @Test void abandonedAdmissionExpiresAndJoinDoesNotRestartTheAuthDeadline() {
        config.set("security.unauthenticated-global",1);var guard=guard();var first=client("192.0.2.1");var next=client("192.0.2.2");
        guard.onLogin(gate(first));var denied=gate(next);guard.onLogin(denied);assertEquals(PlayerLoginEvent.Result.KICK_OTHER,denied.getResult());
        now.set(15_000_000_000L);var accepted=gate(next);guard.onLogin(accepted);assertEquals(PlayerLoginEvent.Result.ALLOWED,accepted.getResult());
        now.set(20_000_000_000L);guard.onJoin(new PlayerJoinEvent(next.player,Component.empty()));
        now.set(135_000_000_000L);guard.pulse(List.of(next.player));assertEquals(1,next.kicks.get());
    }
    @Test void physicalCapacityAndReturningReserveAreEnforcedWithoutAuthBypass() {
        config.set("security.defense.reserved-slots",2);
        var guard=new ServerGuard(GuardSettings.parse(config),()->texts,p->authenticated.contains(p.getUniqueId()),registry::get,()->aliases,warnings::add,now::get,()->6);
        var known=client("192.0.2.10");authenticated.add(known.player.getUniqueId());
        guard.onJoin(new PlayerJoinEvent(known.player,Component.empty()));guard.pulse(List.of(known.player));
        guard.onQuit(new PlayerQuitEvent(known.player,Component.empty()));authenticated.clear();
        for(int i=1;i<=2;i++){var e=gate(client("192.0.2."+i));guard.onLogin(e);assertEquals(PlayerLoginEvent.Result.ALLOWED,e.getResult());}
        var excess=gate(client("192.0.2.3"));guard.onLogin(excess);assertEquals(PlayerLoginEvent.Result.KICK_OTHER,excess.getResult());
        var returning=gate(known);guard.onLogin(returning);assertEquals(PlayerLoginEvent.Result.ALLOWED,returning.getResult());
        assertFalse(authenticated.contains(known.player.getUniqueId()));
        guard.onJoin(new PlayerJoinEvent(known.player,Component.empty()));
        now.set(120_000_000_000L);guard.pulse(List.of(known.player));assertEquals(1,known.kicks.get());
    }
    @Test void returningPriorityStillHonorsSharedIpPendingLimit() {
        config.set("security.unauthenticated-per-ip",1);var guard=guard();var known=client("192.0.2.1");
        authenticated.add(known.player.getUniqueId());guard.pulse(List.of(known.player));
        guard.onQuit(new PlayerQuitEvent(known.player,Component.empty()));authenticated.clear();
        guard.onLogin(gate(client("192.0.2.1")));var blocked=gate(known);guard.onLogin(blocked);
        assertEquals(PlayerLoginEvent.Result.KICK_OTHER,blocked.getResult());
    }
    @Test void alreadyDeniedLoginIsNeverAllowedAndOldQuitDoesNotEraseNewSession() {
        config.set("security.command-burst",1);var guard=guard();var old=client("192.0.2.1");var current=client("192.0.2.1",old.player.getUniqueId());
        var banned=gate(old);banned.disallow(PlayerLoginEvent.Result.KICK_BANNED,Component.text("ban"));guard.onLogin(banned);
        assertEquals(PlayerLoginEvent.Result.KICK_BANNED,banned.getResult());
        guard.onJoin(new PlayerJoinEvent(current.player,Component.empty()));
        for(int i=0;i<20;i++){var stale=command(old,"/login old",false);guard.onCommand(stale);assertTrue(stale.isCancelled());}
        assertEquals(0,current.kicks.get());assertEquals(0,old.kicks.get());
        guard.onCommand(command(current,"/login secret",true));
        guard.onQuit(new PlayerQuitEvent(old.player,Component.empty()));guard.onCommand(command(current,"/login secret",true));
        assertEquals(1,current.kicks.get());
    }
    @Test void queuedCommandsCountAsOneStrikePerConnectionAndReconnectsEscalate() throws Exception {
        config.set("security.command-burst",1);var guard=guard();UUID id=UUID.randomUUID();
        for(int connection=0;connection<3;connection++) {
            var offender=client("192.0.2.1",id);guard.onJoin(new PlayerJoinEvent(offender.player,Component.empty()));
            for(int packet=0;packet<20;packet++)guard.onCommand(command(offender,"/login secret",true));
            assertEquals(1,offender.kicks.get());guard.onQuit(new PlayerQuitEvent(offender.player,Component.empty()));
            var attempt=login("192.0.2.1",id);guard.onPreLogin(attempt);
            assertEquals(connection==2?AsyncPlayerPreLoginEvent.Result.KICK_OTHER:AsyncPlayerPreLoginEvent.Result.ALLOWED,attempt.getLoginResult());
        }
        var family=login("192.0.2.1");guard.onPreLogin(family);assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED,family.getLoginResult());
        guard.reload(GuardSettings.parse(config));var stillCooling=login("192.0.2.1",id);guard.onPreLogin(stillCooling);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,stillCooling.getLoginResult());
        now.set(30_000_000_000L);var retry=login("192.0.2.1",id);guard.onPreLogin(retry);assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED,retry.getLoginResult());
    }

}
