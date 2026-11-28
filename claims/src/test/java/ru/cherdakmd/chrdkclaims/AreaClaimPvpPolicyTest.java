package ru.cherdakmd.chrdkclaims;

import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.AreaPvpPolicy.Verdict;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AreaClaimPvpPolicyTest {
    @Test void readsTheSameClaimAndBuildPermissionWithoutSideEffects() {
        var plugin=mock(ClaimsPlugin.class); var store=mock(ClaimStore.class);
        var attacker=mock(Player.class); var target=mock(Player.class); var location=mock(Location.class);
        var block=mock(Block.class); var world=mock(World.class);
        var yaml=new YamlConfiguration(); yaml.set("protect.pvp",true);
        when(plugin.config()).thenReturn(ClaimsConfig.parse(yaml,Logger.getAnonymousLogger())); when(plugin.store()).thenReturn(store);
        when(attacker.getName()).thenReturn("Attacker"); when(target.getLocation()).thenReturn(location);
        when(location.getBlock()).thenReturn(block); when(block.getWorld()).thenReturn(world); when(world.getName()).thenReturn("world");
        var listener=new ClaimProtectionListener(plugin);
        assertEquals(Verdict.ALLOW,listener.areaPvp(attacker,target));
        var foreign=new Claim("world",0,0,"Owner",0); when(store.protectedClaimAt("world",0,0)).thenReturn(foreign);
        assertEquals(Verdict.DENY,listener.areaPvp(attacker,target));
        when(plugin.isBypassing(attacker)).thenReturn(true); assertEquals(Verdict.ALLOW,listener.areaPvp(attacker,target));
        when(plugin.isBypassing(attacker)).thenReturn(false); when(store.protectedClaimAt("world",0,0)).thenThrow(new IllegalStateException());
        assertEquals(Verdict.UNKNOWN,listener.areaPvp(attacker,target));
        verify(target,never()).damage(anyDouble(),any(org.bukkit.entity.Entity.class));
    }
}
