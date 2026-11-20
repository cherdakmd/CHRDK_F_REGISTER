package ru.cherdakmd.chrdkitems;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Validated metadata only: loading the catalog never creates or modifies items. */
final class ItemCatalog {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern MODEL = Pattern.compile("chrdk_items:item/[a-z0-9_/-]+");
    enum Category { ARMOR, WEAPON, TOOL }
    record Definition(String id, Category category, Material material, String model) { }

    static Map<String, Definition> load(ConfigurationSection root) {
        if (root == null || !root.isConfigurationSection("items"))
            throw new IllegalArgumentException("catalog.yml: items must be a section");
        var items = root.getConfigurationSection("items");
        var result = new LinkedHashMap<String, Definition>();
        for (String id : items.getKeys(false)) {
            if (!ID.matcher(id).matches()) throw invalid(id, "invalid id");
            var section = items.getConfigurationSection(id);
            if (section == null || !section.getKeys(false).equals(java.util.Set.of("category", "material", "model")))
                throw invalid(id, "expected exactly category, material and model");
            if (!section.isString("category") || !section.isString("material") || !section.isString("model"))
                throw invalid(id, "fields must be strings");
            Category category;
            try { category = Category.valueOf(section.getString("category").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { throw invalid(id, "unknown category"); }
            String materialName = section.getString("material");
            Material material = Material.getMaterial(materialName);
            if (material == null || !matches(category, material.name()))
                throw invalid(id, "material does not match category");
            String model = section.getString("model");
            if (!MODEL.matcher(model).matches() || model.contains("..") || model.contains("//") || model.endsWith("/"))
                throw invalid(id, "invalid model path");
            result.put(id, new Definition(id, category, material, model));
        }
        return Collections.unmodifiableMap(result);
    }

    private static boolean matches(Category category, String material) {
        boolean armorTier = java.util.Set.of("LEATHER", "CHAINMAIL", "IRON", "GOLDEN", "DIAMOND", "NETHERITE", "TURTLE").contains(material.substring(0, material.indexOf('_') < 0 ? material.length() : material.indexOf('_')));
        boolean toolTier = java.util.Set.of("WOODEN", "STONE", "IRON", "GOLDEN", "DIAMOND", "NETHERITE").contains(material.substring(0, material.indexOf('_') < 0 ? material.length() : material.indexOf('_')));
        return switch (category) {
            case ARMOR -> armorTier && (material.endsWith("_HELMET") || material.endsWith("_CHESTPLATE")
                    || material.endsWith("_LEGGINGS") || material.endsWith("_BOOTS"));
            case WEAPON -> (toolTier && material.endsWith("_SWORD")) || material.equals("BOW")
                    || material.equals("CROSSBOW") || material.equals("TRIDENT") || material.equals("MACE");
            case TOOL -> toolTier && (material.endsWith("_PICKAXE") || material.endsWith("_AXE")
                    || material.endsWith("_SHOVEL") || material.endsWith("_HOE"));
        };
    }
    private static IllegalArgumentException invalid(String id, String reason) {
        return new IllegalArgumentException("catalog.yml: " + id + ": " + reason);
    }
}
