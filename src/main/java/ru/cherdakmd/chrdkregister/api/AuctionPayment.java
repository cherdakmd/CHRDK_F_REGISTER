package ru.cherdakmd.chrdkregister.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Неизменяемое намерение аукциона. Счётчик рынка защищает от повтора без бесконечного журнала квитанций. */
public record AuctionPayment(UUID transaction, UUID market, long sequence, long lot, UUID buyer, UUID seller,
                             long price, long fee, long debit, long cash, long change, String inventoryHash, boolean minor, int rateBps) {
    public AuctionPayment(UUID transaction,UUID market,long sequence,long lot,UUID buyer,UUID seller,long price,long fee,long debit,long cash,long change,String inventoryHash) {
        this(transaction,market,sequence,lot,buyer,seller,price,fee,debit,cash,change,inventoryHash,false,DiamondExchangePolicy.BASE_BPS);
    }
    public AuctionPayment(UUID transaction,UUID market,long sequence,long lot,UUID buyer,UUID seller,long price,long fee,long debit,long cash,long change,String inventoryHash,boolean minor) {
        this(transaction,market,sequence,lot,buyer,seller,price,fee,debit,cash,change,inventoryHash,minor,DiamondExchangePolicy.BASE_BPS);
    }
    public enum Status { UNAVAILABLE, MISSING, COMMITTED, CONFLICT }
    public boolean valid() {
        return (!minor||rateBps>=DiamondExchangePolicy.MIN_BPS&&rateBps<=DiamondExchangePolicy.MAX_BPS)
                && transaction != null && market != null && sequence > 0 && sequence < Long.MAX_VALUE && lot > 0
                && buyer != null && seller != null && !buyer.equals(seller)
                && price >= 2 && price <= (minor?100_000_000L:1_000_000L) && fee >= 0 && fee < price
                && debit >= 0 && debit <= price && cash >= 0 && cash <= (minor?36L*64*9*DiamondExchangePolicy.MAX_BPS/DiamondMoney.SCALE:36L*64*9)
                && change >= 0 && change <= (minor?9L*DiamondExchangePolicy.MAX_BPS/DiamondMoney.SCALE:8) && change <= cash && debit + cash - change == price
                && inventoryHash != null && inventoryHash.matches("[0-9a-f]{64}");
    }
    public static String hash(String before, String after) {
        if (before == null || after == null) throw new IllegalArgumentException("Нет снимка");
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(before.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
            return HexFormat.of().formatHex(digest.digest(after.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
