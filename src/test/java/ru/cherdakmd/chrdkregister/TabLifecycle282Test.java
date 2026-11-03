package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Реальный TabListService + память вместо клиента/сети и детерминированный Bukkit scheduler port.
 * Не Minecraft smoke/MSPT benchmark: проверяем lifecycle, доставку, границы задач/работы и очистку. */
class TabLifecycle282Test {
    @TempDir Path folder;
    static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    static final Messages TEXTS = Messages.from(TabLifecycle282Test.class.getResourceAsStream("/messages.yml"), null, Logger.getAnonymousLogger());

    static class Client {
        final UUID id = UUID.randomUUID();
        final String name;
        boolean online = true, licensed = true, allowed = true;
        long until = Long.MAX_VALUE;
        AccessManager.PlayerStage stage = AccessManager.PlayerStage.AUTHENTICATED;
        Component listName, header = Component.empty(), footer = Component.empty();
        int names, headers;
        final List<Component> messages = new ArrayList<>();
        final Player player;
        Client(String name) {
            this.name = name; listName = Component.text(name);
            player = (Player)Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p,m,a) -> switch(m.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> name;
                case "isOnline" -> online;
                case "hasPermission" -> allowed;
                case "playerListName" -> {
                    if (a == null || a.length == 0) yield listName;
                    names++; listName = a[0] == null ? Component.text(name) : (Component)a[0]; yield null;
                }
                case "playerListHeader" -> header;
                case "playerListFooter" -> footer;
                case "sendPlayerListHeaderAndFooter" -> { headers++;header=(Component)a[0];footer=(Component)a[1];yield null; }
                case "sendMessage" -> { if (a.length==1 && a[0] instanceof Component c) messages.add(c); yield null; }
                case "equals" -> p == a[0];
                case "hashCode" -> System.identityHashCode(p);
                case "toString" -> name;
                default -> throw new AssertionError("Unexpected client call: "+m);
            });
        }
        String text() { return PLAIN.serialize(listName); }
        String allMessages() { return messages.stream().map(PLAIN::serialize).reduce("", (a,b)->a+"\n"+b); }
    }
    static class Task implements BukkitTask {
        final int id;
        final Runnable callback;
        final int period;
        long next;
        boolean cancelled;
        Task(int id,Runnable callback,long next,int period) { this.id=id;this.callback=callback;this.next=next;this.period=period; }
        public int getTaskId() { return id; }
        public Plugin getOwner() { return null; }
        public boolean isSync() { return true; }
        public boolean isCancelled() { return cancelled; }
        public void cancel() { cancelled=true; }
    }
    static class World implements TabListService.Runtime {
        final YamlConfiguration config = new YamlConfiguration();
        final Map<String,Client> clients = new LinkedHashMap<>();
        final List<Task> tasks = new ArrayList<>();
        final TabListService service;
        int contexts, decoratedContexts, decorations;
        long tick;
        boolean frozen;
        World() {
            config.set("tablist.enabled",true); config.set("tablist.refresh-ticks",0);
            config.set("tablist.animation-refresh-ticks",5);config.set("tablist.dashboard-animation",false);
            config.set("tablist.header",List.of());config.set("tablist.footer",List.of());
            config.set("tablist.player-name-format","");config.set("tablist.pinned-names",List.of());
            service = new TabListService(this);
            service.setAnimationPredicate(name -> active(clients.get(name)));
            service.setNameDecorator(this::tagged);
        }
        Client add(String name) { var c=new Client(name);clients.put(name,c);return c; }
        boolean active(Client c) { return c!=null && c.licensed && c.until>now() && c.stage==AccessManager.PlayerStage.AUTHENTICATED; }
        String tagged(String name) {
            decorations++;
            return !active(clients.get(name)) ? Messages.escape(name)
                    : Messages.escape(name)+" <#"+Integer.toHexString(0x67e800+(frozen?0:(int)(tick%200)))+">[TAG]</#"
                    +Integer.toHexString(0x67e800+(frozen?0:(int)(tick%200)))+">";
        }
        public Collection<? extends Player> online() { return clients.values().stream().filter(c->c.online).map(c->c.player).toList(); }
        public Messages messages() { return TEXTS; }
        public YamlConfiguration config() { return config; }
        public AccessManager.PlayerStage stage(Player player) { return clients.get(player.getName()).stage; }
        public long now() { return 2_000_000L+tick*50; }
        public Scoreboard board() { return null; }
        public TabListService.TabContext context(Player player,long now,boolean withName) {
            contexts++; if(withName)decoratedContexts++;
            Client c=clients.get(player.getName());
            return new TabListService.TabContext(c.name,clients.size(),1000,20,50,"secret-world","активен","30д",30,
                    new PresentationContext("Ранг","Вход","Подсказка","включена",c.stage==AccessManager.PlayerStage.AUTHENTICATED?"/menu":""),
                    withName?tagged(c.name):Messages.escape(c.name),"",
                    TabDashboard.frame(TEXTS,now,c.stage==AccessManager.PlayerStage.AUTHENTICATED,1234,7200000,20,10,50,"secret-world"));
        }
        public BukkitTask timer(Runnable callback,int period) { return task(callback,period,period); }
        public BukkitTask later(Runnable callback,long delay) { return task(callback,delay,0); }
        Task task(Runnable callback,long delay,int period) { var t=new Task(tasks.size()+1,callback,tick+delay,period);tasks.add(t);return t; }
        long pending() { return tasks.stream().filter(t->!t.cancelled).count(); }
        void advance(int ticks) {
            for(int i=0;i<ticks;i++) {
                tick++;
                for(Task task:List.copyOf(tasks)) if(!task.cancelled && task.next<=tick) {
                    if(task.period>0)task.next+=task.period;
                    task.callback.run();
                    if(task.period==0)task.cancelled=true;
                }
            }
        }
        void quit(Client c) { c.online=false;service.onQuit(new PlayerQuitEvent(c.player,Component.empty())); }
    }
    @Test void expiresAndLeavesBeforeFirstHeartbeatWithoutWaitingForTheStaticFallback() {
        for(boolean expiry:List.of(true,false)) {
            var w=new World();var c=w.add("Alex");w.service.reload();assertTrue(c.text().contains("TAG"));
            if(expiry)c.until=w.now()+1;else c.licensed=false;
            w.advance(5);
            assertEquals("Alex",c.text());assertEquals(0,w.service.cacheSizes().animated());
            assertEquals(0,w.service.cacheSizes().names());w.service.shutdown();
        }
    }
    @Test void authTransitionsInvalidatePrivateHeaderEvenWithAllPeriodicPresentationDisabled() {
        var w=new World();var c=w.add("Alex");
        w.config.set("tablist.header",List.of("%tab_balance% | %tab_boost% | %tab_world% | %tab_tip%"));
        w.service.reload();assertTrue(PLAIN.serialize(c.header).contains(BankService.format(1234)));
        c.stage=AccessManager.PlayerStage.NEEDS_LOGIN;w.advance(5);
        String hidden=PLAIN.serialize(c.header);
        assertFalse(hidden.contains("secret-world"));assertFalse(hidden.contains(BankService.format(1234)));
        assertTrue(hidden.contains("после входа"));assertEquals("Alex",c.text());
        c.stage=AccessManager.PlayerStage.AUTHENTICATED;w.advance(5);
        assertTrue(c.text().contains("TAG"));assertTrue(PLAIN.serialize(c.header).contains("secret-world"));
    }
    @Test void customHeaderKeepsPlayerTaggedOnEveryAnimatedFrame() {
        var w=new World();var c=w.add("Alex");
        w.config.set("tablist.dashboard-animation",true);w.config.set("tablist.header",List.of("%player_tagged%"));
        w.service.reload();
        for(int i=0;i<12;i++) { w.advance(10);assertTrue(PLAIN.serialize(c.header).contains("Alex [TAG]")); }
        assertTrue(w.decoratedContexts>=13);
    }
    @Test void hiddenHeaderSkipsContextButCannotStopTheNameAnimation() {
        var w=new World();var c=w.add("Alex");
        var prefs=new LabelPreferences(folder,Logger.getAnonymousLogger());prefs.load();prefs.set("Alex",LabelPreferences.Label.HEADER,false);
        w.service.setLabelPreferences(prefs);w.config.set("tablist.dashboard-animation",true);
        w.config.set("tablist.header",List.of("%tab_balance%"));w.service.reload();
        assertEquals(0,w.contexts);int packets=c.names;w.advance(40);
        assertTrue(c.names>packets);assertEquals(Component.empty(),c.header);assertEquals(0,w.contexts);
    }
    @Test void foreignFramesAreRepairedDuringOwnershipButNotErasedOnReleaseDisableOrShutdown() {
        for(String action:List.of("expire","disable","shutdown")) {
            var w=new World();var c=w.add("Alex");w.config.set("tablist.header",List.of("%online%"));w.service.reload();
            c.listName=Component.text("Foreign");w.advance(5);assertTrue(c.text().contains("TAG"));
            Component foreign=Component.text("Foreign final");c.listName=foreign;c.header=foreign;
            switch(action) {
                case "expire" -> { c.licensed=false;w.advance(5); }
                case "disable" -> { w.config.set("tablist.enabled",false);w.service.reload(); }
                case "shutdown" -> w.service.shutdown();
            }
            assertEquals(foreign,c.listName,action);assertEquals(foreign,c.header,action);
        }
    }
    @Test void releasingMixedHeaderOwnershipClearsOnlyOurHalfOfThePair() {
        for(boolean externalHeader:List.of(true,false)) {
            var w=new World();var c=w.add("Alex");
            w.config.set("tablist.header",List.of("Our header"));w.config.set("tablist.footer",List.of("Our footer"));w.service.reload();
            Component foreign=Component.text("Foreign");if(externalHeader)c.header=foreign;else c.footer=foreign;
            w.service.shutdown();
            assertEquals(externalHeader?foreign:Component.empty(),c.header);
            assertEquals(externalHeader?Component.empty():foreign,c.footer);
        }
    }
    @Test void ownFramesAreRemovedAndLateRosterWorkCannotResurrectDisabledTab() {
        var w=new World();var c=w.add("Alex");w.config.set("tablist.header",List.of("%online%"));w.service.reload();
        w.service.onJoin(new PlayerJoinEvent(c.player,Component.empty()));assertEquals(2,w.pending());
        w.config.set("tablist.enabled",false);w.service.reload();
        assertEquals("Alex",c.text());assertEquals(Component.empty(),c.header);assertEquals(0,w.pending());
        int sent=c.names+c.headers;w.advance(100);assertEquals(sent,c.names+c.headers);
        assertEquals(new TabListService.CacheSizes(0,0,0,0,0),w.service.cacheSizes());
    }
    @Test void twoHundredSimultaneousJoinsCoalesceAndNameOnlyWorkRemainsLinearWithoutContexts() {
        var w=new World();for(int i=0;i<200;i++)w.add("User"+i);w.service.reload();
        int decorated=w.decorations;
        for(var c:w.clients.values())w.service.onJoin(new PlayerJoinEvent(c.player,Component.empty()));
        assertEquals(2,w.pending());w.advance(1);assertEquals(200,w.decorations-decorated);
        decorated=w.decorations;w.advance(4);assertEquals(200,w.decorations-decorated);
        assertEquals(0,w.contexts);assertEquals(new TabListService.CacheSizes(200,200,200,200,0),w.service.cacheSizes());
        for(var c:w.clients.values())w.quit(c);
        assertEquals(new TabListService.CacheSizes(0,0,0,0,0),w.service.cacheSizes());
        w.advance(1);assertEquals(1,w.pending());w.service.shutdown();assertEquals(0,w.pending());
    }
    @Test void reloadCancelsProbesAndRosterTasksAndDoesNotAccumulateCachesOrTimers() {
        var w=new World();var c=w.add("Alex");w.service.reload();
        for(int i=0;i<30;i++) {
            w.service.audit(c.player,c.player);w.service.onJoin(new PlayerJoinEvent(c.player,Component.empty()));
            assertEquals(3,w.pending());w.service.reload();assertEquals(1,w.pending());
            assertEquals(new TabListService.CacheSizes(1,1,1,1,0),w.service.cacheSizes());
        }
        w.service.shutdown();assertEquals(0,w.pending());
        assertEquals(new TabListService.CacheSizes(0,0,0,0,0),w.service.cacheSizes());
    }
    @Test void probeReportsMovingRepairedFrozenAndOfflineWithoutPretendingToCheckAClient() {
        for(String mode:List.of("moving","repair","frozen")) {
            var w=new World();w.frozen=mode.equals("frozen");var c=w.add("Alex");w.service.reload();
            w.service.audit(c.player,c.player);
            if(mode.equals("repair"))c.listName=Component.text("Foreign");
            w.advance(30);String messages=c.allMessages();
            assertTrue(messages.contains("не клиентского FPS"));
            assertTrue(messages.contains(switch(mode) {
                case "moving" -> "кадры меняются";case "repair" -> "исправлена чужая перезапись";default -> "кадры не меняются";
            }),messages);assertEquals(0,w.service.cacheSizes().audits());
        }
        var w=new World();var c=w.add("Alex");c.online=false;w.service.audit(c.player,c.player);
        assertTrue(c.allMessages().contains("Нужен игрок онлайн"));assertEquals(0,w.pending());
    }
    @Test void probePermissionsPerTargetAndGlobalLimitsAndQuitCancellationAreEnforced() {
        var w=new World();for(int i=0;i<17;i++)w.add("User"+i);w.service.reload();var admin=w.clients.get("User0");
        var denied=w.clients.get("User16");denied.allowed=false;w.service.audit(denied.player,admin.player);
        assertTrue(denied.allMessages().contains("Недостаточно прав"));assertEquals(0,w.service.cacheSizes().audits());
        for(var target:w.clients.values())w.service.audit(admin.player,target.player);
        assertEquals(16,w.service.cacheSizes().audits());assertEquals(17,w.pending());
        w.service.audit(admin.player,admin.player);assertEquals(16,w.service.cacheSizes().audits());
        w.quit(w.clients.get("User1"));assertEquals(15,w.service.cacheSizes().audits());
        w.service.shutdown();assertEquals(0,w.pending());
    }
    @Test void activeClanForcesSafeNameAndReturnsToTheCustomFormatWhenItEnds() {
        var w=new World();var c=w.add("Alex");
        w.config.set("tablist.player-name-format","<gradient:red:blue>%player% %online%</gradient>");
        w.service.reload();assertEquals("Alex [TAG]",c.text());assertEquals(0,w.contexts);
        c.licensed=false;w.advance(5);
        assertEquals("Alex 1",c.text());assertEquals(1,w.contexts);
        assertEquals(0,w.service.cacheSizes().animated());
    }
    @Test void quittingTargetAndRevokingAdminPermissionCancelOrSuppressProbeResults() {
        var w=new World();var admin=w.add("Admin");var target=w.add("Alex");w.service.reload();
        w.service.audit(admin.player,target.player);w.quit(target);
        assertEquals(0,w.service.cacheSizes().audits());w.advance(30);
        assertFalse(admin.allMessages().contains("не клиентского FPS"));
        w.service.audit(admin.player,admin.player);admin.allowed=false;w.advance(30);
        assertFalse(admin.allMessages().contains("не клиентского FPS"));assertEquals(0,w.service.cacheSizes().audits());
    }
    @Test void identicalFramesDoNotProduceRepeatedNameOrHeaderPackets() {
        var w=new World();w.frozen=true;var c=w.add("Alex");
        w.config.set("tablist.dashboard-animation",true);w.config.set("tablist.header",List.of("Static header"));w.service.reload();
        int names=c.names,headers=c.headers;w.advance(100);
        assertEquals(names,c.names);assertEquals(headers,c.headers);
        c.header=Component.text("Foreign");w.advance(10);assertEquals(headers+1,c.headers);
    }
}
