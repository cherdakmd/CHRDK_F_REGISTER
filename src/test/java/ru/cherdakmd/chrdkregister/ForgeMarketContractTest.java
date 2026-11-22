package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** RC16/API42: frozen grant и whitelist Кузни. Source-inspection, не native CraftItemStack codec. */
class ForgeMarketContractTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test void api46KeepsClaimLineageAndApi44CatalogContracts() throws Exception {
        String api = read("src/main/java/ru/cherdakmd/chrdkregister/api/ChrdkRegisterApi.java");
        assertTrue(api.contains("int API_VERSION = 48;"));
        assertTrue(api.contains("UUID claimSourceLineage()"));
        assertTrue(api.contains("long claimExchangeSequence(UUID lineage)"));
        assertFalse(api.contains("forgeMaterialSource()"),
                "API46: мост Кузни снят вместе с модулем CHRDK_TierForge");
    }

    @Test void catalogHasExactlyEightWhitelistedSkusAndValidatesCanonicalSeed() throws Exception {
        String code = read("src/main/java/ru/cherdakmd/chrdkregister/api/ForgeMarketCatalog.java");
        var matcher = Pattern.compile(
                "new Sku\\(\"([a-z]+)\", \"([A-Z_]+)\", (\\d+), (true|false), (true|false)\\)")
                .matcher(code);
        int count = 0;
        while (matcher.find()) count++;
        assertEquals(8, count);
        for (String row : new String[]{
                "new Sku(\"ascend\", \"ECHO_SHARD\", 128, false, false)",
                "new Sku(\"temper\", \"NAUTILUS_SHELL\", 96, false, false)",
                "new Sku(\"reforge\", \"PAPER\", 160, false, false)",
                "new Sku(\"seed\", \"NETHER_STAR\", 64, true, false)",
                "new Sku(\"repair\", \"IRON_INGOT\", 24, false, false)",
                "new Sku(\"inscribe\", \"GOLD_NUGGET\", 192, false, false)",
                "new Sku(\"remove\", \"GUNPOWDER\", 32, false, false)",
                "new Sku(\"name\", \"INK_SAC\", 16, false, false)"}) {
            assertTrue(code.contains(row), row);
        }
        assertTrue(code.contains("validPrice(long price)"));
        assertTrue(code.contains("canonicalUuid(String value)"));
        assertTrue(code.contains("PersistentDataType.STRING"));
        assertTrue(code.contains("getMaxStackSize() != 1 && !marked"));
        assertTrue(code.contains("ChargeInventory.encode(items)"));
        assertTrue(code.contains("new ItemStack[36]"));
    }

    @Test void shopExchangeKeepsLegacyConstructorsAndNullableGrant() throws Exception {
        String code = read("src/main/java/ru/cherdakmd/chrdkregister/api/ShopExchange.java");
        assertEquals(4, count(code, "public ShopExchange(UUID t,UUID m,long s,UUID o,boolean buy"));
        assertTrue(code.contains("String grant)"));
        assertTrue(code.contains("public boolean carriesGrant() { return grant != null; }"));
        assertTrue(code.contains("ForgeMarketCatalog.validTradeGrant(grant,material,units)"));
        assertTrue(code.contains("row.has(\"grant\") && !row.get(\"grant\").isJsonNull()"));
        assertTrue(code.contains("ForgeMarketCatalog.validPrototype(v.getAsString())"));
    }

    @Test void shopInventoryReplaysExactCloneInsertionAndComparesFullNativeAfter() throws Exception {
        String code = read("src/main/java/ru/cherdakmd/chrdkregister/api/ShopInventory.java");
        assertTrue(code.contains("public static ItemStack[] replay(ShopExchange t)"));
        assertTrue(code.contains("public static boolean insertExact(ItemStack[] items, ItemStack prototype, int units)"));
        assertTrue(code.contains("stack.isSimilar(prototype)"));
        assertTrue(code.contains("ChargeInventory.matches(items, t.after())"));
        assertTrue(code.contains("ForgeMarketCatalog.prototype(t.grant())"));
        String bank = read("src/main/java/ru/cherdakmd/chrdkregister/BankService.java");
        assertTrue(bank.contains("ShopInventory.replay(t)"));
        assertTrue(bank.contains("ChargeInventory.matches(replayed,t.after())"));
    }

    @Test void previousWalIsUsedAndRecoveryNeedsNoLiveProducer() throws Exception {
        String store = read("diamond-shop/src/main/java/ru/cherdakmd/diamondshop/ShopStore.java");
        String journal = read("diamond-shop/src/main/java/ru/cherdakmd/diamondshop/ShopJournal.java");
        assertFalse(store.contains("prepareForge("),
                "новые forge-квитанции не создаются: Кузня вырезана");
        assertTrue(store.contains("if(!p.forge())"),
                "зависшие forge-квитанции довершаются без вмешательства в склад");
        assertTrue(journal.contains("boolean forge"));
        assertTrue(journal.contains("if(forge) return valid()"));
        assertTrue(journal.contains("this(trade,stock,sold,bought,time,day,used,turnover,price,historyPoints,historyInterval,false)"));
        assertTrue(journal.contains("ShopJournal.recover(store,new ShopJournal.Bank()")
                || journal.contains("static boolean recover(ShopStore store,Bank bank)"));
    }

    private static int count(String text, String needle) {
        int count = 0, index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) { count++; index += needle.length(); }
        return count;
    }
}
