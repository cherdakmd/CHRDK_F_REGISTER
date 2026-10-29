package ru.cherdakmd.diamondshop;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
class WholeTradesTest {
    @Test void legacyRoundUpIsExplicitAndBounded() {
        assertEquals(13,WholeTrades.legacy(12.35)); assertEquals(1,WholeTrades.legacy(.20));
        assertEquals(0,WholeTrades.legacy(0)); assertEquals(12,WholeTrades.legacy(12));
        assertEquals(-1,WholeTrades.legacy(Double.NaN)); assertEquals(-1,WholeTrades.legacy(-1));
        assertEquals(-1,WholeTrades.legacy(Double.POSITIVE_INFINITY)); assertEquals(-1,WholeTrades.legacy(WholeTrades.MAX+.1));
    }
    @Test void accountFirstAndPhysicalChangeConserveWholeDiamonds() {
        assertEquals(new WholeTrades.Payment(7,3,0),WholeTrades.payment(10,7,3));
        assertEquals(new WholeTrades.Payment(7,9,6),WholeTrades.payment(10,7,9));
        assertEquals(new WholeTrades.Payment(10,0,0),WholeTrades.payment(10,20,0));
        assertNull(WholeTrades.payment(10,7,2)); assertNull(WholeTrades.payment(-1,7,0));
        for(int wallet=0;wallet<40;wallet++) for(int cost=1;cost<40;cost++) for(int physical=0;physical<40;physical++) {
            var p=WholeTrades.payment(cost,wallet,physical);
            if(wallet+physical<cost) assertNull(p);
            else { assertNotNull(p); assertEquals(cost,p.account()+p.physical()-p.change()); assertEquals(Math.min(wallet,cost),p.account()); }
        }
    }
    @Test void roundedAskNeverBelowBidAndBatchesNeverTradeForZeroAtAnyDefaultStock() throws Exception {
        try(var input=getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            var yaml=YamlConfiguration.loadConfiguration(new InputStreamReader(input,StandardCharsets.UTF_8));
            double spread = yaml.getDouble("pricing.spread-percent", 10);
            var rows = yaml.getMapList("items"); assertTrue(rows.size() >= 120);
            for(var row:rows) {
                double min = row.get("min-multiplier") instanceof Number n ? n.doubleValue() : yaml.getDouble("defaults.min-multiplier", .25);
                double max = row.get("max-multiplier") instanceof Number n ? n.doubleValue() : yaml.getDouble("defaults.max-multiplier", 4);
                var item = new ShopConfig.ShopItem(row.get("material").toString(), row.get("category").toString(),
                    ((Number) row.get("base-price")).doubleValue(), ((Number) row.get("ref-stock")).longValue(), min, max);
                int batch=WholeTrades.batch(PriceEngine.bid(PriceEngine.round(item.basePrice()*Math.min(1,item.minMultiplier())),spread));
                assertTrue(batch>0 && batch<=2304,item.material());
                for(long stock:new long[]{1,item.refStock(),Long.MAX_VALUE}) {
                    double mid=PriceEngine.price(item.basePrice(),stock,item.refStock(),item.minMultiplier(),item.maxMultiplier());
                    long ask=WholeTrades.price(PriceEngine.ask(mid,spread),batch,true);
                    long bid=WholeTrades.price(PriceEngine.bid(mid,spread),batch,false);
                    assertTrue(bid>=1 && ask>=bid,item.material()+" "+stock+" "+ask+"/"+bid);
                }
            }
        }
        assertEquals(0,WholeTrades.batch(0)); assertEquals(-1,WholeTrades.price(Double.NaN,1,true));
        assertEquals(-1,WholeTrades.price(WholeTrades.MAX+1d,1,true));
    }
    @Test void physicalPlanUsesSmallDenominationsAndNeverMutatesInputsOrSpendsForFree() {
        int[] amounts={2,2,10}, values={9,1,0};
        var p=WholeTrades.take(amounts,values,3); assertNotNull(p); assertEquals(11,p.paid());
        assertArrayEquals(new int[]{1,0,10},p.after()); assertArrayEquals(new int[]{2,2,10},amounts);
        assertNull(WholeTrades.take(amounts,values,21));
        assertArrayEquals(amounts,WholeTrades.take(amounts,values,0).after());
        for(int gems=0;gems<=10;gems++) for(int blocks=0;blocks<=10;blocks++) for(int need=0;need<=100;need++) {
            var plan=WholeTrades.take(new int[]{blocks,gems},new int[]{9,1},need);
            if(gems+9*blocks<need) assertNull(plan);
            else { assertNotNull(plan); assertEquals(plan.paid(), 9L*(blocks-plan.after()[0])+gems-plan.after()[1]); assertTrue(plan.paid()>=need); assertTrue(plan.paid()-need<9); }
        }
        assertNull(WholeTrades.take(new int[]{65},new int[]{1},1)); assertNull(WholeTrades.take(null,values,1));
    }
    @Test void corruptLegacyBalanceFailsClosedWithoutDestroyingOriginal(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        var file=dir.resolve("shop-data.json"); String bad="{\"balances\":{\"broken-uuid\":12.35}}";
        Files.writeString(file,bad); var store=new ShopStore(dir,Logger.getLogger("WholeTradesTest")); store.load();
        assertFalse(store.legacyAvailable()); store.saveNow(); assertEquals(bad,Files.readString(file));
    }
    @Test void marketSaveDoesNotRoundOrTruncateFrozenLegacyInput(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        var id=UUID.randomUUID(); var file=dir.resolve("shop-data.json");
        Files.writeString(file,"{\"balances\":{\""+id+"\":1.0E30}}");
        var store=new ShopStore(dir,Logger.getLogger("WholeTradesTest")); store.load();
        assertTrue(store.legacyAvailable()); assertEquals(1e30,store.balanceOf(id));
        assertEquals(-1,WholeTrades.legacy(store.balanceOf(id)));
        store.setStock(new ShopConfig.ShopItem("STONE", "blocks", 1, 1000, 0.25, 4), 500, 1000); store.saveNow();
        var reloaded=new ShopStore(dir,Logger.getLogger("WholeTradesTest")); reloaded.load(); assertEquals(1e30,reloaded.balanceOf(id));
    }
    @Test void legacyCustomLoreCannotMislabelNewBatchPrices(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        var file=dir.resolve("messages.yml");
        Files.writeString(file,"messages:\n  shop:\n    gui:\n      item-lore:\n        - 'Цена за штуку, Shift 64'\n");
        try(var input=getClass().getResourceAsStream("/messages.yml")) {
            var messages=ru.cherdakmd.chrdkregister.Messages.from(input,file,Logger.getLogger("WholeTradesTest"));
            assertTrue(messages.raw("shop.gui.item-lore").contains("за штуку"));
            var current=messages.raw("trading.gui.item-lore");
            assertEquals(4,messages.getList("trading.gui.item-lore", "batch",256,"ask",6,"bid",4).size());
            assertTrue(current.contains("%batch%"));
            assertTrue(current.contains("16 партий"));
            assertTrue(messages.raw("trading.balance").contains("/bal"));
        }
    }
    @Test void serviceHasNoSpendableShopWalletNoDropsAndCommitsPreparedSnapshots() throws Exception {
        var s=Files.readString(Path.of("src/main/java/ru/cherdakmd/diamondshop/ShopService.java"));
        for(var forbidden:List.of("store.trySpend", "store.addBalance", "dropItem", "getContents()")) assertFalse(s.contains(forbidden),forbidden);
        for(var guard:List.of("getStorageContents()", "!item.hasItemMeta()", "exchangeDiamonds(player, debit, credit, before, after)", "canRecordTurnover", "countItems(player.getInventory(), material) / batch")) assertTrue(s.contains(guard),guard);
        assertTrue(s.indexOf("canRecordTurnover")<s.indexOf("exchangeDiamonds"));
        var gui=Files.readString(Path.of("src/main/java/ru/cherdakmd/diamondshop/ShopGui.java"));
        for(var guard:List.of("holder.service != service", "offer.ask() !=", "offer.bid() !=", "getTopInventory() == expected")) assertTrue(gui.contains(guard),guard);
    }
}
