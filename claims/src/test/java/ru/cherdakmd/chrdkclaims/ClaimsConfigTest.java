package ru.cherdakmd.chrdkclaims;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimsConfigTest {

    private static ClaimsConfig parse(String yaml) {
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.load(new java.io.StringReader(yaml));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return ClaimsConfig.parse(cfg, Logger.getAnonymousLogger());
    }

    @Test
    void defaults() {
        ClaimsConfig config = parse("general: {}\n");
        assertTrue(config.requireAuth);
        assertEquals(16, config.chunkPrice);
        assertEquals(1, config.upkeepPerDay);
        assertEquals(3, config.graceDays);
        assertEquals(50, config.sellbackPercent);
        assertEquals(10, config.saleTaxPercent);
        assertEquals(24, config.baseChunks);
        assertEquals(8, config.passBonusChunks);
        assertFalse(config.pvp);
        assertTrue(config.explosions);
        assertFalse(config.redstone);
        assertTrue(config.workstations, "вёрстаки защищаются по умолчанию");
        assertTrue(config.notifyRent);
        assertEquals(ClaimMap.RADIUS, config.mapRadius);
        assertEquals(10, config.borderSeconds);
        assertTrue(config.enterNotice, "уведомление о входе на чужой приват включено");
        assertTrue(config.leaveNotice);
        assertFalse(config.noticeOwn);
        assertTrue(config.noticeMembers);
        assertEquals(2, config.noticeCooldown);
        assertEquals(ClaimsConfig.NoticeMode.ACTIONBAR, config.noticeMode);
        assertEquals("", config.timezone);
    }

    @Test
    void clampsNegativeAndHugeNumbers() {
        ClaimsConfig config = parse("""
                pricing: {chunk-price: -5, upkeep-per-day: 99999999, grace-days: -1, sellback-percent: 140}
                taxes: {sale-tax-percent: 120}
                limits: {base-chunks: 0, pass-bonus-chunks: -3, members-per-chunk: 900}
                general: {map-radius: 99, border-seconds: 1}
                """);
        assertEquals(1, config.chunkPrice);
        assertEquals(100_000, config.upkeepPerDay);
        assertEquals(0, config.graceDays);
        assertEquals(100, config.sellbackPercent);
        assertEquals(90, config.saleTaxPercent);
        assertEquals(1, config.baseChunks);
        assertEquals(0, config.passBonusChunks);
        assertEquals(128, config.membersPerChunk);
        assertEquals(ClaimMap.MAX_RADIUS, config.mapRadius);
        assertEquals(3, config.borderSeconds, "подсветка не короче трёх секунд");
    }

    @Test
    void noticeSettingsParseClampAndFallback() {
        ClaimsConfig chat = parse("""
                general: {notice-mode: "CHAT", leave-notice: false, notice-own: true,
                          notice-members: false, notice-cooldown-seconds: 999}
                """);
        assertEquals(ClaimsConfig.NoticeMode.CHAT, chat.noticeMode);
        assertTrue(chat.noticeMode.chat());
        assertFalse(chat.noticeMode.actionBar());
        assertFalse(chat.leaveNotice);
        assertTrue(chat.noticeOwn);
        assertFalse(chat.noticeMembers);
        assertEquals(60, chat.noticeCooldown, "кулдаун зажимается до 60 секунд");

        ClaimsConfig broken = parse("""
                general: {notice-mode: "sidebar", notice-cooldown-seconds: -5}
                """);
        assertEquals(ClaimsConfig.NoticeMode.ACTIONBAR, broken.noticeMode,
                "неизвестный канал не ломает конфиг — прежняя строка действия");
        assertEquals(0, broken.noticeCooldown);

        ClaimsConfig both = parse("general: {notice-mode: both}");
        assertEquals(ClaimsConfig.NoticeMode.BOTH, both.noticeMode);
        assertTrue(both.noticeMode.chat());
        assertTrue(both.noticeMode.actionBar());
    }

    @Test
    void worldFilterIsCaseInsensitiveAndDisabledWins() {
        ClaimsConfig config = parse("""
                worlds:
                  enabled: [world, "World_Nether"]
                  disabled: [world_nether]
                """);
        assertTrue(config.worldEnabled("world"));
        assertTrue(config.worldEnabled("WORLD"));
        assertFalse(config.worldEnabled("world_nether"), "disabled главнее enabled");
        assertFalse(config.worldEnabled("world_the_end"), "не в белом списке");
        assertFalse(config.worldEnabled(""));
        assertFalse(config.worldEnabled(null));
    }

    @Test
    void emptyEnabledListMeansAllWorlds() {
        ClaimsConfig config = parse("worlds: {disabled: [creative]}\n");
        assertTrue(config.worldEnabled("world"));
        assertFalse(config.worldEnabled("creative"));
    }

    @Test
    void disabledStarTurnsClaimsOffEverywhere() {
        ClaimsConfig config = parse("worlds: {disabled: ['*']}\n");
        assertFalse(config.worldEnabled("world"));
        assertFalse(config.worldEnabled("world_nether"));
    }

    @Test
    void enabledStarAllowsAllExceptDisabled() {
        ClaimsConfig config = parse("""
                worlds:
                  enabled: ['*']
                  disabled: [world_the_end]
                """);
        assertTrue(config.worldEnabled("world"));
        assertFalse(config.worldEnabled("world_the_end"));
    }

    @Test
    void worldEconomyMultipliersAreApplied() {
        ClaimsConfig config = parse("""
                pricing: {chunk-price: 16, upkeep-per-day: 2}
                worlds:
                  price-multiplier:
                    world_nether: 0.5
                    WORLD_THE_END: 2.0
                  upkeep-multiplier:
                    world_nether: 0.5
                """);
        assertEquals(16, config.priceIn("world"), "без множителя — базовая цена");
        assertEquals(8, config.priceIn("world_nether"));
        assertEquals(32, config.priceIn("world_the_end"), "мир ищется без учёта регистра");
        assertEquals(16, config.priceIn(null));
        assertEquals(2, config.upkeepIn("world"));
        assertEquals(1, config.upkeepIn("world_nether"));
        assertEquals(2, config.upkeepIn("world_the_end"), "нет множителя ренты — база");
    }

    @Test
    void multiplierOutOfRangeIsClamped() {
        ClaimsConfig config = parse("""
                pricing: {chunk-price: 16}
                worlds:
                  price-multiplier: {world: 50}
                  upkeep-multiplier: {world: 0}
                """);
        assertEquals(160, config.priceIn("world"), "максимум множителя 10: 16 × 10");
        assertEquals(1, config.upkeepIn("world"), "минимум множителя 0.1, рента не обнуляется");
    }

    @Test
    void menuAndTeleportDefaultsAreSafe() {
        ClaimsConfig config = parse("general: {}\n");
        assertTrue(config.menuEnabled, "меню включено по умолчанию");
        assertTrue(config.menuShowForSale, "витрина продаж видна в меню");
        assertTrue(config.teleportEnabled);
        assertEquals(5, config.teleportWarmup);
        assertEquals(10, config.teleportCooldown);
        assertEquals(3, config.teleportRadius);
        assertTrue(config.teleportCancelMove);
        assertTrue(config.teleportCancelDamage);
    }

    @Test
    void teleportSettingsAreClamped() {
        ClaimsConfig config = parse("""
                menu: {enabled: false, show-for-sale: false}
                teleport:
                  enabled: false
                  warmup-seconds: 90
                  cooldown-seconds: -5
                  search-radius: 12
                  cancel-on-move: false
                  cancel-on-damage: false
                """);
        assertFalse(config.menuEnabled);
        assertFalse(config.menuShowForSale);
        assertFalse(config.teleportEnabled);
        assertEquals(30, config.teleportWarmup, "разбег не дольше 30 секунд");
        assertEquals(0, config.teleportCooldown, "отрицательный кулдаун — без кулдауна");
        assertEquals(ClaimTeleport.MAX_RADIUS, config.teleportRadius,
                "поиск площадки не выходит за приват");
        assertFalse(config.teleportCancelMove);
        assertFalse(config.teleportCancelDamage);
    }

    @Test
    void timezoneIsResolvedAndBadZoneFallsBack() {
        ClaimsConfig moscow = parse("general: {timezone: Europe/Moscow}\n");
        assertEquals("Europe/Moscow", moscow.timezone);
        assertEquals(java.time.ZoneId.of("Europe/Moscow"), moscow.zone);

        ClaimsConfig broken = parse("general: {timezone: 'Не/Зона'}\n");
        assertEquals("Не/Зона", broken.timezone);
        assertEquals(java.time.ZoneId.systemDefault(), broken.zone);
    }
}
