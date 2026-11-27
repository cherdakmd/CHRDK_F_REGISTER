package ru.cherdakmd.chrdkregister;

import java.util.Locale;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Models for virtual journal rows and navigation only; not payment data or actions. */
final class AdminDonationVisuals {
    enum Control { PREV, NEXT, REFRESH, EMPTY, STATUS_OK, STATUS_FLAKY, STATUS_DOWN }
    private AdminDonationVisuals() { }
    private static NamespacedKey key(String id) { return new NamespacedKey("chrdk_core", "menu/admin_donations/"+id); }
    private static String id(Enum<?> e) { return e.name().toLowerCase(Locale.ROOT); }
    static NamespacedKey control(Control e) { return e==null?null:key(id(e)); }
    static NamespacedKey filter(AdminDonationsGuiService.Filter filter) { return filter==null?null:key("filter_"+id(filter)); }
    static NamespacedKey status(RegistrationStore.DonationStatus status) { return status==null?null:key("record_"+id(status)); }
    static Control health(boolean ready,boolean flaky,boolean down) { return !ready||down?Control.STATUS_DOWN:flaky?Control.STATUS_FLAKY:Control.STATUS_OK; }
    static void apply(ItemMeta meta,NamespacedKey model,boolean enabled) {
        if(enabled&&meta!=null&&model!=null&&meta.getItemModel()==null&&!meta.hasCustomModelData())meta.setItemModel(model);
    }
}
