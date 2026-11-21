package ru.cherdakmd.chrdkitems;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Validated metadata only: loading the catalog never creates or modifies items. */
final class ItemCatalog {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern MODEL = Pattern.compile("chrdk_items:item/[a-z0-9_/-]+");
    enum Category { ARMOR, WEAPON, TOOL, CONSUMABLE }
    record Definition(String id, Category category, Material material, String model, String name, List<String> description) {
        Definition { description = List.copyOf(description); }
        Definition(String id, Category category, Material material, String model) {
            this(id, category, material, model, "Прототип: " + id, List.of());
        }
    }

    static Map<String, Definition> load(ConfigurationSection root) {
        if (root == null || !root.isConfigurationSection("items"))
            throw new IllegalArgumentException("catalog.yml: items must be a section");
        var items = root.getConfigurationSection("items");
        var result = new LinkedHashMap<String, Definition>();
        for (String id : items.getKeys(false)) {
            if (!ID.matcher(id).matches()) throw invalid(id, "invalid id");
            var section = items.getConfigurationSection(id);
            if (section == null || !section.getKeys(false).containsAll(Set.of("category", "material", "model"))
                    || !Set.of("category", "material", "model", "name", "description").containsAll(section.getKeys(false)))
                throw invalid(id, "expected category, material, model and optional name and description");
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
            String name = "Прототип: " + id;
            if (section.contains("name")) {
                if (!section.isString("name")) throw invalid(id, "name must be a string");
                name = text(id, section.getString("name"), 64);
            }
            var description = new ArrayList<String>();
            if (section.contains("description")) {
                var lines = section.getList("description");
                if (lines == null || lines.size() > 4) throw invalid(id, "description must have at most four lines");
                for (Object line : lines) {
                    if (!(line instanceof String value)) throw invalid(id, "description lines must be strings");
                    description.add(text(id, value, 160));
                }
            }
            result.put(id, new Definition(id, category, material, model, name, description));
        }
        return Collections.unmodifiableMap(result);
    }

    private static String text(String id, String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.codePoints().anyMatch(Character::isISOControl))
            throw invalid(id, "invalid text");
        return value.strip();
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
            // Neutral bases: no vanilla eating, drinking, placement or projectile use.
            case CONSUMABLE -> java.util.Set.of("PAPER", "CLAY_BALL", "ECHO_SHARD",
                    "AMETHYST_SHARD", "PRISMARINE_CRYSTALS").contains(material);
        };
    }
    private static IllegalArgumentException invalid(String id, String reason) {
        return new IllegalArgumentException("catalog.yml: " + id + ": " + reason);
    }
}
