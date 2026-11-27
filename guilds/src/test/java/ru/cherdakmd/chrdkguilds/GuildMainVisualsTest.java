package ru.cherdakmd.chrdkguilds;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GuildMainVisualsTest {
    @Test void fixedModelsFollowLevelWarAndMetadataFallback() {
        var keys=new HashSet<NamespacedKey>(); for(var value:GuildMainVisuals.Control.values())assertTrue(keys.add(value.model())); assertEquals(22,keys.size());
        assertEquals(GuildMainVisuals.Control.INFO_PEACEFUL,GuildMainVisuals.info(true)); assertEquals(GuildMainVisuals.Control.INFO_BATTLE,GuildMainVisuals.info(false));
        assertEquals(GuildMainVisuals.Control.UPGRADE_AVAILABLE,GuildMainVisuals.upgrade(1)); assertEquals(GuildMainVisuals.Control.UPGRADE_MAX,GuildMainVisuals.upgrade(GuildMath.MAX_LEVEL));
        assertEquals(GuildMainVisuals.Control.WAR_NONE,GuildMainVisuals.war(null,false)); assertEquals(GuildMainVisuals.Control.WAR_COOLDOWN,GuildMainVisuals.war(null,true));
        var war=mock(GuildWar.class); when(war.pending()).thenReturn(true); assertEquals(GuildMainVisuals.Control.WAR_PENDING,GuildMainVisuals.war(war,false)); when(war.pending()).thenReturn(false); assertEquals(GuildMainVisuals.Control.WAR_ACTIVE,GuildMainVisuals.war(war,false));
        assertNull(GuildMainVisuals.button("menu-war-name")); var meta=mock(ItemMeta.class); var control=GuildMainVisuals.Control.TREASURY;
        GuildMainVisuals.apply(meta,control,false);GuildMainVisuals.apply(null,control,true);GuildMainVisuals.apply(meta,null,true);verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other","model"));GuildMainVisuals.apply(meta,control,true);verify(meta,never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null);when(meta.hasCustomModelData()).thenReturn(true);GuildMainVisuals.apply(meta,control,true);verify(meta,never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);GuildMainVisuals.apply(meta,control,true);verify(meta).setItemModel(control.model());verify(meta,never()).getPersistentDataContainer();
    }
}
