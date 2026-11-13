package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.ShopExchange;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** RC16.1: заморозка Кузни в целых алмазах (turnoverWhole), а не в сотых (money).
 *  Формулы — чистая математика; проводка через Bukkit/ItemStack-кодеки — source-inspection. */
class ForgeFreezeContractTest {

    private static ShopExchange fractional(long money,int rateBps) {
        return ShopExchange.fractional(new ShopExchange(UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),
                true,"ECHO_SHARD",1,money,money,0,0,Map.of("DIAMOND",1),"before","after"),rateBps);
    }

    @Test void turnoverWholeRoundsUpFractionalMinorWhileLegacyMoneyStaysRaw() {
        var whole=fractional(25000,10000);
        assertEquals(25000,whole.money());
        assertEquals(250,whole.turnoverWhole());
        var odd=fractional(25050,10000);
        assertEquals(25050,odd.money());
        assertEquals(251,odd.turnoverWhole());
        var legacy=new ShopExchange(UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),
                true,"ECHO_SHARD",1,250,250,0,0,Map.of("DIAMOND",1),"before","after");
        assertEquals(250,legacy.turnoverWhole());
    }

    @Test void productionPendingFromIncidentIsAcceptedAndHealed() {
        // Реальный pending с продакшена (инцидент 2026-11-13 22:37:33 MSK, kalinin_play):
        // 8 ECHO_SHARD за 9,44 ⛁ (money=944 сотых, rateBps=9250); заморозка ушла через money:
        // turnover 778.0 → 1722.0, а формула matches ждала 778+turnoverWhole(10)=788.0.
        assertEquals(10,fractional(944,9250).turnoverWhole());
        assertTrue(ShopJournal.frozenEffect(1722.0,778.0,944,10),"legacy-заморозка (money) довершается");
        assertTrue(ShopJournal.frozenEffect(788.0,778.0,944,10),"новая заморозка (turnoverWhole) довершается");
        assertFalse(ShopJournal.frozenEffect(1721.0,778.0,944,10),"чужое значение не принимается");
        assertEquals(788.0,PriceEngine.round(778.0+fractional(944,9250).turnoverWhole()),0.0,
                "heal довершает сделку с истинным эффектом, а не с баговым ×100");
    }

    @Test void frozenEffectAcceptsBothLegacyAndCurrentForgeFreeze() {
        // legacy-заморозка (до RC16.1): round(old+money) — сотые алмаза, ×100 к целым.
        assertTrue(ShopJournal.frozenEffect(25000,0,25000,250));
        assertTrue(ShopJournal.frozenEffect(25100.5,100.5,25000,250));
        // новая заморозка: round(old+turnoverWhole) — целые алмазы.
        assertTrue(ShopJournal.frozenEffect(250,0,25000,250));
        assertTrue(ShopJournal.frozenEffect(350.5,100.5,25000,250));
        // цена без дробной части: обе формулы совпадают.
        assertTrue(ShopJournal.frozenEffect(250,0,250,250));
        // ни та, ни другая — не принимается.
        assertFalse(ShopJournal.frozenEffect(249,0,25000,250));
        assertFalse(ShopJournal.frozenEffect(25001,0,25000,250));
    }

    @Test void prepareForgeFreezesCountersInWholeDiamondsNotMinorMoney() throws Exception {
        String code=Files.readString(Path.of("src/main/java/ru/cherdakmd/diamondshop/ShopStore.java"));
        String forge=code.substring(code.indexOf("boolean prepareForge"),code.indexOf("cancelUnaccepted"));
        assertTrue(forge.contains("usedToday(t.owner(),now)+t.turnoverWhole()"));
        assertTrue(forge.contains("PriceEngine.round(turnover+t.turnoverWhole())"));
        assertTrue(forge.contains("exactIncrease(turnover,p.turnover(),t.turnoverWhole())"));
        assertTrue(forge.contains("exactIncrease(usedToday(t.owner(),now),p.used(),t.turnoverWhole())"));
        assertFalse(forge.contains("t.money()"), "заморозка Кузни не считается в сотых алмаза");
    }

    @Test void matchesAcceptsLegacyFrozenForgeEffectAndFinishHealsIt() throws Exception {
        String journal=Files.readString(Path.of("src/main/java/ru/cherdakmd/diamondshop/ShopJournal.java"));
        String matches=journal.substring(journal.indexOf("boolean matches"),journal.indexOf("interface Bank"));
        assertTrue(journal.contains("static boolean frozenEffect(double frozen,double old,long money,long turnoverWhole)"));
        assertTrue(matches.contains("frozenEffect(turnover,oldTurnover,trade.money(),trade.turnoverWhole())"));
        assertTrue(matches.contains("frozenEffect(used,oldUsed,trade.money(),trade.turnoverWhole())"));
        // штатный (не-Кузня) путь неизменен: обе формулы — turnoverWhole.
        assertTrue(matches.contains("turnover==PriceEngine.round(oldTurnover+trade.turnoverWhole())"));
        assertTrue(matches.contains("used==PriceEngine.round(oldUsed+trade.turnoverWhole())"));
        String store=Files.readString(Path.of("src/main/java/ru/cherdakmd/diamondshop/ShopStore.java"));
        String finish=store.substring(store.indexOf("synchronized boolean finish"),store.indexOf("public synchronized void saveNow"));
        assertTrue(finish.contains("if(p.forge())"));
        assertTrue(finish.contains("PriceEngine.round(usedToday(p.trade().owner(),p.time())+p.trade().turnoverWhole())"));
        assertTrue(finish.contains("PriceEngine.round(turnover+p.trade().turnoverWhole())"));
        assertTrue(finish.contains("p=new ShopJournal.Pending(p.trade()"));
    }
}
