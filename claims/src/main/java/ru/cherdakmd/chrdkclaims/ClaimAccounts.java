package ru.cherdakmd.chrdkclaims;

import java.util.Map;

/** Порт единственного личного банка. inventoryPayer задан только при явной ручной оплате. */
interface ClaimAccounts {
    boolean ready(String name);
    long balance(String name);
    boolean exchange(Map<String, Long> deltas, String inventoryPayer);
}
