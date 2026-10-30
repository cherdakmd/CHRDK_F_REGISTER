package ru.cherdakmd.diamondshop;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ShopCatalogMigrationTest {
    private YamlConfiguration yaml(String name) throws Exception {
        try (var stream=getClass().getResourceAsStream("/"+name)) {
            assertNotNull(stream); var yaml=new YamlConfiguration();
            yaml.load(new InputStreamReader(stream,StandardCharsets.UTF_8)); return yaml;
        }
    }
    @Test void unchangedCatalogUpgradesOnceAndOtherSettingsStay() throws Exception {
        var live=yaml("catalog-legacy-1.4.244.yml"); var old=yaml("catalog-legacy-1.4.244.yml"); var fresh=yaml("config.yml");
        live.set("pricing.spread-percent",17); live.set("pricing.daily-turnover-limit",300);
        assertEquals(142,live.getMapList("items").size()); assertEquals(249,fresh.getMapList("items").size());
        assertTrue(ShopCatalogMigration.shouldUpgrade(live,old,fresh));
        live.set("items",fresh.get("items"));
        assertEquals(17,live.getInt("pricing.spread-percent")); assertEquals(300,live.getInt("pricing.daily-turnover-limit"));
        assertFalse(ShopCatalogMigration.shouldUpgrade(live,old,fresh));
    }
    @Test void previousCatalogHasExactMigrationAndNewTerrainAndOreCoverage() throws Exception {
        var old=yaml("catalog-legacy-1.4.249.yml"); var fresh=yaml("config.yml");
        assertTrue(ShopCatalogMigration.shouldUpgrade(old,old,fresh));
        assertTrue(fresh.getMapList("items").containsAll(old.getMapList("items")));
        assertEquals(71,fresh.getMapList("items").size()-old.getMapList("items").size());
        var materials=new HashSet<String>();
        for(var item:fresh.getMapList("items")) {
            String material=item.get("material").toString(); materials.add(material);
            if(material.endsWith("_ORE") || material.equals("ANCIENT_DEBRIS")) assertEquals(false,item.get("sell-enabled"),material);
        }
        for(String ore:List.of("COAL","IRON","COPPER","GOLD","LAPIS","REDSTONE","DIAMOND","EMERALD")) {
            assertTrue(materials.contains(ore+"_ORE")); assertTrue(materials.contains("DEEPSLATE_"+ore+"_ORE"));
        }
        assertTrue(materials.containsAll(List.of("GRASS_BLOCK","COARSE_DIRT","ROOTED_DIRT","MYCELIUM","PODZOL","MUD","CALCITE","COBBLED_DEEPSLATE")));
    }
    @Test void customizedRemovedAndEmptyCatalogsAreNotOverwritten() throws Exception {
        var old=yaml("catalog-legacy-1.4.244.yml"); var fresh=yaml("config.yml");
        for (int mode=0;mode<3;mode++) {
            var live=yaml("catalog-legacy-1.4.244.yml");
            var rows=new ArrayList<Map<?,?>>(live.getMapList("items"));
            if (mode==0) { var first=new HashMap<Object,Object>(rows.getFirst()); first.put("base-price",99.0); rows.set(0,first); }
            else if (mode==1) rows.removeFirst(); else rows.clear();
            live.set("items",rows); assertFalse(ShopCatalogMigration.shouldUpgrade(live,old,fresh));
        }
        var defaultsOnly=new YamlConfiguration();defaultsOnly.setDefaults(old);
        assertFalse(ShopCatalogMigration.shouldUpgrade(defaultsOnly,old,fresh));
    }
    @Test void allNewItemsAreBuyOnlyAndOldRowsArePreservedExactly() throws Exception {
        var old=yaml("catalog-legacy-1.4.244.yml").getMapList("items"); var fresh=yaml("catalog-legacy-1.4.249.yml").getMapList("items");
        assertTrue(fresh.containsAll(old));
        var added=fresh.stream().filter(i->!old.contains(i)).toList();assertEquals(36,added.size());
        for (var item:added) { assertEquals(false,item.get("sell-enabled"));assertEquals(1.0,item.get("min-multiplier")); }
        assertEquals(16,added.stream().filter(i->i.get("material").toString().endsWith("_CONCRETE")).count());
        assertEquals(16,added.stream().filter(i->i.get("material").toString().endsWith("_TERRACOTTA")).count());
    }
}
