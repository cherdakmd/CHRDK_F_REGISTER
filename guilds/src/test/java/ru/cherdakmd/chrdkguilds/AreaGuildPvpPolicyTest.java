package ru.cherdakmd.chrdkguilds;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.AreaPvpPolicy.Verdict;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.api.PaidActions;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AreaGuildPvpPolicyTest {
    private static void field(Object o,String name,Object value) throws Exception {
        var f=GuildsPlugin.class.getDeclaredField(name); f.setAccessible(true); f.set(o,value);
    }
    @Test void preflightExcludesAlliesPeaceAndUnknownWithoutRecordingAttacks() throws Exception {
        var plugin=mock(GuildsPlugin.class); var config=mock(GuildConfig.class); var service=mock(GuildService.class);
        var peace=mock(GuildPeaceController.class); var groups=mock(AdventureGroupController.class);
        var payments=mock(PaidActions.class); var api=mock(ChrdkRegisterApi.class);
        var a=mock(Player.class); var b=mock(Player.class); UUID aId=UUID.randomUUID(), bId=UUID.randomUUID();
        when(a.getUniqueId()).thenReturn(aId); when(b.getUniqueId()).thenReturn(bId);
        field(plugin,"config",config); field(plugin,"service",service); field(plugin,"peace",peace);
        field(plugin,"adventureGroups",groups); field(plugin,"payments",payments); field(plugin,"coreApi",api);
        when(plugin.isEnabled()).thenReturn(true); when(config.enabled()).thenReturn(true);
        when(plugin.authenticated(any())).thenReturn(true);
        when(service.relation(aId,bId)).thenReturn(GuildRelation.OTHER);
        when(groups.areaRelation(aId,bId)).thenReturn(AdventureGroupRelation.DIFFERENT);
        doCallRealMethod().when(plugin).areaPvp(a,b);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            assertEquals(Verdict.ALLOW, plugin.areaPvp(a,b));
            when(service.relation(aId,bId)).thenReturn(GuildRelation.SAME_GUILD); assertEquals(Verdict.DENY,plugin.areaPvp(a,b));
            when(service.relation(aId,bId)).thenReturn(GuildRelation.ACTIVE_WAR);
            when(groups.areaRelation(aId,bId)).thenReturn(AdventureGroupRelation.SAME_GROUP); assertEquals(Verdict.DENY,plugin.areaPvp(a,b));
            when(groups.areaRelation(aId,bId)).thenReturn(AdventureGroupRelation.UNKNOWN); assertEquals(Verdict.UNKNOWN,plugin.areaPvp(a,b));
            when(groups.areaRelation(aId,bId)).thenReturn(AdventureGroupRelation.DIFFERENT);
            when(peace.peaceful(aId)).thenReturn(true); assertEquals(Verdict.DENY,plugin.areaPvp(a,b));
            when(peace.peaceful(aId)).thenReturn(false); when(peace.protects(eq(bId),anyLong())).thenReturn(true);
            assertEquals(Verdict.DENY,plugin.areaPvp(a,b));
            when(payments.paused()).thenReturn(true); assertEquals(Verdict.UNKNOWN,plugin.areaPvp(a,b));
        }
        verify(peace,never()).attack(any(),any(),anyBoolean(),anyBoolean(),anyBoolean(),any(),anyString(),anyLong());
    }
}
