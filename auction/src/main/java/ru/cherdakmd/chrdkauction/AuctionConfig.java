package ru.cherdakmd.chrdkauction;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.*;

public record AuctionConfig(int minPrice, int maxPrice, int feePercent, int hours, int perPlayer,
                            int maxLots, Set<Material> blocked) {
    static AuctionConfig parse(FileConfiguration c) {
        int min = Math.max(2, Math.min(AuctionStore.MAX_PRICE, c.getInt("market.min-price", 20)));
        Set<Material> blocked = EnumSet.noneOf(Material.class);
        for (String value : c.getStringList("market.blocked-materials")) {
            Material m = Material.matchMaterial(value);
            if (m == null) throw new IllegalArgumentException("Unknown blocked material: " + value);
            blocked.add(m);
        }
        return new AuctionConfig(min, Math.max(min, Math.min(AuctionStore.MAX_PRICE, c.getInt("market.max-price", 100000))),
                Math.max(0, Math.min(50, c.getInt("market.fee-percent", 5))),
                Math.max(1, Math.min(720, c.getInt("market.duration-hours", 48))),
                Math.max(1, Math.min(100, c.getInt("market.max-per-player", 10))),
                Math.max(1, Math.min(1000, c.getInt("market.max-active-lots", 500))), Set.copyOf(blocked));
    }
}
