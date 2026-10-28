package ru.cherdakmd.chrdkregister.api;

import java.util.UUID;

/** API 17: долговечное намерение расхода. Доходы используют отдельные cumulative-квитанции. */
public record BankCharge(String source, UUID lineage, long sequence, UUID transaction, UUID payer,
                         long price, long debit, long cash, long change, String inventoryHash, String stateHash) {
    public enum Status { UNAVAILABLE, MISSING, COMMITTED, CONFLICT }
    public boolean valid() {
        return source != null && source.matches("[a-z][a-z0-9-]{0,47}") && lineage != null && transaction != null && payer != null
                && sequence > 0 && sequence < Long.MAX_VALUE && price >= 1 && price <= 1_000_000
                && debit >= 0 && debit <= price && cash >= 0 && cash <= 36L * 64 * 9
                && change >= 0 && change <= 8 && change <= cash && debit + cash - change == price
                && inventoryHash != null && inventoryHash.matches("[0-9a-f]{64}")
                && stateHash != null && stateHash.matches("[0-9a-f]{64}");
    }
}
