package ru.cherdakmd.chrdkregister.api;

import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AreaPvpGateTest {
    private final Player a = mock(Player.class), b = mock(Player.class);
    private AreaPvpGate.Provider provider(String name, AreaPvpPolicy.Verdict verdict) {
        return new AreaPvpGate.Provider(name, true, (x,y) -> verdict);
    }
    @Test void requiresBothKnownProvidersAndAllAllow() {
        var claims = provider("CHRDK_Claims", AreaPvpPolicy.Verdict.ALLOW);
        var guilds = provider("CHRDK_Guilds", AreaPvpPolicy.Verdict.ALLOW);
        assertTrue(AreaPvpGate.evaluate(a,b,List.of(claims,guilds)));
        assertFalse(AreaPvpGate.evaluate(a,b,List.of(claims)));
        assertFalse(AreaPvpGate.evaluate(a,b,List.of(guilds)));
        assertFalse(AreaPvpGate.evaluate(a,b,List.of(claims,guilds,provider("extra",AreaPvpPolicy.Verdict.DENY))));
        assertFalse(AreaPvpGate.evaluate(a,b,List.of(claims,provider("CHRDK_Guilds",AreaPvpPolicy.Verdict.UNKNOWN))));
        assertFalse(AreaPvpGate.evaluate(a,b,List.of(claims,new AreaPvpGate.Provider("CHRDK_Guilds",false,(x,y)->AreaPvpPolicy.Verdict.ALLOW))));
        assertFalse(AreaPvpGate.evaluate(a,b,List.of(claims,new AreaPvpGate.Provider("CHRDK_Guilds",true,(x,y)->{throw new IllegalStateException();}))));
    }
    @Test void liveGateRequiresValidPlayersWorldAndServices() {
        var world = mock(org.bukkit.World.class); var manager = mock(org.bukkit.plugin.ServicesManager.class);
        @SuppressWarnings("unchecked") var claimRegistration = (org.bukkit.plugin.RegisteredServiceProvider<AreaPvpPolicy>) mock(org.bukkit.plugin.RegisteredServiceProvider.class);
        @SuppressWarnings("unchecked") var guildRegistration = (org.bukkit.plugin.RegisteredServiceProvider<AreaPvpPolicy>) mock(org.bukkit.plugin.RegisteredServiceProvider.class);
        var claims = mock(org.bukkit.plugin.Plugin.class); var guilds = mock(org.bukkit.plugin.Plugin.class);
        when(claims.getName()).thenReturn("CHRDK_Claims"); when(claims.isEnabled()).thenReturn(true);
        when(guilds.getName()).thenReturn("CHRDK_Guilds"); when(guilds.isEnabled()).thenReturn(true);
        when(claimRegistration.getPlugin()).thenReturn(claims); when(guildRegistration.getPlugin()).thenReturn(guilds);
        when(claimRegistration.getProvider()).thenReturn((x,y)->AreaPvpPolicy.Verdict.ALLOW);
        when(guildRegistration.getProvider()).thenReturn((x,y)->AreaPvpPolicy.Verdict.ALLOW);
        when(manager.getRegistrations(AreaPvpPolicy.class)).thenReturn(List.of(claimRegistration,guildRegistration));
        when(a.getUniqueId()).thenReturn(UUID.randomUUID()); when(b.getUniqueId()).thenReturn(UUID.randomUUID());
        when(a.isOnline()).thenReturn(true); when(b.isOnline()).thenReturn(true);
        when(a.getWorld()).thenReturn(world); when(b.getWorld()).thenReturn(world);
        when(world.getPVP()).thenReturn(true); when(b.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(org.bukkit.Bukkit::getServicesManager).thenReturn(manager);
            assertTrue(AreaPvpGate.allows(a,b));
            when(world.getPVP()).thenReturn(false); assertFalse(AreaPvpGate.allows(a,b)); when(world.getPVP()).thenReturn(true);
            when(b.hasMetadata("NPC")).thenReturn(true); assertFalse(AreaPvpGate.allows(a,b)); when(b.hasMetadata("NPC")).thenReturn(false);
            when(b.getGameMode()).thenReturn(org.bukkit.GameMode.CREATIVE); assertFalse(AreaPvpGate.allows(a,b));
            when(b.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL); when(guilds.isEnabled()).thenReturn(false);
            assertFalse(AreaPvpGate.allows(a,b)); assertFalse(AreaPvpGate.allows(a,a));
        }
    }
    @Test void abilityContextMatchesOnlyTheActivePairAndAlwaysUnwinds() {
        var other = mock(Player.class);
        when(a.getUniqueId()).thenReturn(UUID.randomUUID());
        when(b.getUniqueId()).thenReturn(UUID.randomUUID());
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        var hit = mock(EntityDamageByEntityEvent.class); when(hit.getDamager()).thenReturn(a); when(hit.getEntity()).thenReturn(b);
        var reflected = mock(EntityDamageByEntityEvent.class); when(reflected.getDamager()).thenReturn(b); when(reflected.getEntity()).thenReturn(a);
        assertFalse(AreaDamageContext.isAbilityDamage(hit));
        assertThrows(IllegalStateException.class, () -> AreaDamageContext.run(a,b,() -> {
            assertTrue(AreaDamageContext.isAbilityDamage(hit));
            assertFalse(AreaDamageContext.isAbilityDamage(reflected));
            AreaDamageContext.run(a,other,() -> assertFalse(AreaDamageContext.isAbilityDamage(hit)));
            assertTrue(AreaDamageContext.isAbilityDamage(hit));
            throw new IllegalStateException();
        }));
        assertFalse(AreaDamageContext.isAbilityDamage(hit));
    }
}
