package ru.cherdakmd.chrdkclaims;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ClaimTariffTest {
    @TempDir Path dir;
    final Logger log=Logger.getAnonymousLogger();
    @Test void pricesRiseWithoutIncreasingTheServerBuybackForOldLand() {
        var y=new YamlConfiguration();var c=ClaimsConfig.parse(y,log);
        assertEquals(160,c.chunkPrice);assertEquals(10,c.upkeepPerDay);assertEquals(20,c.teleportCost);
        assertEquals(20,c.rentalMinPerDay);assertEquals(8,c.sellbackIn("world"));
        for(double factor:new double[]{.1,.2,.49,.5,1,1.5,2,10}) {
            y.set("worlds.price-multiplier.world",factor);c=ClaimsConfig.parse(y,log);
            assertEquals(ClaimMath.sellbackReward(ClaimMath.scaled(16,factor),50),c.sellbackIn("world"));
            assertTrue(c.sellbackIn("world")<c.priceIn("world"));
        }
    }
    @Test void cheapCustomLandCannotBeSoldForMoreThanItsPurchasePriceAndTpNeverDropsBelowTen() {
        var y=new YamlConfiguration();y.set("pricing.chunk-price",1);y.set("teleport.cost-diamonds",0);
        var c=ClaimsConfig.parse(y,log);assertEquals(0,c.sellbackIn("world"));assertEquals(10,c.teleportCost);
    }
    @Test void oldPaidLeaseIsNotRepricedOrCutShortAndEndsInsteadOfRenewingBelowNewFloor() {
        long now=1000;var s=new ClaimStore(dir,log);var c=new Claim("world",0,0,"Owner",now);s.add(c);
        s.addTreasury("Tenant",100);assertNull(ClaimRentals.offer(s,c,"Owner",2,now));
        assertNull(ClaimRentals.take(s,c,"Tenant",2,now,1));long until=c.rentUntil;int paid=s.treasuryOf("Tenant");
        assertEquals(0,ClaimRentals.settle(s,c,until-1,10,20));assertEquals(until,c.rentUntil);assertEquals(2,c.rentPrice);
        assertEquals(paid,s.treasuryOf("Tenant"));assertEquals(-1,ClaimRentals.settle(s,c,until,10,20));
        assertEquals(paid,s.treasuryOf("Tenant"));assertTrue(c.tenant.isBlank());
    }
    @Test void newCheapOfferAndTakingAnOldCheapOfferAreRejectedWithoutPayment() {
        long now=1000;var s=new ClaimStore(dir,log);var c=new Claim("world",0,0,"Owner",now);s.add(c);s.addTreasury("Tenant",100);
        assertEquals("finance.rent-minimum",ClaimRentals.offer(s,c,"Owner",19,now,20));assertEquals(0,c.rentOffer);
        assertNull(ClaimRentals.offer(s,c,"Owner",2,now));
        assertEquals("finance.rent-minimum",ClaimRentals.take(s,c,"Tenant",2,now,10,20));assertEquals(100,s.treasuryOf("Tenant"));
        assertNull(ClaimRentals.offer(s,c,"Owner",20,now,20));assertNull(ClaimRentals.take(s,c,"Tenant",20,now,10,20));
        assertEquals(80,s.treasuryOf("Tenant"));assertEquals(20,c.rentPrice);
    }
}
