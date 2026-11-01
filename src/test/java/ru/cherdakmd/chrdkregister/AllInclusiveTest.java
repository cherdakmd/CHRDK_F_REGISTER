package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AllInclusiveTest {
    private ShopCatalog defaults() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(Path.of("src/main/resources/config.yml").toFile());
        return ShopCatalog.from(yaml);
    }

    @Test void fullPackIsOneSaleWithRootRankAndThirteenDistinctExtras() throws Exception {
        var catalog = defaults();
        var full = catalog.find("all-inclusive").orElseThrow();
        assertEquals(899, full.priceRubles());
        assertEquals(30, full.days());
        assertEquals(5, full.rank());
        assertTrue(full.isBundle());
        assertEquals(13, full.resolvedIncludes().size());
        assertEquals(13, new java.util.HashSet<>(full.resolvedIncludes()).size());
        assertTrue(ShopDeliveryPolicy.complete(full, catalog));
        assertTrue(full.resolvedIncludes().containsAll(List.of("homes-plus", "join-effect", "battlepass-premium")));
        assertFalse(full.resolvedIncludes().stream().anyMatch(id -> id.startsWith("rank-")));
    }

    @Test void includedBattlePassIsThirtyDaysButStandaloneStaysNinety() throws Exception {
        var catalog = defaults();
        var full = catalog.find("all-inclusive").orElseThrow();
        var pass = catalog.find("battlepass-premium").orElseThrow();
        assertEquals(90, pass.days());
        assertEquals(30, full.includedDays(pass));
        for (String id : full.resolvedIncludes()) assertEquals(30, full.includedDays(catalog.find(id).orElseThrow()));
        assertEquals(30, catalog.find("look-pack").orElseThrow().includedDays(catalog.find("nick-aurora").orElseThrow()));
    }

    @Test void rankPlanRenewsVOrUpgradesLowerRankButRejectsPermanentV() throws Exception {
        var full = defaults().find("all-inclusive").orElseThrow();
        long now = 1_800_000_000_000L, day = ShopEntitlements.DAY_MILLIS;
        var account = new RegistrationStore.PlayerAccount();
        account.rankLevel = 5;
        account.passUntil = now + 10 * day;
        var renewal = ShopDeliveryPolicy.rankPlan(account, full.rank(), full.days(), 0, now, 365, 365, 365);
        assertEquals(now + 40 * day, renewal.until());
        account.rankLevel = 2;
        var upgrade = ShopDeliveryPolicy.rankPlan(account, full.rank(), full.days(), 0, now, 159, 365, 365);
        assertEquals(now + (30 + RankLadder.carryDays(10, 159, 365)) * day, upgrade.until());
        account.rankLevel = 5;
        account.rankPermanent = true;
        assertEquals(ShopService.Status.RANK_HIGHER,
                ShopDeliveryPolicy.rankPlan(account, full.rank(), full.days(), 0, now, 365, 365, 365).status());
    }

    @Test void hybridNeedsExplicitPeriodAndCannotBeNestedAsARanklessLeaf() throws Exception {
        var rank = Map.<String, Object>of("id", "full", "name", "Full", "price", 899,
                "days", 30, "rank", 5, "includes", List.of("item"));
        var leaf = Map.<String, Object>of("id", "item", "name", "Item", "price", 99, "days", 30);
        assertTrue(ShopCatalog.parse(true, 365, List.of(rank, leaf)).find("full").isEmpty());
        var good = new java.util.HashMap<>(rank);
        good.put("includes-days", 30);
        var parent = Map.<String, Object>of("id", "parent", "name", "Parent", "price", 999,
                "days", 30, "includes", List.of("full"));
        var catalog = ShopCatalog.parse(true, 365, List.of(good, leaf, parent));
        assertTrue(ShopDeliveryPolicy.complete(catalog.find("full").orElseThrow(), catalog));
        assertFalse(ShopDeliveryPolicy.complete(catalog.find("parent").orElseThrow(), catalog));
        good.put("includes-days", 366);
        assertTrue(ShopCatalog.parse(true, 365, List.of(good, leaf)).find("full").isEmpty());
    }

    @Test void malformedHybridCannotSilentlyLoseItsRankOrItsContents() {
        var base = Map.<String, Object>of("id", "full", "name", "Full", "price", 899,
                "days", 30, "rank", 5, "includes-days", 30, "includes", List.of("item"));
        var leaf = Map.<String, Object>of("id", "item", "name", "Item", "price", 99, "days", 30);
        for (Object invalid : List.of(-1, 6, "invalid")) {
            var broken = new java.util.HashMap<>(base);
            broken.put("rank", invalid);
            assertTrue(ShopCatalog.parse(true, 365, List.of(broken, leaf)).find("full").isEmpty());
        }
        for (Object invalid : List.of(0, -1, 366, "invalid")) {
            var broken = new java.util.HashMap<>(base);
            broken.put("includes-days", invalid);
            assertTrue(ShopCatalog.parse(true, 365, List.of(broken, leaf)).find("full").isEmpty());
        }
        for (Object invalid : List.of(List.of(), "item")) {
            var broken = new java.util.HashMap<>(base);
            broken.put("includes", invalid);
            assertTrue(ShopCatalog.parse(true, 365, List.of(broken, leaf)).find("full").isEmpty());
        }
    }

    @Test void missingMemberRejectsWholeHybridInsteadOfGrantingOnlyRank() throws Exception {
        var full = Map.<String, Object>of("id", "full", "name", "Full", "price", 899,
                "days", 30, "rank", 5, "includes-days", 30, "includes", List.of("missing"));
        var catalog = ShopCatalog.parse(true, 365, List.of(full));
        assertFalse(ShopDeliveryPolicy.complete(catalog.find("full").orElseThrow(), catalog));
    }

    @Test void legacyConstructorsKeepLeafPeriods() {
        var legacy = new ShopProduct("kit", "Kit", "", 199, 30, "", "", List.of(), List.of(),
                List.of("pass"), List.of("pass"), 0, 0);
        var pass = new ShopProduct("pass", "Pass", "", 249, 90, "", "", List.of(), List.of());
        assertEquals(0, legacy.includedDays());
        assertEquals(90, legacy.includedDays(pass));
    }

    @Test void bothPreflightsRunBeforeRankOrLeafMutationAndSaleIsRecordedOnlyOnce() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/ShopService.java"));
        int firstRankGrant = source.indexOf("until = grantRankProduct(");
        int preflight = source.indexOf("!ShopDeliveryPolicy.fitsBundle(product, catalog, now, length");
        assertTrue(preflight >= 0 && preflight < firstRankGrant);
        assertTrue(source.contains("int partDays = product.includedDays(part)"));
        assertTrue(source.contains("grantOne(canonical, part, now, partDays, discount)"));
        assertTrue(source.contains("if (!product.hasRank() && !product.retainsBundleEntitlement()) {\n                until = now;"));
        assertEquals(1, source.split("recordShopSale\\(", -1).length - 1);
        assertTrue(source.contains("!product.isBundle() && product.rank() == level"));
        assertTrue(source.contains("product.hasRank() && product.isBundle()) return false"));
        String menu = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/RankMenuService.java"));
        assertTrue(menu.contains("product.isBundle() || product.rank() != level"));
    }
}
