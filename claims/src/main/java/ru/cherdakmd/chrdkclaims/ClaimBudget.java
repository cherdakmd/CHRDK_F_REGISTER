package ru.cherdakmd.chrdkclaims;

import java.math.BigInteger;
import java.util.Collection;

/** Консервативный резерв по текущим ставкам, не обещание точного календаря списаний. */
final class ClaimBudget {
    record Budget(int owned, int rentals, BigInteger upkeep, BigInteger rental,
                  BigInteger debt, BigInteger reserve, BigInteger shortage, BigInteger fullDays) { }
    private ClaimBudget() { }
    static Budget calculate(Collection<Claim> claims, ClaimsConfig config, String name, long balance, int days, long now) {
        if (days < 1 || days > 30 || name == null) throw new IllegalArgumentException("budget horizon/name");
        int owned=0, rentals=0;
        BigInteger upkeep=BigInteger.ZERO, rental=BigInteger.ZERO, debt=BigInteger.ZERO;
        for (var claim:claims) {
            if(!config.worldEnabled(claim.world)) continue;
            if(claim.isOwner(name)) {
                owned++;
                var rate=BigInteger.valueOf(config.upkeepIn(claim.world));
                upkeep=upkeep.add(rate);
                debt=debt.add(rate.multiply(BigInteger.valueOf(Math.max(0,claim.debtDays))));
            }
            if(name.equalsIgnoreCase(claim.tenant)&&claim.rentedAt(now)&&claim.rentRenew&&claim.rentOffer>0
                    &&claim.rentPrice>=config.rentalMinPerDay) {
                rentals++;rental=rental.add(BigInteger.valueOf(claim.rentPrice));
            }
        }
        var daily=upkeep.add(rental);
        var funds=BigInteger.valueOf(Math.max(0,balance));
        var reserve=debt.add(daily.multiply(BigInteger.valueOf(days)));
        var fullDays=daily.signum()==0?BigInteger.ZERO:funds.subtract(debt).max(BigInteger.ZERO).divide(daily);
        return new Budget(owned,rentals,upkeep,rental,debt,reserve,reserve.subtract(funds).max(BigInteger.ZERO),fullDays);
    }
}
