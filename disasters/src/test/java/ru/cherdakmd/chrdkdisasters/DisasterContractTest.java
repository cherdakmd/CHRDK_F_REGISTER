package ru.cherdakmd.chrdkdisasters;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Контракты безопасности модуля: то, что легко сломать будущей правкой. */
final class DisasterContractTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    private static String sources() throws IOException {
        StringBuilder out = new StringBuilder();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                out.append(Files.readString(file)).append('\n');
            }
        }
        return out.toString();
    }

    @Test
    void descriptorWiresCoreClaimsConfigSchemaAndCommands() throws IOException {
        String plugin = read("src/main/resources/plugin.yml");
        assertTrue(plugin.contains("name: CHRDK_Disasters"));
        assertTrue(plugin.contains("api-version: '26.3'"));
        assertTrue(plugin.contains("depend: [CHRDK_Core]"));
        assertTrue(plugin.contains("softdepend: [CHRDK_Claims, CHRDK_Achievements, "
                + "CHRDK_Skills, PlaceholderAPI]"));
        assertTrue(plugin.contains("chrdkdisasters.bypass"));
        assertTrue(plugin.contains("list|stop [id]"));
        assertTrue(plugin.contains("/disasters [status|shop|buy"));
        assertTrue(read("../settings.gradle").contains("include 'disasters'"));
        assertTrue(read("../wiki/config.yml").contains("- disasters"));
        String schema = read("src/main/resources/config-schema.yml");
        assertTrue(schema.contains("config-version: 5"));
        assertTrue(schema.contains("messages-version: 4"));
    }

    @Test
    void configAndMessagesAreSelfContained() throws IOException {
        String config = read("src/main/resources/config.yml");
        assertTrue(config.contains("enabled: false"));
        assertTrue(config.contains("earthquake: false"));
        assertTrue(config.contains("tornado: false"));
        assertTrue(config.contains("flood: false"));
        assertTrue(config.contains("blizzard: true"));
        assertTrue(config.contains("respect-claims: true"));
        assertTrue(config.contains("restore-after-seconds: 600"));
        assertTrue(config.contains("phases:"));
        assertTrue(config.contains("active-seconds: 20"));
        assertTrue(config.contains("sounds:"));
        assertTrue(config.contains("player-effects:"));
        assertTrue(config.contains("rewards:"));
        assertTrue(config.contains("progression:"));
        assertTrue(config.contains("integrations:"));
        assertTrue(config.contains("shop:"));
        String messages = read("src/main/resources/messages.yml");
        for (String key : new String[]{"start", "warn", "end", "status-enabled",
                "status-disabled", "status-shop", "player-usage", "admin-usage",
                "admin-started", "admin-blocked",
                "admin-stopped", "admin-stopped-one", "admin-no-run", "admin-list",
                "admin-list-empty", "admin-list-line", "admin-restored",
                "admin-not-found", "admin-on", "admin-off", "reloaded", "unknown-kind",
                "no-permission", "player-only",
                "hint-dust-storm", "hint-meteor-shower", "hint-earthquake",
                "hint-tornado", "hint-flood", "hint-blizzard",
                "cataclysm-warning", "cataclysm-countdown",
                "cataclysm-action-dust-storm", "cataclysm-action-meteor-shower",
                "cataclysm-action-earthquake", "cataclysm-action-tornado",
                "cataclysm-action-flood", "cataclysm-action-blizzard",
                "kind-dust-storm", "kind-meteor-shower", "kind-earthquake",
                "kind-tornado", "kind-flood", "kind-blizzard",
                "reward", "shop-title", "shop-close", "shop-balance-title",
                "shop-balance-line", "shop-level-line", "shop-kinds-line",
                "shop-price", "shop-can-buy", "shop-cannot-buy",
                "shop-item-diamonds", "shop-item-supplies", "shop-item-ward",
                "shop-usage", "shop-unknown", "shop-no-funds", "shop-failed",
                "shop-bought"}) {
            assertTrue(messages.contains("\"" + key + "\""), "нет сообщения " + key);
        }
    }

    @Test
    void noNmsNoExplosionsNoFakeBlockBreaks() throws IOException {
        String sources = sources();
        assertFalse(sources.contains("net.minecraft"), "NMS запрещён");
        assertFalse(sources.contains("getHandle"), "рефлексия в NMS запрещена");
        assertFalse(sources.contains("callEvent("), "катастрофа не порождает Bukkit-события");
        assertFalse(sources.contains("breakNaturally("), "нет добычи ради катастрофы");
        assertFalse(sources.contains("createExplosion("), "взрывы Bukkit не нужны");
        assertFalse(sources.contains("setHealth("), "здоровье игрока не трогаем напрямую");
        String effects = read("src/main/java/ru/cherdakmd/chrdkdisasters/DisasterEffects.java");
        assertTrue(effects.contains("cataclysm.enabled()"), "разрушения и урон строго opt-in");
        assertTrue(effects.contains("cataclysm.damage()"), "урон отключается отдельно");
        assertTrue(effects.contains("maxDamagePerPulse"), "урон за импульс ограничен");
        assertTrue(effects.contains("GameMode.CREATIVE") && effects.contains("GameMode.SPECTATOR"));
        assertTrue(effects.contains("maxAffectedPlayers"), "есть лимит затронутых игроков");
        assertTrue(sources.contains("set(block, Material.AIR)"),
                "удаление блоков идёт через общую защищённую точку");
        assertTrue(sources.contains("setType(material, false)"),
                "временная вода ставится той же точкой без физики");
        assertTrue(sources.contains("instanceof Container"),
                "хранилища игроков не трогаем");
        assertTrue(sources.contains("removePotionEffect"),
                "эффекты состояния снимаются при затухании");
    }

    @Test
    void expandedTerrainCannotBypassClaimsOrRunUnbounded() throws IOException {
        String effects = read("src/main/java/ru/cherdakmd/chrdkdisasters/DisasterEffects.java");
        String engine = read("src/main/java/ru/cherdakmd/chrdkdisasters/DisasterEngine.java");
        assertTrue(effects.contains("plugin.protection().blocked(block.getLocation())"),
                "каждый блок проверяется после старта тоже");
        assertTrue(engine.contains("areaBlocked(at, config.effectRadius())"),
                "приваты проверяются по расширенному радиусу");
        assertTrue(effects.contains("isTerrain(block.getType())"),
                "выемка ограничена природным ландшафтом");
        assertTrue(effects.contains("budgetExhausted()"));
        assertTrue(effects.contains("instanceof Container"));
        assertFalse(effects.contains("createExplosion("));
    }

    @Test
    void phasesSoundsAndRegistryAreWired() throws IOException {
        String sources = sources();
        assertTrue(sources.contains("DisasterPhasePlan"), "план фаз");
        assertTrue(sources.contains("DisasterRun"), "реестр активных запусков");
        assertTrue(sources.contains("stopOne"), "остановка по id");
        assertTrue(sources.contains("Particle.SNOWFLAKE"), "метель");
        assertTrue(sources.contains("Particle.SPLASH"), "наводнение");
        assertTrue(sources.contains("Particle.BLOCK"), "смерч поднимает блоки визуально");
        assertTrue(sources.contains("Sound.BLOCK_SNOW_BREAK"), "звуки из проверенного набора");
        assertTrue(sources.contains("PotionEffectType.SLOWNESS"), "мягкие эффекты без урона");
    }

    @Test
    void claimsSoftDependencyNeverLoadsItsClassesWhenAbsent() throws IOException {
        String protection = read("src/main/java/ru/cherdakmd/chrdkdisasters/DisasterProtection.java");
        assertFalse(protection.contains("ru.cherdakmd.chrdkclaims"),
                "защита не должна резолвить классы софт-зависимости");
        assertFalse(protection.contains("instanceof ClaimsPlugin"));
        assertTrue(protection.contains("isPluginEnabled(ClaimsHook.PLUGIN)"));
        String hook = read("src/main/java/ru/cherdakmd/chrdkdisasters/ClaimsHook.java");
        assertTrue(hook.contains("class ClaimsHook"));
        assertTrue(hook.contains("instanceof ClaimsPlugin"));
        assertTrue(hook.contains("LinkageError"), "fail-closed при несовместимости");
    }

    @Test
    void usesSharedConfigSchemaAndMessages() throws IOException {
        String sources = sources();
        assertTrue(sources.contains("ConfigUpgrader.apply(this)"));
        assertTrue(sources.contains("Messages.load(this"));
        assertTrue(sources.contains("ClaimStore") || sources.contains("claimAt("));
        assertTrue(sources.contains("Europe/Moscow"));
        assertTrue(sources.contains("announceCooldownSeconds()"));
    }

    @Test
    void rewardsShopAndProgressUseSoftIntegrations() throws IOException {
        String sources = sources();
        assertTrue(sources.contains("disasters-progress.json"), "прогресс хранится по UUID");
        assertTrue(sources.contains("DisasterProgressStore"), "атомарный JSON-стор");
        assertTrue(sources.contains("DisasterProgressMath"), "чистая арифметика прокачки");
        assertTrue(sources.contains("DisasterShop"), "магазин осколков");
        assertTrue(sources.contains("/disasters shop") || sources.contains("shop()"));
        assertTrue(sources.contains("bankStore().deposit"),
                "алмазы идут на счёт ядра, а не физическим дропом");
        assertTrue(sources.contains("isWardActive"), "пропуск катастрофы из магазина");
        assertTrue(sources.contains("dailyLeft"), "дневной лимит осколков");
        assertTrue(sources.contains("LinkageError"), "софт-зависимости fail-closed");

        String rewards = read("src/main/java/ru/cherdakmd/chrdkdisasters/DisasterRewards.java");
        assertFalse(rewards.contains("ChrdkAchievementsApi"),
                "бинарный API ачивок резолвится только внутри хука");
        assertFalse(rewards.contains("ChrdkSkillsApi"),
                "бинарный API Skills резолвится только внутри хука");
        String achievements = read("src/main/java/ru/cherdakmd/chrdkdisasters/AchievementsHook.java");
        assertTrue(achievements.contains("isPluginEnabled(PLUGIN)"));
        assertTrue(rewards.contains("disasters-survived"));
        assertTrue(rewards.contains("disaster-shards"));
        String skills = read("src/main/java/ru/cherdakmd/chrdkdisasters/SkillsHook.java");
        assertTrue(skills.contains("isPluginEnabled(PLUGIN)"));
        assertTrue(skills.contains("getRegistration(ChrdkSkillsApi.class)"));

        String build = read("build.gradle");
        assertTrue(build.contains("compileOnly project(':achievements')"));
        assertTrue(build.contains("compileOnly project(':skills')"));
    }

    @Test
    void achievementsCatalogueGainsSixDisasterGoals() throws IOException {
        String registry = read("../achievements/src/main/java/ru/cherdakmd/chrdkachievements/AchievementRegistry.java");
        assertTrue(registry.contains("EXPECTED_SIZE = 78"));
        assertTrue(registry.contains("MASTER_GOAL = 71"));
        assertTrue(registry.contains("pervaya-katastrofa"));
        assertTrue(registry.contains("vse-shest-bedstvij"));
        assertTrue(registry.contains("stojkost-pyat"));
        String metric = read("../achievements/src/main/java/ru/cherdakmd/chrdkachievements/AchievementMetric.java");
        assertTrue(metric.contains("disasters-survived"));
        assertTrue(metric.contains("disaster-kinds"));
        assertTrue(metric.contains("disaster-shards"));
        assertTrue(metric.contains("disaster-level"));
        String messages = read("../achievements/src/main/resources/messages.yml");
        assertTrue(messages.contains("ach.pervaya-katastrofa.name"));
        assertTrue(messages.contains("ach.stojkost-pyat.desc"));
    }
}
