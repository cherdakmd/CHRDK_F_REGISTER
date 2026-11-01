package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PremiumPresentation278Test {
    private YamlConfiguration yaml(String resource) throws Exception {
        try (var input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input); return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
    }
    @Test void allFiveRanksHaveTwentyDistinctLinesAndNeverRepeatImmediately() throws Exception {
        for (RankSettings settings : List.of(RankSettings.defaults(), RankSettings.from(yaml("/config.yml")), RankSettings.from(yaml("/premium-legacy-1.4.277.yml")))) {
            for (int rank = 1; rank <= 5; rank++) {
                assertEquals(20, settings.joinPool(rank).size());
                assertEquals(20, new HashSet<>(settings.joinPool(rank)).size());
                String last = "";
                for (int repeat = 0; repeat < 100; repeat++) {
                    String next = settings.nextJoinLine(rank, "Steve");
                    assertTrue(next.contains("Steve")); assertFalse(next.contains("%player%"));
                    assertNotEquals(last, next); last = next;
                }
            }
            assertEquals("", settings.nextJoinLine(0,"Steve"));
        }
    }
    @Test void customSingleLineAndExplicitDisabledPoolAreKept() {
        var cfg = new YamlConfiguration();
        cfg.set("ranks.levels", List.of(Map.of("level",1,"id","custom","join-line","Свой %player%"),
                Map.of("level",2,"id","muted","join-line","ignored","join-lines",List.of())));
        var settings = RankSettings.from(cfg);
        assertEquals("Свой Steve",settings.nextJoinLine(1,"Steve"));
        assertEquals("",settings.nextJoinLine(2,"Steve"));
        assertTrue(settings.nextJoinLine(1,"<red>").contains("\\<red>"));
    }
    @Test void stockMigrationIsIdempotentAndDoesNotAlterOldProducts() throws Exception {
        var old=yaml("/premium-legacy-1.4.277.yml"); var live=yaml("/premium-legacy-1.4.277.yml"); var fresh=yaml("/config.yml");
        var before=live.getMapList("shop.products");
        assertTrue(PremiumPresentationMigration.update(live,old,fresh));
        assertEquals(fresh.get("ranks.levels"),live.get("ranks.levels"));
        assertEquals(29,live.getMapList("shop.products").size());
        assertEquals(before,live.getMapList("shop.products").subList(0,26));
        assertFalse(PremiumPresentationMigration.update(live,old,fresh));
    }
    @Test void customRanksPricesAndExistingReservedSkuAreNeverOverwritten() throws Exception {
        var old=yaml("/premium-legacy-1.4.277.yml"); var live=yaml("/premium-legacy-1.4.277.yml"); var fresh=yaml("/config.yml");
        var tiers=List.of(Map.of("level",1,"id","owner","join-line","Собственная строка")); live.set("ranks.levels",tiers);
        var products=new ArrayList<Map<?,?>>(live.getMapList("shop.products"));
        products.add(Map.of("id","clan-emblem","price",777,"days",42)); live.set("shop.products",products);
        PremiumPresentationMigration.update(live,old,fresh);
        assertEquals(tiers,live.get("ranks.levels")); assertEquals(29,live.getMapList("shop.products").size());
        assertEquals(products,live.getMapList("shop.products").subList(0,27));
    }
    @Test void clanLicensesArePersonalThirtyDayCosmeticsWithNoConsoleCommands() throws Exception {
        var cfg=yaml("/config.yml");
        var products=cfg.getMapList("shop.products");
        for (var product:products) if(PremiumPresentationMigration.PRODUCTS.contains(product.get("id"))) {
            assertEquals(30,product.get("days")); assertEquals(false,product.get("trademc-export"));
            assertEquals(List.of(),product.get("permissions")); assertEquals(List.of(),product.get("commands"));
            assertFalse(product.containsKey("rank"));
        }
        var all=products.stream().filter(p->"all-inclusive".equals(p.get("id"))).findFirst().orElseThrow();
        assertEquals(899,all.get("price")); assertFalse(all.get("includes").toString().contains("clan-"));
    }
}
