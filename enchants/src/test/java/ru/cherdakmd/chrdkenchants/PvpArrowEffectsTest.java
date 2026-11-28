package ru.cherdakmd.chrdkenchants;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PvpArrowEffectsTest {
    @Test void lateCancelledArrowCannotApplyFrost() {
        var plugin=mock(EnchantsPlugin.class); var items=mock(EnchantItems.class); var scheduler=mock(BukkitScheduler.class);
        var arrow=mock(Arrow.class); var shooter=mock(Player.class); var target=mock(Player.class);
        var event=mock(EntityDamageByEntityEvent.class); var world=mock(World.class); var location=mock(Location.class);
        var tasks=new ArrayList<Runnable>();
        when(plugin.items()).thenReturn(items); when(items.readEntity(arrow)).thenReturn(Map.of("frostarrow",1));
        when(arrow.getShooter()).thenReturn(shooter); when(event.getDamager()).thenReturn(arrow);
        when(event.getEntity()).thenReturn(target); when(event.getFinalDamage()).thenReturn(4.0);
        when(target.getWorld()).thenReturn(world); when(world.getUID()).thenReturn(UUID.randomUUID());
        when(target.getLocation()).thenReturn(location);
        when(scheduler.runTaskTimer(eq(plugin),any(Runnable.class),anyLong(),anyLong())).thenReturn(mock(BukkitTask.class));
        doAnswer(call->{tasks.add(call.getArgument(1));return mock(BukkitTask.class);}).when(scheduler).runTask(eq(plugin),any(Runnable.class));
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            var listener=new BowEffectsListener(plugin,new ArrowExplosionGuard());
            listener.onConfirmedArrowDamage(event); assertEquals(1,tasks.size());
            when(event.isCancelled()).thenReturn(true); tasks.removeFirst().run();
            verify(target,never()).setFreezeTicks(anyInt()); verify(target,never()).addPotionEffect(any());
            listener.stopSweep();
        }
    }
}
