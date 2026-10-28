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
            var config=ShopConfig.parse(yaml,Logger.getLogger("WholeTradesTest")); assertFalse(config.items().isEmpty());
            for(var item:config.items()) {
                int batch=WholeTrades.batch(PriceEngine.bid(PriceEngine.round(item.basePrice()*Math.min(1,item.minMultiplier())),config.spreadPercent()));
                assertTrue(batch>0 && batch<=2304,item.material());
                for(long stock:new long[]{1,item.refStock(),Long.MAX_VALUE}) {
                    double mid=PriceEngine.price(item.basePrice(),stock,item.refStock(),item.minMultiplier(),item.maxMultiplier());
                    long ask=WholeTrades.price(PriceEngine.ask(mid,config.spreadPercent()),batch,true);
                    long bid=WholeTrades.price(PriceEngine.bid(mid,config.spreadPercent()),batch,false);
                    assertTrue(bid>=1 && ask>=bid,item.material()+" "+stock+" "+ask+"/"+bid);
                }
            }
        }
        assertEquals(0,WholeTrades.batch(0)); assertEquals(-1,WholeTrades.price(Double.NaN,1,true));
        assertEquals(-1,WholeTrades.price(WholeTrades.MAX+1d,1,true));
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
