package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ClanPack279Test {
    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = ShopEntitlements.DAY_MILLIS;
    private YamlConfiguration yaml() throws Exception {
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
    }
    @Test void bundleHasThreeLeavesAndItsOwnBonusButOldBundlesDoNot() throws Exception {
        var cat = ShopCatalog.from(yaml());
        var pack = cat.find("clan-style-pack").orElseThrow();
        assertEquals(449, pack.priceRubles()); assertEquals(30, pack.days());
        assertEquals(30, pack.includedDays()); assertTrue(pack.retainsBundleEntitlement());
        assertEquals(Set.of("clan-name-style", "clan-emblem", "clan-card-frame"), Set.copyOf(pack.resolvedIncludes()));
        assertTrue(ShopDeliveryPolicy.complete(pack, cat));
        assertFalse(pack.hasRank()); assertFalse(pack.hasGroup()); assertFalse(pack.hasTag());
        assertFalse(pack.hasCommands()); assertTrue(pack.permissions().isEmpty());
        assertTrue(cat.products().stream().filter(p -> !p.id().equals(pack.id())).noneMatch(ShopProduct::retainsBundleEntitlement));
        assertFalse(cat.find("all-inclusive").orElseThrow().resolvedIncludes().contains(pack.id()));
    }
    @Test void eachOfFourPeriodsMustFitBeforeAnyGrantAndOverrideCannotShortenBonus() throws Exception {
        var cat = ShopCatalog.from(yaml()); var pack = cat.find("clan-style-pack").orElseThrow();
        var rights = new ArrayList<>(pack.resolvedIncludes()); rights.add(pack.id());
        for (String blocked : rights) {
            Map<String,Long> owned = new HashMap<>(); owned.put(blocked, NOW + 335 * DAY + 1);
            Map<String,Long> before = Map.copyOf(owned);
            assertFalse(ShopDeliveryPolicy.fitsBundle(pack, cat, NOW, 30, p -> owned.getOrDefault(p.id(), 0L)), blocked);
            assertEquals(before, owned);
        }
        assertTrue(ShopDeliveryPolicy.fitsBundle(pack, cat, NOW, 30, p -> NOW + 335 * DAY));
        assertFalse(ShopDeliveryPolicy.fitsBundle(pack, cat, NOW, 1, p -> 0));
    }
    @Test void malformedReservedRecipeAndNestingCannotSilentlyLoseTheBonus() throws Exception {
        for (Map<String,Object> change : List.of(
                Map.<String,Object>of("commands", List.of("say invalid")),
                Map.<String,Object>of("includes", List.of("clan-emblem")),
                Map.<String,Object>of("days", 7), Map.<String,Object>of("group", "custom"))) {
            var cfg = yaml();
            var products = new ArrayList<Map<?,?>>(cfg.getMapList("shop.products"));
            for (int index = 0; index < products.size(); index++) if ("clan-style-pack".equals(products.get(index).get("id"))) {
                var altered = new LinkedHashMap<Object,Object>(products.get(index));
                altered.putAll(change); products.set(index, altered);
            }
            cfg.set("shop.products", products);
            var catalog = ShopCatalog.from(cfg); var pack = catalog.find("clan-style-pack").orElseThrow();
            assertFalse(pack.retainsBundleEntitlement()); assertFalse(ShopDeliveryPolicy.complete(pack, catalog));
        }
        var cfg = yaml(); var products = new ArrayList<Map<?,?>>(cfg.getMapList("shop.products"));
        products.add(Map.of("id", "nested-clan", "name", "Nested", "price", 1000, "days", 30,
                "includes-days", 30, "includes", List.of("clan-style-pack")));
        cfg.set("shop.products", products); var cat = ShopCatalog.from(cfg);
        assertFalse(ShopDeliveryPolicy.complete(cat.find("nested-clan").orElseThrow(), cat));
    }
    @Test void liveChatStatusDoesNotConsumeOrDuplicatePersonalSlotsAndHonorsHiding(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        var prefs = new LabelPreferences(dir, Logger.getAnonymousLogger()); prefs.load();
        prefs.setSlots("Steve", List.of("classes", "achievements", "guilds"));
        var lookup = new LabelStyleService.RankLookup() {
            public LabelStyleService.Rank view(String name) { return null; }
            public List<String> registrationOrder() { return List.of("classes", "achievements", "guilds"); }
        };
        var service = new LabelStyleService((id, name, anchor) -> switch (id) {
            case "classes" -> "[Класс] " + anchor;
            case "achievements" -> "[Титул] " + anchor;
            case "guilds", "guilds-chat", "guilds-tab" -> "<red>[<aqua>✦ TAG</aqua>]</red> " + anchor;
            default -> anchor;
        }, prefs, lookup);
        var before = service.assignments("Steve");
        String chat = Messages.stripFormatting(service.decorate("Steve", "Steve", null));
        assertEquals("Steve · [Класс] · [Титул] · [✦ TAG]", chat);
        assertEquals(before, service.assignments("Steve"));
        prefs.setSlots("Steve", List.of("guilds", "classes"));
        chat = Messages.stripFormatting(service.decorate("Steve", "Steve", null));
        assertEquals(1, chat.split("TAG", -1).length - 1);
        prefs.set("Steve", LabelPreferences.Label.GUILDS, false);
        assertFalse(Messages.stripFormatting(service.decorate("Steve", "Steve", null)).contains("TAG"));
        assertTrue(Messages.stripFormatting(service.decorateForTab("Steve", "Steve", null)).contains("TAG"));
    }
}
