package ru.cherdakmd.chrdkregister.api;

/** Read-only current shop bid in base hundredths, before the diamond exchange rate.
 * Implementations must not modify stock, balances, sale remainders or turnover.
 * A caller must persist the final quoted amount before attempting a payout.
 */
public interface RewardMarketQuote {
    enum Status { AVAILABLE, NOT_BOUGHT, UNAVAILABLE }
    record Quote(Status status, long minor) {
        public Quote {
            if (status == null || minor < 0 || (status != Status.AVAILABLE && minor != 0))
                throw new IllegalArgumentException("invalid reward market quote");
        }
    }
    Quote sell(String material, int amount);
}
