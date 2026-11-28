package ru.cherdakmd.chrdkskills;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PvpSkillDamageTest {
    private static final class Fixture {
        final SkillsPlugin plugin = mock(SkillsPlugin.class);
        final SkillsConfig config = mock(SkillsConfig.class);
        final SkillService service = mock(SkillService.class);
        final Server server = mock(Server.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final World world = mock(World.class);
        final Player attacker = mock(Player.class), victim = mock(Player.class);
        final EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        final AtomicReference<Double> damage = new AtomicReference<>(10.0);
        final SkillListener listener;
        Fixture() {
            when(plugin.config()).thenReturn(config);
            when(config.enabled()).thenReturn(true);
            when(plugin.getServer()).thenReturn(server);
            when(server.getScheduler()).thenReturn(scheduler);
            when(world.getPVP()).thenReturn(true);
            when(attacker.getUniqueId()).thenReturn(UUID.randomUUID());
            when(victim.getUniqueId()).thenReturn(UUID.randomUUID());
            when(victim.getWorld()).thenReturn(world);
            when(attacker.getGameMode()).thenReturn(GameMode.SURVIVAL);
            when(event.getEntity()).thenReturn(victim);
            when(event.getDamage()).thenAnswer(i -> damage.get());
            doAnswer(i -> { damage.set(i.getArgument(0)); return null; }).when(event).setDamage(anyDouble());
            when(service.attribute(attacker, PlayerAttribute.STRENGTH)).thenReturn(10);
            when(service.attribute(attacker, PlayerAttribute.CUNNING)).thenReturn(10);
            when(service.branchLevel(attacker, BuildBranch.WARRIOR)).thenReturn(10);
            when(service.branchLevel(attacker, BuildBranch.ARCHER)).thenReturn(10);
            when(service.branchLevel(attacker, BuildBranch.ROGUE)).thenReturn(10);
            when(service.perksFor(attacker, SkillType.COMBAT)).thenReturn(new SkillPerks.Active(
                    Map.of(SkillPerk.Kind.MELEE_DAMAGE, .2, SkillPerk.Kind.LIFESTEAL, .1), List.of()));
            when(service.perksFor(attacker, SkillType.ARCHERY)).thenReturn(new SkillPerks.Active(
                    Map.of(SkillPerk.Kind.ARROW_DAMAGE, .2), List.of()));
            listener = new SkillListener(plugin, service);
        }
    }

    @Test void meleeUsesBaseAttributesPerksAndBranchesWithoutMobRewards() {
        var f = new Fixture(); when(f.event.getDamager()).thenReturn(f.attacker);
        f.listener.onMelee(f.event);
        assertEquals(10 * 1.2 * 1.08 * 1.2 * 1.1, f.damage.get(), 1e-9);
        verify(f.service, never()).award(any(), any(), anyLong());
        verify(f.attacker, never()).setHealth(anyDouble());
    }

    @Test void arrowUsesRangedModifiersWithoutMobRewards() {
        var f = new Fixture(); var arrow = mock(AbstractArrow.class);
        when(arrow.getShooter()).thenReturn(f.attacker); when(f.event.getDamager()).thenReturn(arrow);
        f.listener.onArrow(f.event);
        assertEquals(10 * 1.2 * 1.03 * 1.2 * 1.1, f.damage.get(), 1e-9);
        verify(f.service, never()).award(any(), any(), anyLong());
    }

    @Test void selfNpcAndPvpDisabledDoNotReceiveBonuses() {
        var f = new Fixture(); when(f.event.getDamager()).thenReturn(f.attacker);
        when(f.world.getPVP()).thenReturn(false); f.listener.onMelee(f.event);
        when(f.world.getPVP()).thenReturn(true); when(f.victim.hasMetadata("NPC")).thenReturn(true); f.listener.onMelee(f.event);
        when(f.victim.hasMetadata("NPC")).thenReturn(false); when(f.event.getEntity()).thenReturn(f.attacker); f.listener.onMelee(f.event);
        assertEquals(10, f.damage.get());
        verify(f.service, never()).perksFor(any(), any());
    }

    @Test void attributedAreaAbilityDoesNotBecomeAMeleeWeaponHit() {
        var f = new Fixture(); when(f.event.getDamager()).thenReturn(f.attacker);
        ru.cherdakmd.chrdkregister.api.AreaDamageContext.run(f.attacker, f.victim,
                () -> f.listener.onMelee(f.event));
        assertEquals(10, f.damage.get());
        verify(f.service, never()).perksFor(any(), any());
        verify(f.service, never()).award(any(), any(), anyLong());
    }

    @Test void mobDamageKeepsStrongFoeAndTierPressureAndAwardsCombatXp() {
        var f = new Fixture(); var mob = mock(org.bukkit.entity.Monster.class);
        var data = mock(org.bukkit.persistence.PersistentDataContainer.class);
        when(mob.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(org.bukkit.NamespacedKey.class), eq(org.bukkit.persistence.PersistentDataType.INTEGER))).thenReturn(8);
        when(f.event.getDamager()).thenReturn(f.attacker); when(f.event.getEntity()).thenReturn(mob);
        when(f.event.getFinalDamage()).thenReturn(10.0);
        when(f.service.perksFor(f.attacker, SkillType.COMBAT)).thenReturn(new SkillPerks.Active(
                Map.of(SkillPerk.Kind.MELEE_DAMAGE, .2), List.of()));
        f.listener.onMelee(f.event);
        assertEquals(10 * 1.2 * (1 + .08 * 1.5) * .85 * 1.2 * 1.1, f.damage.get(), 1e-9);
        verify(f.service).award(f.attacker, SkillType.COMBAT, 10L);
    }

    @Test void cancelledHitDoesNotScheduleLifestealAndLateCancellationStopsIt() {
        var f = new Fixture(); when(f.event.getDamager()).thenReturn(f.attacker);
        f.listener.onMelee(f.event); when(f.event.isCancelled()).thenReturn(true);
        f.listener.onPvpLifesteal(f.event);
        verify(f.scheduler, never()).runTask(eq(f.plugin), any(Runnable.class));
        when(f.event.isCancelled()).thenReturn(false); f.listener.onMelee(f.event);
        AtomicReference<Runnable> task = new AtomicReference<>();
        doAnswer(i -> { task.set(i.getArgument(1)); return null; }).when(f.scheduler).runTask(eq(f.plugin), any(Runnable.class));
        f.listener.onPvpLifesteal(f.event); assertNotNull(task.get());
        when(f.event.isCancelled()).thenReturn(true); when(f.event.getFinalDamage()).thenReturn(10.0);
        task.get().run(); verify(f.attacker, never()).setHealth(anyDouble());
    }
}
