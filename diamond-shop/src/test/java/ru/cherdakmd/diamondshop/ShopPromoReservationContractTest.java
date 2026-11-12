package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Денежный резерв обязан предшествовать WAL и переживать pending recovery. */
class ShopPromoReservationContractTest {
    @Test
    void reservePrecedesPrepareAndAcceptedWalNeverReleasesBonus() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/ru/cherdakmd/diamondshop/ShopService.java"));
        int reserve = service.indexOf("promos.reserve(player.getUniqueId(),promo,now)");
        int prepare = service.indexOf("store.prepare(t,item,now");
        int accepted = service.indexOf("tradeAccepted=true", prepare);
        int recovery = service.indexOf("if(!recoverPending())", accepted);
        assertTrue(reserve > 0 && reserve < prepare, "сначала durable-резерв, затем WAL");
        assertTrue(prepare < accepted && accepted < recovery,
                "после принятия WAL резерв сохраняется даже при сбое recovery");
        assertTrue(service.contains("if(promoReserved&&!tradeAccepted) promos.release"));
        assertTrue(service.contains("money=baseMoney; price=basePrice; exact=baseExact;"));
    }

    @Test
    void successfulCommitCannotChargeReservedBonusTwice() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/ru/cherdakmd/diamondshop/ShopPromoService.java"));
        assertTrue(service.contains("store.commit(player, nowMillis, material, units, 0, false, false)"));
        assertTrue(service.contains("bonus.amount() <= dailyLeft && store.reserve"));
    }
}
