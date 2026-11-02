package ru.cherdakmd.chrdkhunt;

import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EntityType;

/** Шесть коллекционных разновидностей, без нового уровня силы и без наложения элит. */
enum WildVariant {
    ASH("ash", EntityType.HUSK, Material.SAND), FROST("frost", EntityType.STRAY, Material.POWDER_SNOW_BUCKET),
    THICKET("thicket", EntityType.SPIDER, Material.COBWEB), BOG("bog", EntityType.DROWNED, Material.LILY_PAD),
    CRIMSON("crimson", EntityType.HOGLIN, Material.CRIMSON_FUNGUS), RIFT("rift", EntityType.ENDERMAN, Material.ENDER_PEARL);
    final String id; final EntityType type; final Material icon;
    WildVariant(String id, EntityType type, Material icon) { this.id = id; this.type = type; this.icon = icon; }
    static WildVariant byId(String id) {
        if (id != null) for (var v : values()) if (v.id.equals(id)) return v;
        return null;
    }
    static WildVariant habitat(EntityType type, World.Environment world, String biome) {
        if (biome == null) return null;
        String b = biome.toLowerCase(Locale.ROOT);
        if (world == World.Environment.NETHER) return type == EntityType.HOGLIN && b.equals("crimson_forest") ? CRIMSON : null;
        if (world == World.Environment.THE_END) return type == EntityType.ENDERMAN && !b.equals("the_void") ? RIFT : null;
        if (world != World.Environment.NORMAL) return null;
        if (type == EntityType.HUSK && b.equals("desert")) return ASH;
        if (type == EntityType.STRAY && (b.startsWith("snowy_") || b.contains("frozen") || b.equals("ice_spikes") || b.equals("grove") || b.equals("jagged_peaks"))) return FROST;
        if ((type == EntityType.ZOMBIE || type == EntityType.DROWNED) && (b.equals("swamp") || b.equals("mangrove_swamp"))) return BOG;
        if (type == EntityType.SPIDER && (b.contains("forest") || b.contains("jungle") || b.endsWith("taiga") || b.equals("grove") || b.equals("pale_garden"))) return THICKET;
        return null;
    }
}
