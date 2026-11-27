package ru.cherdakmd.chrdkregister;

import java.util.Locale;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Virtual label controls; custom palette previews and player heads remain untouched. */
final class LabelMenuVisuals {
    enum Control { PREVIEW, COLOR, ORDER, PRESETS, ENABLE, DISABLE, PROFILE, RESET, COSMETICS, CLOSE, BACK, INFO }
    enum State { ON, OFF, UNAVAILABLE, WAITING, UNASSIGNED }
    enum Place { LOCKED, EMPTY, ASSIGNED }
    enum Pick { AVAILABLE, SELECTED, UNAVAILABLE }
    private LabelMenuVisuals() { }
    private static NamespacedKey key(String id) { return new NamespacedKey("chrdk_core", "menu/labels/"+id); }
    private static String id(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
    static NamespacedKey control(Control value) { return value==null?null:key(id(value)); }
    static NamespacedKey label(LabelPreferences.Label label,State state) { return label==null||state==null?null:key("label_"+id(label)+"_"+id(state)); }
    static NamespacedKey place(Place value) { return value==null?null:key("place_"+id(value)); }
    static NamespacedKey picker(LabelPreferences.Label label,Pick state) {
        return label==null||label.group()!=LabelPreferences.Group.NAME||state==null?null:key("pick_"+id(label)+"_"+id(state));
    }
    static NamespacedKey preset(boolean filled) { return key(filled?"preset_filled":"preset_empty"); }
    static State state(boolean visible,LabelPreferences.Label label,LabelStyleService.Selection selection) {
        if(!visible)return State.OFF;
        if(label==LabelPreferences.Label.HEADER||selection==null||selection.displayed(label))return State.ON;
        if(!selection.obtained(label))return State.UNAVAILABLE;
        return selection.deferred(label)?State.WAITING:State.UNASSIGNED;
    }
    static void apply(ItemMeta meta,NamespacedKey model,boolean enabled) {
        if(enabled&&meta!=null&&model!=null&&meta.getItemModel()==null&&!meta.hasCustomModelData())meta.setItemModel(model);
    }
}
