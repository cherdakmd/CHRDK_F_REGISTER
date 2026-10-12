package ru.cherdakmd.chrdkclaims;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.logging.Logger;

/** Разбор config.yml: экономика, налог, лимиты, флаги защиты. */
public final class ClaimsConfig {

    public final boolean requireAuth;
    public final int chunkPrice;
    public final int upkeepPerDay;
    public final int graceDays;
    public final int sellbackPercent;
    public final int saleTaxPercent;
    public final int baseChunks;
    public final int passBonusChunks;
    public final int membersPerChunk;
    public final int withdrawMax;
    public final int spawnMargin;

    public final boolean pvp;
    public final boolean fire;
    public final boolean explosions;
    public final boolean fluids;
    public final boolean pistons;
    public final boolean hoppers;
    public final boolean doors;
    public final boolean redstone;
    public final boolean mobGriefing;
    public final boolean entities;

    private ClaimsConfig(FileConfiguration cfg) {
        requireAuth = cfg.getBoolean("general.require-auth", true);
        chunkPrice = clamp(cfg.getInt("pricing.chunk-price", 16), 1, 1_000_000);
        upkeepPerDay = clamp(cfg.getInt("pricing.upkeep-per-day", 1), 0, 100_000);
        graceDays = clamp(cfg.getInt("pricing.grace-days", 3), 0, 90);
        sellbackPercent = clamp(cfg.getInt("pricing.sellback-percent", 50), 0, 100);
        saleTaxPercent = clamp(cfg.getInt("taxes.sale-tax-percent", 10), 0, 90);
        baseChunks = clamp(cfg.getInt("limits.base-chunks", 24), 1, 100_000);
        passBonusChunks = clamp(cfg.getInt("limits.pass-bonus-chunks", 8), 0, 100_000);
        membersPerChunk = clamp(cfg.getInt("limits.members-per-chunk", 8), 0, 128);
        withdrawMax = clamp(cfg.getInt("limits.withdraw-max", 2304), 1, 1_000_000);
        spawnMargin = clamp(cfg.getInt("worlds.spawn-margin", 64), 0, 100_000);

        pvp = cfg.getBoolean("protect.pvp", false);
        fire = cfg.getBoolean("protect.fire", true);
        explosions = cfg.getBoolean("protect.explosions", true);
        fluids = cfg.getBoolean("protect.fluids", true);
        pistons = cfg.getBoolean("protect.pistons", true);
        hoppers = cfg.getBoolean("protect.hoppers", true);
        doors = cfg.getBoolean("protect.doors", true);
        redstone = cfg.getBoolean("protect.redstone", false);
        mobGriefing = cfg.getBoolean("protect.mob-griefing", true);
        entities = cfg.getBoolean("protect.entities", true);
    }

    public static ClaimsConfig parse(FileConfiguration cfg, Logger logger) {
        return new ClaimsConfig(cfg);
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
