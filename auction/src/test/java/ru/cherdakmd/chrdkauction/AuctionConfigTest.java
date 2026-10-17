package ru.cherdakmd.chrdkauction;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import static org.junit.jupiter.api.Assertions.*;
class AuctionConfigTest {
    @Test void defaults() {
        var c=AuctionConfig.parse(new YamlConfiguration());assertEquals(20,c.minPrice());assertEquals(5,c.feePercent());assertEquals(48,c.hours());
    }
    @Test void invalidLimitsAreClamped() throws Exception {
        var yaml=new YamlConfiguration();yaml.loadFromString("market:\n  min-price: -1\n  max-price: -5\n  fee-percent: 999\n  duration-hours: 0\n  max-per-player: 0\n");
        var c=AuctionConfig.parse(yaml);assertEquals(2,c.minPrice());assertEquals(2,c.maxPrice());assertEquals(50,c.feePercent());assertEquals(1,c.hours());assertEquals(1,c.perPlayer());
    }
    @Test void invalidBlacklistFailsClosed() {
        var yaml=new YamlConfiguration();yaml.set("market.blocked-materials",java.util.List.of("NOT_A_MATERIAL"));
        assertThrows(IllegalArgumentException.class,()->AuctionConfig.parse(yaml));
    }
}
