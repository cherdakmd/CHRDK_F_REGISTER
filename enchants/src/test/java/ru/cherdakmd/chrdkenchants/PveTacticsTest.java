package ru.cherdakmd.chrdkenchants;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PveTacticsTest {
    @Test void newEffectsHaveExplicitFixedBalanceAndNormalSourceGates() {
        for (var effect : List.of(PveEnchant.PROVOKING_STRIKE, PveEnchant.SIGNAL_GUARD)) {
            assertEquals(2,effect.maxLevel); assertEquals(15,effect.cooldownSeconds);
            assertEquals(15,effect.chance(1)); assertEquals(20,effect.chance(2)); assertEquals(20,effect.chance(Integer.MAX_VALUE));
            assertEquals(15,effect.chance(Integer.MIN_VALUE));
            var def=EnchantRegistry.byId(effect.id); assertEquals(EnchantDef.Rarity.RARE,def.rarity()); assertFalse(def.curse());
            assertTrue(ru.cherdakmd.chrdkregister.api.EnchantBookPolicy.allows(def.rarity().name(),1));
        }
        assertEquals(0,PveEnchant.PROVOKING_STRIKE.ticks(2));
        assertEquals(40,PveEnchant.SIGNAL_GUARD.ticks(1)); assertEquals(60,PveEnchant.SIGNAL_GUARD.ticks(2));
        assertEquals("pve-melee-control",EnchantRegistry.byId("provoking_strike").conflictGroup());
        assertEquals("pve-guard-control",EnchantRegistry.byId("signal_guard").conflictGroup());
        assertTrue(EnchantRegistry.byId("provoking_strike").supports("IRON_AXE"));
        assertFalse(EnchantRegistry.byId("provoking_strike").supports("BOW"));
        assertTrue(EnchantRegistry.byId("signal_guard").supports("DIAMOND_LEGGINGS"));
        assertFalse(EnchantRegistry.byId("signal_guard").supports("DIAMOND_BOOTS"));
    }
    @Test void automaticChoicePreservesOldHighestLevelAndStableTieOrder() {
        assertEquals(PveEnchant.BRIAR_GUARD,PveChoices.choose(PveEnchant.Channel.GUARD,
                Map.of("briar_guard",2,"faltering_guard",2,"signal_guard",2),null));
        assertEquals(PveEnchant.FALTERING_GUARD,PveChoices.choose(PveEnchant.Channel.GUARD,
                Map.of("briar_guard",1,"faltering_guard",2),null));
        assertEquals(PveEnchant.CRIPPLING_EDGE,PveChoices.choose(PveEnchant.Channel.MELEE,
                Map.of("crippling_edge",1,"provoking_strike",1),null));
    }
    @Test void personalPreferenceOnlySelectsAnActuallyAvailablePositiveGuard() {
        assertEquals(PveEnchant.SIGNAL_GUARD,PveChoices.choose(PveEnchant.Channel.GUARD,
                Map.of("briar_guard",2,"signal_guard",1),"signal_guard"));
        assertEquals(PveEnchant.BRIAR_GUARD,PveChoices.choose(PveEnchant.Channel.GUARD,
                Map.of("briar_guard",2,"signal_guard",0),"signal_guard"));
        assertEquals(PveEnchant.BRIAR_GUARD,PveChoices.choose(PveEnchant.Channel.GUARD,
                Map.of("briar_guard",2),"signal_guard"));
        assertNull(PveChoices.choose(PveEnchant.Channel.GUARD,Map.of(),"signal_guard"));
    }
    @Test void preferenceCannotAffectAttackChannelsAndMalformedValuesAreNotExecuted() {
        for(String value:List.of("auto","unknown","PROVOKING_STRIKE","crippling_edge","signal_guard;faltering_guard","x".repeat(65)))
            assertNull(PveChoices.preference(value));
        assertNull(PveChoices.preference(null));
        assertEquals(PveEnchant.CRIPPLING_EDGE,PveChoices.choose(PveEnchant.Channel.MELEE,
                Map.of("crippling_edge",2,"provoking_strike",1),"signal_guard"));
        assertNull(PveChoices.choose(PveEnchant.Channel.GUARD,Map.of("signal_guard",-1),"signal_guard"));
    }
    @Test void allWornPieceLevelsAreComparedByMaximumRatherThanSummation() {
        assertEquals(PveEnchant.BRIAR_GUARD,PveChoices.choose(PveEnchant.Channel.GUARD,
                Map.of("briar_guard",Integer.MAX_VALUE,"faltering_guard",2),null));
        assertEquals(PveEnchant.SIGNAL_GUARD,PveChoices.choose(PveEnchant.Channel.GUARD,
                Map.of("signal_guard",Integer.MAX_VALUE),"signal_guard"));
    }
    private static final class Arena {
        World world=proxy(World.class,(name,args)->{ throw new UnsupportedOperationException(name); });
        World ownerWorld=world;
        double distance=5; boolean ai=true,aware=true,leashed=false,lineOfSight=true,npc=false,named=false,invisible=false,vanished=false,valid=true;
        AtomicBoolean context=new AtomicBoolean(true);
        AtomicReference<LivingEntity> target=new AtomicReference<>(); AtomicInteger writes=new AtomicInteger();
        Player owner=proxy(Player.class,(name,args)->switch(name) {
            case "getWorld"->ownerWorld; case "getLocation"->new Location(ownerWorld,0,0,0);
            case "isValid"->true; case "isDead"->false; case "isInvisible"->invisible;
            case "hasMetadata"->vanished; default->throw new UnsupportedOperationException(name);
        });
        Monster mob=proxy(Monster.class,(name,args)->switch(name) {
            case "getWorld"->world; case "getLocation"->new Location(world,distance,0,0);
            case "isValid"->valid; case "isDead"->false; case "hasAI"->ai; case "isAware"->aware;
            case "isLeashed"->leashed; case "hasLineOfSight"->lineOfSight;
            case "hasMetadata"->npc; case "customName"->named?net.kyori.adventure.text.Component.text("Boss"):null;
            case "getTarget"->target.get(); case "setTarget"->{ target.set((LivingEntity)args[0]);writes.incrementAndGet();yield null; }
            default->throw new UnsupportedOperationException(name);
        });
        boolean apply(java.util.function.Consumer<org.bukkit.event.entity.EntityTargetLivingEntityEvent> events) {
            return PveTaunt.apply(mob,owner,context::get,events);
        }
    }
    @Test void tauntEmitsOneCustomTargetEventThenUsesNativeSetterOnce() {
        var arena=new Arena(); var events=new AtomicInteger();
        assertTrue(arena.apply(event->{ events.incrementAndGet(); assertSame(arena.owner,event.getTarget());
            assertEquals(org.bukkit.event.entity.EntityTargetEvent.TargetReason.CUSTOM,event.getReason()); }));
        assertEquals(1,events.get()); assertEquals(1,arena.writes.get()); assertSame(arena.owner,arena.target.get());
        assertFalse(arena.apply(event->fail("already targeting owner"))); assertEquals(1,arena.writes.get());
    }
    @Test void targetCancellationAndTargetSubstitutionCannotBeOverridden() {
        var first=new Arena(); assertFalse(first.apply(event->event.setCancelled(true))); assertEquals(0,first.writes.get());
        var second=new Arena(); assertFalse(second.apply(event->event.setTarget(null))); assertEquals(0,second.writes.get());
        var third=new Arena(); Player other=proxy(Player.class,(name,args)->null);
        assertFalse(third.apply(event->event.setTarget(other))); assertEquals(0,third.writes.get());
    }
    @Test void reentrantTargetChangeIsNotOverwritten() {
        var arena=new Arena(); Player other=proxy(Player.class,(name,args)->null);
        assertFalse(arena.apply(event->arena.target.set(other))); assertSame(other,arena.target.get()); assertEquals(0,arena.writes.get());
    }
    @Test void contextAndVisibilityAreRecheckedAfterAllTargetHandlers() {
        var cancelled=new Arena(); assertFalse(cancelled.apply(event->cancelled.context.set(false))); assertEquals(0,cancelled.writes.get());
        var vanished=new Arena(); assertFalse(vanished.apply(event->vanished.vanished=true)); assertEquals(0,vanished.writes.get());
        var renamed=new Arena(); assertFalse(renamed.apply(event->renamed.named=true)); assertEquals(0,renamed.writes.get());
        var moved=new Arena(); assertFalse(moved.apply(event->moved.distance=7)); assertEquals(0,moved.writes.get());
    }
    @Test void noAiUnawareLeashedNpcNamedOrInvisibleCasesNeverEmitARequest() {
        for (int mode=0;mode<8;mode++) {
            var arena=new Arena(); switch(mode) { case 0->arena.ai=false;case 1->arena.aware=false;case 2->arena.leashed=true;
                case 3->arena.npc=true;case 4->arena.named=true;case 5->arena.invisible=true;case 6->arena.vanished=true;case 7->arena.valid=false; }
            assertFalse(arena.apply(event->fail("ineligible target"))); assertEquals(0,arena.writes.get());
        }
    }
    @Test void tauntRequiresSameWorldSixBlockReachFiniteCoordinatesAndLineOfSight() {
        var blocked=new Arena(); blocked.lineOfSight=false;assertFalse(blocked.apply(event->fail("wall")));
        var far=new Arena();far.distance=6.001;assertFalse(far.apply(event->fail("reach")));
        var nan=new Arena();nan.distance=Double.NaN;assertFalse(nan.apply(event->fail("NaN")));
        var world=new Arena();world.ownerWorld=proxy(World.class,(name,args)->null);assertFalse(world.apply(event->fail("world")));
        var edge=new Arena();edge.distance=6;assertTrue(edge.apply(event->{}));
    }
    @Test void signalAndTauntRemainInsideFinalDamageEquipmentAndSharedCooldownGuards() throws Exception {
        String source=source("PveEffectsListener");
        for(String guard:List.of("event.isCancelled() || event.getFinalDamage() <= 0","!Double.isFinite(event.getFinalDamage())",
                "PveEquipment.same(weapon, owner.getInventory().getItemInMainHand())", "PveEquipment.same(armor, owner.getInventory().getArmorContents())",
                "expectedPreference, preference(owner)", "case \"GLOWING\" -> PotionEffectType.GLOWING", "target.isGlowing()",
                "target.hasPotionEffect(type)", "if (applied) owner.getPersistentDataContainer().set(ready", "generation == plugin.sourceGeneration()",
                "session.equals(sessions.get(owner.getUniqueId()))", "coreApi().isAuthenticated(player)")) assertTrue(source.contains(guard),guard);
        for(String forbidden:List.of("remove(ready)","removePotionEffect(","target.damage(","setDamage(","setHealth(","dropItem(","addItem("))
            assertFalse(source.contains(forbidden),forbidden);
    }
    @Test void commandCannotSelectUnwornOrDisabledGuardAndNeverClearsCombatCooldown() throws Exception {
        String source=source("PveEffectsListener");
        assertTrue(source.contains("!available(PveEnchant.Channel.GUARD, player.getInventory().getArmorContents()).containsKey(enchant.id)"));
        assertTrue(source.contains("new NamespacedKey(plugin, \"pve_guard_priority_v1\")"));
        String command=source.substring(source.indexOf("void guardPreference("),source.indexOf("@EventHandler(priority"));
        assertTrue(command.contains("remove(preferred)")); assertFalse(command.contains("set(ready"));assertFalse(command.contains("remove(ready"));
        assertTrue(source("EnchantCommandHandler").contains("case \"guard\""));
    }
    @Test void tauntHasNoAggroLockTasksVelocityTeleportOrBlockMutation() throws Exception {
        String source=source("PveTaunt");
        for(String forbidden:List.of("runTask", "setAware(", "setAI(", "teleport(", "setVelocity(","setHealth(","setType(")) assertFalse(source.contains(forbidden),forbidden);
        assertTrue(source.contains("targeting.isCancelled() || targeting.getTarget() != owner"));
        assertTrue(source.contains("Objects.equals(previous, mob.getTarget())"));
    }
    private String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/"+name+".java"));}
    @FunctionalInterface private interface Call {Object run(String name,Object[] args);}
    private static <T>T proxy(Class<T>type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(self,method,args)->{
        if(method.getName().equals("hashCode"))return System.identityHashCode(self);
        if(method.getName().equals("equals"))return self==args[0];
        if(method.getName().equals("toString"))return type.getSimpleName();
        return call.run(method.getName(),args==null?new Object[0]:args);
    }));}
}
