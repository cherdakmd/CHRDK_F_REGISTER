package ru.cherdakmd.chrdkquests;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.RewardMarketQuote;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class QuestMoneyQuoteTest {
    @Test void valuesXpReputationAndShopBidWithoutNativeRewards() {
        var reward=new QuestReward(100,10,List.of(new QuestReward.Item(Material.BREAD,4)));
        assertEquals(300,QuestMoneyQuote.quote(reward,(m,n)->new RewardMarketQuote.Quote(RewardMarketQuote.Status.AVAILABLE,200),v->v/2));
    }
    @Test void unknownOrUnavailableShopNeverCreatesZeroValuation() {
        var reward=new QuestReward(1,0,List.of(new QuestReward.Item(Material.BREAD,4)));
        assertThrows(IllegalStateException.class,()->QuestMoneyQuote.quote(reward,null,v->v));
        assertThrows(IllegalStateException.class,()->QuestMoneyQuote.quote(reward,(m,n)->new RewardMarketQuote.Quote(RewardMarketQuote.Status.UNAVAILABLE,0),v->v));
        assertThrows(IllegalStateException.class,()->QuestMoneyQuote.quote(reward,(m,n)->new RewardMarketQuote.Quote(RewardMarketQuote.Status.NOT_BOUGHT,0),v->v));
    }
    @Test void fixedFallbackOnlyUsedWhenShopExplicitlyDoesNotBuy() {
        var reward=new QuestReward(0,0,List.of(new QuestReward.Item(Material.BAKED_POTATO,2)));
        assertEquals(80,QuestMoneyQuote.quote(reward,(m,n)->new RewardMarketQuote.Quote(RewardMarketQuote.Status.NOT_BOUGHT,0),v->v));
    }
}
