package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Сводка действующих настроек в консоль (1.4.100). */
class ConfigReportTest {

    private static TierForgeConfig parse(YamlConfiguration yaml) {
        return TierForgeConfig.parse(yaml, Logger.getLogger("ConfigReportTest"));
    }

    @Test
    void defaultsAreReportedAsEnabled() {
        List<String> lines = ConfigReport.lines(parse(new YamlConfiguration()));
        assertEquals(8, lines.size(),
                "восемь строк: крафт, опознание, наковальня, дроп, прочее, "
                        + "аффиксы/зачарования, кастомные чары, эффекты");
        assertTrue(lines.get(0).contains("100%"), lines.get(0));
        assertTrue(lines.get(1).contains("включено"), lines.get(1));
        assertTrue(lines.get(2).contains("перекат вкл."), lines.get(2));
        assertTrue(lines.get(3).contains("общий 15%"), lines.get(3));
        assertTrue(lines.get(3).contains("босс ×2"), "множитель без хвоста .0: " + lines.get(3));
        assertTrue(lines.get(5).contains("до 2 бонусов"), lines.get(5));
        assertTrue(lines.get(5).contains("шанс от 20%"), lines.get(5));
        assertTrue(lines.get(6).contains("кастомные чары (CHRDK_Enchants)"), lines.get(6));
        assertTrue(lines.get(6).contains("шанс от 18%"), lines.get(6));
        assertTrue(lines.get(6).contains("проклятия 15% с тира epic"), lines.get(6));
    }

    @Test
    void disabledFeaturesAreSpelledOut() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("roll-on-craft-chance-percent", 0);
        yaml.set("anvil.enabled", false);
        yaml.set("reroll.enabled", false);
        yaml.set("mob-drop.enabled", false);
        yaml.set("identify.enabled", false);
        List<String> lines = ConfigReport.lines(parse(yaml));
        assertTrue(lines.get(0).contains("выключен"), lines.get(0));
        assertTrue(lines.get(1).contains("тир виден сразу"), lines.get(1));
        assertTrue(lines.get(3).contains("выключен"), lines.get(3));
    }

    @Test
    void perKindChancesAndFractionalBossMultiplier() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("mob-drop.weapons-chance-percent", 40);
        yaml.set("mob-drop.armor-chance-percent", 0);
        yaml.set("mob-drop.boss-multiplier", 2.5);
        String drop = ConfigReport.lines(parse(yaml)).get(3);
        assertTrue(drop.contains("оружие 40%"), drop);
        assertTrue(drop.contains("броня выкл."), drop);
        assertTrue(drop.contains("×2.5"), drop);
    }

    @Test
    void visualsLineShowsMasterSwitch() {
        List<String> enabled = ConfigReport.lines(parse(new YamlConfiguration()));
        assertEquals(8, enabled.size(), enabled.toString());
        assertTrue(enabled.get(7).contains("аура каждые 10 тиков"), enabled.get(7));
        assertTrue(enabled.get(7).contains("7 тиров с частицами"), enabled.get(7));

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("visuals.enabled", false);
        assertTrue(ConfigReport.lines(parse(yaml)).get(7).contains("выключены"),
                ConfigReport.lines(parse(yaml)).get(7));
    }

    @Test
    void customEnchantsLineShowsSwitchAndCurseThreshold() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("enchantments.custom.enabled", false);
        List<String> lines = ConfigReport.lines(parse(yaml));
        assertTrue(lines.get(6).contains("выключены"), lines.get(6));

        YamlConfiguration noCurses = new YamlConfiguration();
        noCurses.set("enchantments.custom.curse-chance-percent", 0);
        assertTrue(ConfigReport.lines(parse(noCurses)).get(6).contains("без проклятий"),
                ConfigReport.lines(parse(noCurses)).get(6));
    }

    @Test
    void nullConfigAndNumberTrimming() {
        assertEquals(List.of("конфиг не загружен"), ConfigReport.lines(null));
        assertEquals("2", ConfigReport.trim(2.0));
        assertEquals("1.5", ConfigReport.trim(1.5));
        assertEquals("0", ConfigReport.trim(0.0));
    }
}
