package ru.cherdakmd.chrdkskills;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Кристаллы опыта навыков: стакуемые материалы и использование правым кликом.
 * Старые зелья остаются рабочими и конвертируются в кристаллы при входе.
 */
class SkillXpItemsTest {

    @Test
    void everySkillHasOwnStackableCrystalMaterial() {
        assertEquals(Material.RAW_IRON, SkillXpItems.materialOf(SkillType.MINING));
        assertEquals(Material.OAK_SAPLING, SkillXpItems.materialOf(SkillType.WOODCUTTING));
        assertEquals(Material.CLAY_BALL, SkillXpItems.materialOf(SkillType.DIGGING));
        assertEquals(Material.WHEAT, SkillXpItems.materialOf(SkillType.FARMING));
        assertEquals(Material.TROPICAL_FISH, SkillXpItems.materialOf(SkillType.FISHING));
        assertEquals(Material.IRON_NUGGET, SkillXpItems.materialOf(SkillType.COMBAT));
        assertEquals(Material.FEATHER, SkillXpItems.materialOf(SkillType.ARCHERY));
        assertEquals(Material.IRON_BARS, SkillXpItems.materialOf(SkillType.DEFENSE));
        assertEquals(Material.NETHER_WART, SkillXpItems.materialOf(SkillType.ALCHEMY));
        assertEquals(Material.LAPIS_LAZULI, SkillXpItems.materialOf(SkillType.ENCHANTING));
    }

    @Test
    void crystalsAreUsedByRightClickAndLegacyPotionsConvertOnJoin() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/ru/cherdakmd/chrdkskills/SkillXpItems.java"), StandardCharsets.UTF_8);
        assertTrue(source.contains("skill_xp_v1"),
                "метка предмета сохранена: старые зелья остаются узнаваемыми");
        assertTrue(source.contains("PlayerInteractEvent"),
                "использование — правый клик, без питья и ванильной магии");
        assertTrue(source.contains("ПКМ — использовать"),
                "лор подсказывает способ применения");
        assertTrue(source.contains("Кристалл опыта: "),
                "имя предмета — кристалл опыта навыка");
        assertTrue(source.contains("PlayerJoinEvent"),
                "конвертация старых зелий происходит при входе");
        assertTrue(source.contains("Material.POTION ? skill(item) : null"),
                "только PDC-зелья конвертируются: чужие зелья не трогаются");
    }

    @Test
    void optionalVisualsAreOnlyAppliedAtCrystalCreation() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/ru/cherdakmd/chrdkskills/SkillXpItems.java"), StandardCharsets.UTF_8);
        var config = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
        assertTrue(source.contains("getBoolean(\"textures.crystals\", false)"));
        assertEquals(source.indexOf("SkillCrystalVisuals.applyNew"), source.lastIndexOf("SkillCrystalVisuals.applyNew"));
        assertTrue(source.contains("SkillCrystalVisuals.applyNew"));
        assertTrue(source.contains("create(entry.getKey(), false)"),
                "конвертация зелий не меняет старую совместимость стаков");
        assertTrue(config.isBoolean("textures.crystals"));
        assertFalse(config.getBoolean("textures.crystals", true), "оформление кристаллов по умолчанию выключено");
    }

    /**
     * Навык на потолке: кристалл не копит мёртвый опыт, а даёт уровень шкалы
     * опыта Minecraft и деньги в /bal (оба бонуса, money-reward).
     */
    @Test
    void maxedSkillCrystalPaysMoneyAndVanillaLevelInsteadOfDeadSkillXp() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/ru/cherdakmd/chrdkskills/SkillXpItems.java"), StandardCharsets.UTF_8);
        assertTrue(source.contains("giveExpLevels(1)"),
                "кристалл на максимуме даёт уровень шкалы опыта Minecraft");
        assertTrue(source.contains("moneyLoot().accept("),
                "кристалл на максимуме платит деньги через общий денежный источник");
        assertTrue(source.contains("xp-potion-money") && source.contains("xp-potion-vanilla"),
                "тексты про деньги и про ванильный уровень");
        assertTrue(source.contains("xp-potions.money-reward"),
                "награда настраивается конфигом");
        assertTrue(source.contains("xp-potion-awards-paused"),
                "приостановленный источник наград — своя причина отказа с подсказкой администрации");
        assertTrue(source.contains("xp-potion-storage-broken"),
                "повреждённое хранилище расходников — своя причина отказа");
        assertTrue(source.contains("failStamps"),
                "повторяющиеся отказы не спамят в чат: одинаковое сообщение не чаще раза в 3 секунды");
        assertTrue(source.contains("На максимуме навыка: +"),
                "лор предмета предупреждает о награде");
        assertTrue(source.indexOf("giveExpLevels(1)") < source.indexOf("grantXp(player, skill, xp)"),
                "ветка максимума обрабатывается до начисления опыта навыка");
    }
}
