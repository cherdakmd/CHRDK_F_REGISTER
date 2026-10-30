package ru.cherdakmd.chrdkclaims;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.List;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ClaimBudgetTest {
    private ClaimsConfig config() {return ClaimsConfig.parse(new YamlConfiguration(),Logger.getAnonymousLogger());}
    @Test void reserveIncludesOwnedUpkeepDebtAndTenantRenewalsNotOtherOwnersBills() {
        var owned=new Claim("world",0,0,"Steve",Long.MAX_VALUE);owned.debtDays=2;
        var rented=new Claim("world",2,0,"Alex",Long.MAX_VALUE);rented.tenant="STEVE";rented.rentUntil=1000;rented.rentRenew=true;rented.rentOffer=20;rented.rentPrice=30;
        var unrelated=new Claim("world",4,0,"Other",Long.MAX_VALUE);
        var b=ClaimBudget.calculate(List.of(owned,rented,unrelated),config(),"Steve",50,3,100);
        assertEquals(1,b.owned());assertEquals(1,b.rentals());assertEquals(BigInteger.TEN,b.upkeep());
        assertEquals(BigInteger.valueOf(30),b.rental());assertEquals(BigInteger.valueOf(20),b.debt());
        assertEquals(BigInteger.valueOf(140),b.reserve());assertEquals(BigInteger.valueOf(90),b.shortage());assertEquals(BigInteger.ZERO,b.fullDays());
        assertEquals(2,owned.debtDays);assertEquals(Long.MAX_VALUE,owned.paidUntil);assertEquals(1000,rented.rentUntil);
    }
    @Test void prepaidTimeIsNotChargedByTheEstimateAndExpiredDisabledOrUnderfloorRenewalsAreExcluded() {
        var c=new Claim("world",0,0,"Alex",0);c.tenant="Steve";c.rentUntil=1000;c.rentRenew=true;c.rentOffer=20;c.rentPrice=20;
        assertEquals(1,ClaimBudget.calculate(List.of(c),config(),"Steve",0,1,999).rentals());
        assertEquals(0,ClaimBudget.calculate(List.of(c),config(),"Steve",0,1,1000).rentals());
        c.rentPrice=19;assertEquals(0,ClaimBudget.calculate(List.of(c),config(),"Steve",0,1,999).rentals());
        c.rentPrice=20;c.rentRenew=false;assertEquals(0,ClaimBudget.calculate(List.of(c),config(),"Steve",0,1,999).rentals());
        c.rentRenew=true;c.rentOffer=0;assertEquals(0,ClaimBudget.calculate(List.of(c),config(),"Steve",0,1,999).rentals());
        var yaml=new YamlConfiguration();yaml.set("worlds.disabled",List.of("world"));
        var disabled=ClaimsConfig.parse(yaml,Logger.getAnonymousLogger());c.owner="Steve";c.debtDays=10;
        assertEquals(BigInteger.ZERO,ClaimBudget.calculate(List.of(c),disabled,"Steve",0,30,0).reserve());
    }
    @Test void extremeDebtCannotOverflowAndHorizonIsExplicit() {
        var yaml=new YamlConfiguration();yaml.set("pricing.upkeep-per-day",1_000_000);
        var cfg=ClaimsConfig.parse(yaml,Logger.getAnonymousLogger());var c=new Claim("world",0,0,"Steve",0);c.debtDays=Integer.MAX_VALUE;
        var b=ClaimBudget.calculate(List.of(c,c,c),cfg,"Steve",0,30,0);
        var expected=BigInteger.valueOf(cfg.upkeepIn("world")).multiply(BigInteger.valueOf((long)Integer.MAX_VALUE+30)).multiply(BigInteger.valueOf(3));
        assertEquals(expected,b.reserve());assertEquals(expected,b.shortage());
        assertThrows(IllegalArgumentException.class,()->ClaimBudget.calculate(List.of(c),cfg,"Steve",0,0,0));
        assertThrows(IllegalArgumentException.class,()->ClaimBudget.calculate(List.of(c),cfg,"Steve",0,31,0));
        assertEquals(BigInteger.ZERO,ClaimBudget.calculate(List.of(),cfg,"Steve",-1,3,0).reserve());
    }
}
