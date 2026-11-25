package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Locale;
import org.bukkit.Material;
import ru.cherdakmd.chrdkfishing.BaitCatalog;

/** Shared player-facing names and descriptions; amounts are hundredths of a diamond. */
final class FishingText {
    static String amount(long minor) { return (minor / 100) + "." + String.format(Locale.ROOT, "%02d", minor % 100); }
    static String material(Material material) {
        return switch (material) {
            case STRING -> "Нить";
            case IRON_INGOT -> "Железный слиток";
            case LAPIS_LAZULI -> "Лазурит";
            case GOLD_INGOT -> "Золотой слиток";
            case AMETHYST_SHARD -> "Осколок аметиста";
            case DIAMOND -> "Алмаз";
            case PRISMARINE_SHARD -> "Осколок призмарина";
            case NAUTILUS_SHELL -> "Раковина наутилуса";
            case NETHERITE_SCRAP -> "Незеритовый скрап";
            default -> material.name();
        };
    }
    static String baitEffect(BaitCatalog.Bait bait) {
        return switch (bait.effect()) {
            case WAIT_TIME -> "Ожидание поклёвки: −" + bait.bonusPercent() + "%";
            case RARE_FISH -> "В пользу редкой рыбы: до " + bait.bonusPercent() + "% базового веса";
            case LARGE_FISH -> "До +" + bait.bonusPercent() + " веса крупной рыбе вместо маленькой";
        };
    }
    private FishingText() { }
}
