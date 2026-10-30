package ru.cherdakmd.diamondshop;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ShopServiceEconomyTest {
    @TempDir Path dir;
    private ShopConfig.ShopConfigData config(double spread) throws Exception {
        try (var in=getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(in);var yaml=new YamlConfiguration();yaml.load(new InputStreamReader(in,StandardCharsets.UTF_8));
            var items=new ArrayList<ShopConfig.ShopItem>();
            for (var r:yaml.getMapList("items")) {
                var min=r.get("min-multiplier");var max=r.get("max-multiplier");
                items.add(new ShopConfig.ShopItem(r.get("material").toString(),r.get("category").toString(),
                        ((Number)r.get("base-price")).doubleValue(),((Number)r.get("ref-stock")).longValue(),
                        min instanceof Number n?n.doubleValue():.25,max instanceof Number n?n.doubleValue():4,
                        !Boolean.FALSE.equals(r.get("sell-enabled"))));
                assertNotNull(org.bukkit.Material.valueOf(r.get("material").toString()));
            }
            return new ShopConfig.ShopConfigData(Map.of("DIAMOND",1,"DIAMOND_BLOCK",9),0,6400,.25,4,
                    List.copyOf(items),spread,0,24,3600000);
        }
    }
    private ShopService service(double spread) throws Exception {
        var store=new ShopStore(dir,Logger.getAnonymousLogger());store.load();
        return new ShopService(config(spread),store,()->null);
    }
    @Test void everyDefaultQuoteIsWholePositiveAndBoundedAtAllStockExtremes() throws Exception {
        for (double spread:new double[]{0,10,90}) {
            var s=service(spread);
            for (var item:s.config().items()) {
                assertTrue(s.batchSize(item)>0&&s.batchSize(item)<=2304,item.material());
                for (long stock:new long[]{1,item.refStock(),Long.MAX_VALUE}) {
                    s.store().setStock(item,stock,System.currentTimeMillis());
                    long ask=s.askPrice(item),bid=s.bidPrice(item);
                    assertTrue(ask>0&&ask<=WholeTrades.MAX,item.material()+" ask="+ask);
                    if (item.sellEnabled()) assertTrue(bid>=1&&ask>=bid,item.material()+" "+spread+" "+ask+"/"+bid);
                    else assertEquals(0,bid);
                }
            }
        }
    }
    @Test void allRecipeRoutesAreCappedEvenWithIndependentlyManipulatedStocks() throws Exception {
        var random=new Random(245);
        for (double spread:new double[]{0,10,90}) {
            var s=service(spread);
            for (int sample=0;sample<12;sample++) {
                var purchases=new HashMap<String,RecipeEconomy.Cost>();purchases.put("DIAMOND",RecipeEconomy.Cost.of(1));
                for (var item:s.config().items()) {
                    s.store().setStock(item,switch(random.nextInt(3)){case 0->1;case 1->item.refStock();default->Long.MAX_VALUE;},System.currentTimeMillis());
                    purchases.put(item.material(),RecipeEconomy.unit(s.askPrice(item),s.batchSize(item)));
                }
                var costs=RecipeEconomy.costs(purchases);
                for (var item:s.config().items()) if(item.sellEnabled()) {
                    var income=RecipeEconomy.unit(s.bidPrice(item),s.batchSize(item));
                    assertTrue(income.compareTo(costs.get(item.material()))<=0,item.material()+" sample="+sample);
                }
            }
        }
    }
    @Test void largePurchaseCannotRaiseItsOwnDecompositionBuybackCeiling() throws Exception {
        var s=service(10);var block=s.config().item("SLIME_BLOCK").orElseThrow();var ball=s.config().item("SLIME_BALL").orElseThrow();
        long paid=TradeAmounts.quote(s.batchSize(block),s.askPrice(block),256,true);
        long before=s.bidPrice(ball);s.store().setStock(block,1,System.currentTimeMillis());
        assertEquals(before,s.bidPrice(ball),"покупка блока не поднимает рецептурный потолок");
        long income=TradeAmounts.quote(s.batchSize(ball),s.bidPrice(ball),2304,false);
        assertTrue(income<=paid,"256 блоков → 2304 сгустка не создают алмазы");
    }
    @Test void clayAndHoneyChainsAndMetalCompressionCannotMintMoney() throws Exception {
        var s=service(10);
        for (String[] route:new String[][]{{"CLAY","BRICK","4"},{"HONEY_BLOCK","HONEY_BOTTLE","4"},
                {"IRON_BLOCK","IRON_INGOT","9"},{"GOLD_BLOCK","GOLD_INGOT","9"},
                {"COPPER_BLOCK","COPPER_INGOT","9"},{"BLAZE_ROD","BLAZE_POWDER","2"}}) {
            var input=s.config().item(route[0]).orElseThrow();var output=s.config().item(route[1]).orElseThrow();int yield=Integer.parseInt(route[2]);
            s.store().setStock(input,Long.MAX_VALUE,System.currentTimeMillis());s.store().setStock(output,1,System.currentTimeMillis());
            long paid=TradeAmounts.quote(s.batchSize(input),s.askPrice(input),64,true);
            long income=TradeAmounts.quote(s.batchSize(output),s.bidPrice(output),64L*yield,false);
            assertTrue(income<=paid,Arrays.toString(route)+" "+paid+"/"+income);
        }
    }
    @Test void buyOnlyIsEnforcedBelowCommandsAndBothGuiPathsWithoutTouchingInventory() throws Exception {
        var s=service(10);int count=0;
        for (var item:s.config().items()) if(!item.sellEnabled()) {
            count++;assertEquals("quantity.buy-only",s.sell(null,item,1).errorKey());
            assertEquals("quantity.buy-only",s.sellMaximum(null,item).errorKey());
            assertEquals("quantity.buy-only",s.quoted(null,item,64,false,s.batchSize(item),0).errorKey());
            assertEquals(0,s.maximumSale(null,item,s.batchSize(item),0));
            assertEquals(0,s.bidPrice(item));assertTrue(s.askPrice(item)>0);
        }
        assertEquals(91,count);
    }
    @Test void forgedLegacyItemCannotEnableBuybackForNewDecor() throws Exception {
        var s=service(10);var item=s.config().item("WHITE_CONCRETE").orElseThrow();
        var forged=new ShopConfig.ShopItem(item.material(),item.category(),item.basePrice(),item.refStock(),item.minMultiplier(),item.maxMultiplier());
        assertEquals(0,s.bidPrice(forged));
        assertEquals("trading.price-changed",s.sell(null,forged,64).errorKey());
        assertEquals("trading.price-changed",s.sellMaximum(null,forged).errorKey());
        assertEquals(0,s.maximumSale(null,forged,s.batchSize(forged),1));
    }
    @Test void exactFractionsDoNotDecayInGoldNuggetRoundTrips() {
        var costs=RecipeEconomy.recipeCosts(Map.of("GOLD_INGOT",RecipeEconomy.Cost.of(.5)));
        assertEquals(RecipeEconomy.Cost.of(.5),costs.get("GOLD_INGOT"));
        assertEquals(1,RecipeEconomy.cap(costs.get("GOLD_INGOT"),2,0));
        assertEquals(2304,RecipeEconomy.batch(RecipeEconomy.unit(1,2304)));
    }
    @Test void sixArgumentCustomItemsKeepLegacyTwoWayTrading() {
        assertTrue(new ShopConfig.ShopItem("STONE","blocks",.05,20000,.25,4).sellEnabled());
    }
    @Test void everyDefaultBuybackIsBelowItsOwnLowestAskAtAllTestedSpreads() throws Exception {
        for(double spread:new double[]{0,10,50,90}) {
            var s=service(spread);
            for(var item:s.config().items()) {
                if(!item.sellEnabled())continue;
                s.store().setStock(item,Long.MAX_VALUE,System.currentTimeMillis());
                long lowestAsk=s.askPrice(item);
                s.store().setStock(item,1,System.currentTimeMillis());
                assertTrue(s.bidPrice(item)<=lowestAsk,item.material()+" spread="+spread);
            }
        }
    }
    @Test void rawGoodsCannotBePumpedByBuyingTheSameMaterial() throws Exception {
        var s=service(10);var item=s.config().item("GHAST_TEAR").orElseThrow();
        s.store().setStock(item,item.refStock(),System.currentTimeMillis());
        int batch=s.batchSize(item); long paid=TradeAmounts.quote(batch,s.askPrice(item),2304,true);
        s.store().setStock(item,1,System.currentTimeMillis());
        long legacyBid=WholeTrades.price(PriceEngine.bid(s.priceOf(item),10),batch,false);
        assertTrue(TradeAmounts.quote(batch,legacyBid,2304,false)>paid,"Регрессия действительно воспроизводит прежний self-pump");
        assertTrue(TradeAmounts.quote(batch,s.bidPrice(item),2304,false)<=paid);
        long ceiling=RecipeEconomy.cap(RecipeEconomy.Cost.of(PriceEngine.ask(PriceEngine.round(item.basePrice()*item.minMultiplier()),10)),batch,10);
        for(long stock:new long[]{1,item.refStock(),Long.MAX_VALUE}) {
            s.store().setStock(item,stock,System.currentTimeMillis()); assertTrue(s.bidPrice(item)<=ceiling);
        }
    }
}
