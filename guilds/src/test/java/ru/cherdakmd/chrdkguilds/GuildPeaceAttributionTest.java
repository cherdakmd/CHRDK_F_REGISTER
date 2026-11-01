package ru.cherdakmd.chrdkguilds;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Настоящие методы слушателя с Bukkit-интерфейсами-прокси; не запущенный сервер. */
class GuildPeaceAttributionTest {
    static class State {
        final UUID id=UUID.randomUUID();
        final Map<NamespacedKey,Object> data=new HashMap<>();
        Object shooter;
        boolean pvp=true,dead,invulnerable;
        GameMode mode=GameMode.SURVIVAL;
    }
    final Set<UUID> peaceful=new HashSet<>();
    long now=1_900_000_000_000L;
    boolean accounted=true,block=true;
    UUID attacker,source;
    boolean penalty,peacefulAction,launchPeaceful;
    int calls;
    final GuildPeaceListener.Access access=new GuildPeaceListener.Access() {
        public boolean enabled(){return true;}
        public boolean authenticated(Player p){return true;}
        public boolean peaceful(UUID owner){return peaceful.contains(owner);}
        public GuildPeaceController.AttackOutcome attack(UUID a,UUID v,boolean l,boolean p,boolean penalize,UUID s,String kind,long time) {
            attacker=a;source=s;penalty=penalize;peacefulAction=p;launchPeaceful=l;calls++;
            assertEquals(now,time);
            return new GuildPeaceController.AttackOutcome(block,penalize&&accounted);
        }
        public void quit(UUID owner) { }
    };
    GuildPeaceListener listener(){return new GuildPeaceListener(access,"test",()->now);}
    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }
    PersistentDataContainer data(State state) {
        return proxy(PersistentDataContainer.class,(p,m,a)->switch(m.getName()) {
            case "get" -> state.data.get(a[0]);
            case "has" -> state.data.containsKey(a[0]);
            case "set" -> {state.data.put((NamespacedKey)a[0],a[2]);yield null;}
            case "remove" -> {state.data.remove(a[0]);yield null;}
            default -> throw new AssertionError(m.getName());
        });
    }
    <T extends Entity> T entity(Class<T> type,State state) {
        var pdc=data(state);
        World world=proxy(World.class,(p,m,a)->{if(m.getName().equals("getPVP"))return state.pvp;throw new AssertionError(m.getName());});
        return proxy(type,(p,m,a)->switch(m.getName()) {
            case "getUniqueId" -> state.id;
            case "getPersistentDataContainer" -> pdc;
            case "getShooter" -> state.shooter;
            case "getWorld" -> world;
            case "hasMetadata" -> false;
            case "isDead" -> state.dead;
            case "isInvulnerable" -> state.invulnerable;
            case "getGameMode" -> state.mode;
            default -> throw new AssertionError(m.getName());
        });
    }
    @Test void directPeacefulActionIsProvenAndCanBePenalized() {
        var a=new State();peaceful.add(a.id);var listener=listener();
        assertTrue(listener.attack(entity(Player.class,a),entity(Player.class,new State()),"melee"));
        assertTrue(penalty);assertTrue(peacefulAction);assertEquals(a.id,attacker);
    }
    @Test void projectileRemembersOwnerAndPeacefulLaunchAfterClanExit() {
        var a=new State();peaceful.add(a.id);var arrow=entity(Arrow.class,new State());var listener=listener();listener.mark(arrow,a.id);
        peaceful.clear();listener.attack(arrow,entity(Player.class,new State()),"projectile");
        assertTrue(penalty);assertTrue(peacefulAction);assertTrue(launchPeaceful);assertEquals(a.id,attacker);
    }
    @Test void battleProjectileCannotBeRelabelledPeacefulByLaterClanJoin() {
        var a=new State();var arrow=entity(Arrow.class,new State());var listener=listener();listener.mark(arrow,a.id);
        peaceful.add(a.id);listener.attack(arrow,entity(Player.class,new State()),"projectile");
        assertFalse(peacefulAction);assertFalse(launchPeaceful);
    }
    @Test void sameSourceIsNotPenalizedAgainAfterMinuteOrListenerRecreation() {
        var arrow=entity(Arrow.class,new State());var listener=listener();listener.mark(arrow,UUID.randomUUID());
        listener.attack(arrow,entity(Player.class,new State()),"projectile");UUID first=source;assertTrue(penalty);
        now+=60_000;listener().attack(arrow,entity(Player.class,new State()),"projectile");
        assertFalse(penalty);assertEquals(first,source);
    }
    @Test void cloudInheritsPotionActionAndUsedFlag() {
        var potion=entity(ThrownPotion.class,new State());var cloud=entity(AreaEffectCloud.class,new State());var listener=listener();
        listener.mark(potion,UUID.randomUUID());listener.attack(potion,entity(Player.class,new State()),"potion");UUID first=source;
        listener.markFrom(cloud,potion);now+=60_000;listener.attack(cloud,entity(Player.class,new State()),"potion");
        assertEquals(first,source);assertFalse(penalty);
    }
    @Test void firstCloudHitAccountsWholeActionNotEveryVictim() {
        var potion=entity(ThrownPotion.class,new State());var cloud=entity(AreaEffectCloud.class,new State());var listener=listener();
        listener.mark(potion,UUID.randomUUID());listener.markFrom(cloud,potion);
        listener.attack(cloud,entity(Player.class,new State()),"potion");assertTrue(penalty);
        listener.attack(cloud,entity(Player.class,new State()),"potion");assertFalse(penalty);
    }
    @Test void failedDurableWriteDoesNotFalselyMarkSourceAccounted() {
        var arrow=entity(Arrow.class,new State());var listener=listener();listener.mark(arrow,UUID.randomUUID());
        accounted=false;listener.attack(arrow,entity(Player.class,new State()),"projectile");assertTrue(penalty);
        accounted=true;listener.attack(arrow,entity(Player.class,new State()),"projectile");assertTrue(penalty);
        listener.attack(arrow,entity(Player.class,new State()),"projectile");assertFalse(penalty);
    }
    @Test void legacyUnmarkedArrowStillHasOwnerButCannotAccuseRetrospectively() {
        var state=new State();state.shooter=entity(Player.class,new State());
        listener().attack(entity(Arrow.class,state),entity(Player.class,new State()),"projectile");
        assertNotNull(attacker);assertFalse(penalty);
    }
    @Test void sourceOlderThanEvidenceWindowCannotCreateNewIncident() {
        var arrow=entity(Arrow.class,new State());var listener=listener();listener.mark(arrow,UUID.randomUUID());
        now+=GuildSanction.WINDOW;listener.attack(arrow,entity(Player.class,new State()),"projectile");assertFalse(penalty);
    }
    @Test void futureDatedSourceCannotAccuseAfterClockRollback() {
        var arrow=entity(Arrow.class,new State());var listener=listener();listener.mark(arrow,UUID.randomUUID());
        now--;listener.attack(arrow,entity(Player.class,new State()),"projectile");assertFalse(penalty);
    }
    @Test void automatedPetRetaliationNeverFinesOwnerEvenIfMarked() {
        var wolf=entity(Wolf.class,new State());var listener=listener();listener.mark(wolf,UUID.randomUUID());
        listener.attack(wolf,entity(Player.class,new State()),"melee");assertFalse(penalty);
    }
    @Test void invulnerableCreativeSpectatorAndPvpOffTargetsDoNotAccuse() {
        var target=new State();var player=entity(Player.class,target);var attacker=entity(Player.class,new State());var listener=listener();
        target.mode=GameMode.CREATIVE;listener.attack(attacker,player,"melee");assertFalse(penalty);
        target.mode=GameMode.SPECTATOR;listener.attack(attacker,player,"melee");assertFalse(penalty);
        target.mode=GameMode.SURVIVAL;target.invulnerable=true;listener.attack(attacker,player,"melee");assertFalse(penalty);
        target.invulnerable=false;target.pvp=false;listener.attack(attacker,player,"melee");assertFalse(penalty);
    }
    @Test void deadTargetNeverInvokesSanctions() {
        var target=new State();target.dead=true;assertFalse(listener().attack(entity(Player.class,new State()),entity(Player.class,target),"melee"));assertEquals(0,calls);
    }
}
