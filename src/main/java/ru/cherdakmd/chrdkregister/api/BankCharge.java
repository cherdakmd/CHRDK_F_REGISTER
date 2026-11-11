package ru.cherdakmd.chrdkregister.api;

import java.util.UUID;

/** API 17: долговечное намерение расхода. Доходы используют отдельные cumulative-квитанции. */
public record BankCharge(String source, UUID lineage, long sequence, UUID transaction, UUID payer,
                         long price, long debit, long cash, long change, String inventoryHash, String stateHash,
                         boolean minor, int rateBps) {
    public BankCharge(String source,UUID lineage,long sequence,UUID transaction,UUID payer,long price,long debit,long cash,long change,String inventoryHash,String stateHash) {
        this(source,lineage,sequence,transaction,payer,price,debit,cash,change,inventoryHash,stateHash,false,DiamondExchangePolicy.BASE_BPS);
    }
    public enum Status { UNAVAILABLE, MISSING, COMMITTED, CONFLICT }
    public static String permission(String source) {
        if(source==null) return null;
        return switch(source) {
            case "common-goals" -> "chrdkgoals.use";
            case "teleports" -> "chrdktele.use";
            case "claim-teleports" -> "chrdkclaims.teleport";
            case "guilds" -> "chrdkguilds.use";
            default -> null;
        };
    }
    public boolean valid() {
        return source != null && source.matches("[a-z][a-z0-9-]{0,47}") && lineage != null && transaction != null && payer != null
                && sequence > 0 && sequence < Long.MAX_VALUE && price >= 1 && price <= (minor?100_000_000L:1_000_000L)
                && (!minor||rateBps>=DiamondExchangePolicy.MIN_BPS&&rateBps<=DiamondExchangePolicy.MAX_BPS)
                && debit >= 0 && debit <= price && cash >= 0 && cash <= (minor?36L*64*9*20_000L:36L*64*9)
                && change >= 0 && change <= (minor?20_000L:8) && change <= cash && debit + cash - change == price
                && inventoryHash != null && inventoryHash.matches("[0-9a-f]{64}")
                && stateHash != null && stateHash.matches("[0-9a-f]{64}");
    }
}
