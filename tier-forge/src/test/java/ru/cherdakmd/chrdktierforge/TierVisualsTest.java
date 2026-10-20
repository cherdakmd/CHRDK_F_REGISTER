package ru.cherdakmd.chrdktierforge;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Визуальные эффекты тиров (1.4.119): у каждого тира свой стиль, конфиг
 * переопределяет и клампится, «датовые» и неизвестные частицы не роняют ауру.
 */
class TierVisualsTest {

    /** Разбор с перехватом предупреждений. */
    private record Parsed(TierVisuals visuals, List<String> warnings) {
    }

    private static Parsed parse(YamlConfiguration yaml) {
        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return new Parsed(TierVisuals.parse(yaml, logger), warnings);
    }

    private static YamlConfiguration empty() {
        return new YamlConfiguration();
    }

    @Test
    void everyTierHasItsOwnDefaults() {
        TierVisuals visuals = TierVisuals.defaults();
        assertEquals(7, visuals.tiers().size());
        Set<Integer> colors = new HashSet<>();
        for (String tier : TierDef.LADDER) {
            TierVisuals.Style style = visuals.styleFor(tier);
            assertNotNull(style, tier);
            assertTrue(style.hasParticles(), "у тира " + tier + " должна быть аура");
            TierVisuals.Effect first = style.effects().get(0);
            assertEquals(Particle.DUST, first.particle(), tier);
            assertTrue(first.count() >= 1 && first.count() <= TierVisuals.MAX_COUNT, tier);
            assertTrue(first.spread() >= 0 && first.spread() <= 2.0, tier);
            assertTrue(first.size() >= 0.1f && first.size() <= 4.0f, tier);
            assertTrue(colors.add(first.color().asRGB()), "цвет ауры тира " + tier + " уникален");
            for (TierVisuals.Effect effect : style.effects()) {
                assertTrue(effect.particle().getDataType() == Void.class
                                || effect.particle().getDataType() == Particle.DustOptions.class,
                        "частица " + effect.particle() + " не требует данных");
            }
        }
        assertEquals(TierVisuals.Glint.OFF, visuals.styleFor("cursed").glint());
        assertEquals(TierVisuals.Glint.ON, visuals.styleFor("epic").glint());
        assertEquals(TierVisuals.Glint.ON, visuals.styleFor("legendary").glint());
        assertEquals(TierVisuals.Glint.INHERIT, visuals.styleFor("common").glint());
        assertEquals(7, visuals.tiersWithParticles());
    }

    @Test
    void emptyConfigEqualsDefaults() {
        TierVisuals parsed = parse(empty()).visuals();
        TierVisuals defaults = TierVisuals.defaults();
        assertTrue(parsed.enabled());
        assertEquals(defaults.intervalTicks(), parsed.intervalTicks());
        assertFalse(parsed.selfOnly());
        assertTrue(parsed.armor());
        for (String tier : TierDef.LADDER) {
            assertEquals(defaults.styleFor(tier).glint(), parsed.styleFor(tier).glint(), tier);
            assertEquals(defaults.styleFor(tier).effects(), parsed.styleFor(tier).effects(), tier);
        }
    }

    @Test
    void globalSettingsAreClamped() {
        YamlConfiguration yaml = empty();
        yaml.set("visuals.interval-ticks", 1);
        yaml.set("visuals.self-only", true);
        yaml.set("visuals.armor", false);
        Parsed parsed = parse(yaml);
        assertEquals(TierVisuals.MIN_INTERVAL_TICKS, parsed.visuals().intervalTicks());
        assertTrue(parsed.visuals().selfOnly());
        assertFalse(parsed.visuals().armor());
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("visuals.interval-ticks")),
                "кламп периода должен попадать в лог: " + parsed.warnings());

        yaml.set("visuals.interval-ticks", 999);
        assertEquals(TierVisuals.MAX_INTERVAL_TICKS, parse(yaml).visuals().intervalTicks());
    }

    @Test
    void styleOverridesAreReadAndClamped() {
        YamlConfiguration yaml = empty();
        yaml.set("visuals.tiers.legendary.glint", "off");
        yaml.set("visuals.tiers.legendary.particles", List.of(Map.of(
                "type", "smoke",
                "count", 99,
                "spread", 9.0,
                "speed", 2.0)));
        Parsed parsed = parse(yaml);
        TierVisuals.Style style = parsed.visuals().styleFor("legendary");
        assertEquals(TierVisuals.Glint.OFF, style.glint());
        assertEquals(1, style.effects().size());
        TierVisuals.Effect effect = style.effects().get(0);
        assertEquals(Particle.SMOKE, effect.particle());
        assertEquals(TierVisuals.MAX_COUNT, effect.count());
        assertEquals(2.0, effect.spread(), 1e-9);
        assertEquals(1.0, effect.speed(), 1e-9);
        assertNull(effect.color());
        // Не тронутый в конфиге тир остаётся с умолчанием.
        assertEquals(TierVisuals.defaults().styleFor("rare"), parsed.visuals().styleFor("rare"));
    }

    @Test
    void emptyParticleListMeansNoAura() {
        YamlConfiguration yaml = empty();
        yaml.set("visuals.tiers.common.particles", List.of());
        TierVisuals.Style style = parse(yaml).visuals().styleFor("common");
        assertFalse(style.hasParticles());
        assertEquals(TierVisuals.Glint.INHERIT, style.glint());
    }

    @Test
    void unknownAndDataParticlesAreSkipped() {
        YamlConfiguration yaml = empty();
        yaml.set("visuals.tiers.fine.particles", List.of(
                Map.of("type", "NOT_A_PARTICLE"),
                Map.of("type", "ITEM"),
                Map.of("type", "minecraft:flame")));
        Parsed parsed = parse(yaml);
        List<TierVisuals.Effect> effects = parsed.visuals().styleFor("fine").effects();
        assertEquals(1, effects.size(), effects.toString());
        assertEquals(Particle.FLAME, effects.get(0).particle());
        assertEquals(2, parsed.warnings().size(), parsed.warnings().toString());
    }

    @Test
    void dustColorAndSizeAreApplied() {
        YamlConfiguration yaml = empty();
        yaml.set("visuals.tiers.rare.particles", List.of(Map.of(
                "type", "DUST",
                "color", "#12AB34",
                "size", 2.5,
                "count", 3)));
        TierVisuals.Effect effect = parse(yaml).visuals().styleFor("rare").effects().get(0);
        assertEquals(Color.fromRGB(0x12AB34), effect.color());
        assertEquals(2.5f, effect.size(), 1e-6);
    }

    @Test
    void brokenColorFallsBackToWhite() {
        YamlConfiguration yaml = empty();
        yaml.set("visuals.tiers.rare.particles", List.of(Map.of(
                "type", "DUST",
                "color", "фиолетовый")));
        Parsed parsed = parse(yaml);
        assertEquals(Color.WHITE, parsed.visuals().styleFor("rare").effects().get(0).color());
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("particles.color")),
                parsed.warnings().toString());
    }

    @Test
    void yamlBooleansInGlintAreUnderstood() {
        // Без кавычек SnakeYAML отдаёт on/off как true/false — смысл не должен теряться.
        YamlConfiguration yaml = empty();
        yaml.set("visuals.tiers.legendary.glint", false);
        yaml.set("visuals.tiers.cursed.glint", true);
        TierVisuals visuals = parse(yaml).visuals();
        assertEquals(TierVisuals.Glint.OFF, visuals.styleFor("legendary").glint());
        assertEquals(TierVisuals.Glint.ON, visuals.styleFor("cursed").glint());
    }

    @Test
    void unknownGlintFallsBackToInherit() {
        YamlConfiguration yaml = empty();
        yaml.set("visuals.tiers.epic.glint", "сияет");
        Parsed parsed = parse(yaml);
        assertEquals(TierVisuals.Glint.INHERIT, parsed.visuals().styleFor("epic").glint());
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("glint")),
                parsed.warnings().toString());
    }

    @Test
    void moreThanThreeLayersAreCutOff() {
        YamlConfiguration yaml = empty();
        yaml.set("visuals.tiers.epic.particles", List.of(
                Map.of("type", "FLAME"),
                Map.of("type", "SMOKE"),
                Map.of("type", "PORTAL"),
                Map.of("type", "END_ROD")));
        Parsed parsed = parse(yaml);
        assertEquals(TierVisuals.MAX_EFFECTS, parsed.visuals().styleFor("epic").effects().size());
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("слоёв")),
                parsed.warnings().toString());
    }

    @Test
    void stampTracksGlintAndMasterSwitch() {
        TierVisuals visuals = TierVisuals.defaults();
        assertEquals("glint:off", visuals.stampFor("cursed"));
        assertEquals("glint:on", visuals.stampFor("legendary"));
        assertEquals("glint:inherit", visuals.stampFor("common"));
        assertEquals("inherit", visuals.stampFor("unknown"));

        YamlConfiguration yaml = empty();
        yaml.set("visuals.enabled", false);
        TierVisuals disabled = parse(yaml).visuals();
        assertFalse(disabled.enabled());
        assertEquals("off", disabled.stampFor("legendary"));
    }
}
