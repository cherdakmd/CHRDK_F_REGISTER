package ru.cherdakmd.chrdkregister;

import java.util.HashSet;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminDonationVisualsTest {
    @Test void controlsFiltersAndAllRecordStatesHaveDistinctModels() {
        var keys=new HashSet<NamespacedKey>();for(var control:AdminDonationVisuals.Control.values())assertTrue(keys.add(AdminDonationVisuals.control(control)));
        for(var filter:AdminDonationsGuiService.Filter.values())assertTrue(keys.add(AdminDonationVisuals.filter(filter)));
        for(var status:RegistrationStore.DonationStatus.values())assertTrue(keys.add(AdminDonationVisuals.status(status)));assertEquals(16,keys.size());
        assertEquals(AdminDonationVisuals.Control.STATUS_DOWN,AdminDonationVisuals.health(false,false,false));assertEquals(AdminDonationVisuals.Control.STATUS_DOWN,AdminDonationVisuals.health(true,true,true));
        assertEquals(AdminDonationVisuals.Control.STATUS_FLAKY,AdminDonationVisuals.health(true,true,false));assertEquals(AdminDonationVisuals.Control.STATUS_OK,AdminDonationVisuals.health(true,false,false));
        var meta=mock(ItemMeta.class);var key=AdminDonationVisuals.control(AdminDonationVisuals.Control.EMPTY);
        AdminDonationVisuals.apply(meta,key,false);AdminDonationVisuals.apply(null,key,true);AdminDonationVisuals.apply(meta,null,true);verifyNoInteractions(meta);
        when(meta.getItemModel()).thenReturn(new NamespacedKey("other","art"));AdminDonationVisuals.apply(meta,key,true);verify(meta,never()).setItemModel(any());
        when(meta.getItemModel()).thenReturn(null);when(meta.hasCustomModelData()).thenReturn(true);AdminDonationVisuals.apply(meta,key,true);verify(meta,never()).setItemModel(any());
        when(meta.hasCustomModelData()).thenReturn(false);AdminDonationVisuals.apply(meta,key,true);verify(meta).setItemModel(key);verify(meta,never()).getPersistentDataContainer();
    }
}
