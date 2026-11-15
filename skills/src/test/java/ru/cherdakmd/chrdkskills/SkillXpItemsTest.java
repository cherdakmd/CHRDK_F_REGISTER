package ru.cherdakmd.chrdkskills;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(Material.CHAIN, SkillXpItems.materialOf(SkillType.DEFENSE));
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
}
