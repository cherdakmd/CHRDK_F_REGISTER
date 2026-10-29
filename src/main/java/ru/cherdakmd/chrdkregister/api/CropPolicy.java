package ru.cherdakmd.chrdkregister.api;

/** Реальное семя на одну культуру. Нет роста, дополнительного дропа или опыта. */
public final class CropPolicy {
    public static String seed(String crop) {
        if (crop == null) return null;
        return switch (crop) {
            case "WHEAT" -> "WHEAT_SEEDS";
            case "CARROTS" -> "CARROT";
            case "POTATOES" -> "POTATO";
            case "BEETROOTS" -> "BEETROOT_SEEDS";
            case "NETHER_WART" -> "NETHER_WART";
            case "COCOA" -> "COCOA_BEANS";
            default -> null;
        };
    }
    public static boolean ripe(int age, int maximum) { return maximum > 0 && age == maximum; }
    private CropPolicy() { }
}
