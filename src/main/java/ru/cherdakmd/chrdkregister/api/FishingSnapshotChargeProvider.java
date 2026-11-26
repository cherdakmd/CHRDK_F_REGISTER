package ru.cherdakmd.chrdkregister.api;

import org.bukkit.entity.Player;

/** Internal Core capability for Fishing bank-only charges with the original stored snapshot.
 * A false result is not evidence that a debit did not commit; callers must check its receipt.
 */
public interface FishingSnapshotChargeProvider {
    boolean commitFishingSnapshotCharge(Player player, BankCharge charge, String snapshot);
}
