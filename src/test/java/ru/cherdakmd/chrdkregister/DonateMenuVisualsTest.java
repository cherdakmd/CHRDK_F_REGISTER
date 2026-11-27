package ru.cherdakmd.chrdkregister;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DonateMenuVisualsTest {
    @Test void fixedTierAndHistoryIconsRespectMetadata() {
        var keys=new HashSet<NamespacedKey>();for(var icon:DonateMenuVisuals.Icon.values())assertTrue(keys.add(icon.model()));assertEquals(13,keys.size());
        for(int i=0;i<7;i++)assertEquals("tier_"+(i+1),DonateMenuVisuals.tier(i).model().getKey().substring("menu/donate/".length()));
        assertNull(DonateMenuVisuals.tier(-1));assertNull(DonateMenuVisuals.tier(7));assertNull(DonateMenuVisuals.history(null));
        assertEquals(DonateMenuVisuals.Icon.GRANTED,DonateMenuVisuals.history(RegistrationStore.DonationStatus.GRANTED));assertEquals(DonateMenuVisuals.Icon.REJECTED,DonateMenuVisuals.history(RegistrationStore.DonationStatus.REJECTED));
        for(var status:RegistrationStore.DonationStatus.values())if(status.isPending())assertEquals(DonateMenuVisuals.Icon.PENDING,DonateMenuVisuals.history(status));
        var meta=mock(ItemMeta.class);var icon=DonateMenuVisuals.Icon.PAY;DonateMenuVisuals.apply(meta,icon,false);DonateMenuVisuals.apply(null,icon,true);DonateMenuVisuals.apply(meta,null,true);verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other","model"));DonateMenuVisuals.apply(meta,icon,true);verify(meta,never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null);when(meta.hasCustomModelData()).thenReturn(true);DonateMenuVisuals.apply(meta,icon,true);verify(meta,never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);DonateMenuVisuals.apply(meta,icon,true);verify(meta).setItemModel(icon.model());verify(meta,never()).getPersistentDataContainer();
    }
}
