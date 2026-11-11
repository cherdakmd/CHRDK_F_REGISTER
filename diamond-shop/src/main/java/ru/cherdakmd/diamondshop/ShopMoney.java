package ru.cherdakmd.diamondshop;

/** UI formatting only; financial calculations use exact long hundredths. */
final class ShopMoney {
    static String text(long minor) { return ru.cherdakmd.chrdkregister.api.DiamondMoney.format(minor); }
    private ShopMoney() { }
}
