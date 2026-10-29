package ru.cherdakmd.chrdkregister.api;

/** Общий потолок редкости источника. Неизвестная редкость не выдаётся автоматически. */
public final class EnchantBookPolicy {
    public static boolean allows(String rarity, int tier) {
        int rank = switch (rarity == null ? "" : rarity) {
            case "COMMON" -> 0;
            case "RARE" -> 1;
            case "EPIC" -> 2;
            case "LEGENDARY" -> 3;
            default -> Integer.MAX_VALUE;
        };
        return rank <= (tier >= 3 ? 3 : tier == 2 ? 2 : 1);
    }
    private EnchantBookPolicy() { }
}
