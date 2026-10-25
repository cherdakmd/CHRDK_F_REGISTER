package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разделение ролей (1.4.141): бонус тира зависит от вида предмета, а не от
 * того, «оружие ли это вообще». Проверяем обе половины правила — тировые
 * проценты ({@link TierBonus}) и пулы ковки/свойств/аффиксов.
 */
class TierRolesTest {

    private static final Set<ItemKind> TOOLS =
            EnumSet.of(ItemKind.PICKAXE, ItemKind.SHOVEL, ItemKind.HOE);
    private static final Set<ItemKind> MELEE =
            EnumSet.of(ItemKind.SWORD, ItemKind.TRIDENT);
    private static final Set<ItemKind> RANGED =
            EnumSet.of(ItemKind.BOW, ItemKind.CROSSBOW);
    private static final Set<ItemKind> ARMOR =
            EnumSet.of(ItemKind.HELMET, ItemKind.CHESTPLATE, ItemKind.LEGGINGS, ItemKind.BOOTS);

    /** Эффекты, которые усиливают удар и бессмысленны на броне. */
    private static final Set<ForgeUpgrade.Effect> OFFENSIVE = EnumSet.of(
            ForgeUpgrade.Effect.ATTACK_PERCENT, ForgeUpgrade.Effect.ATTACK_SPEED_PERCENT,
            ForgeUpgrade.Effect.RAW_DAMAGE, ForgeUpgrade.Effect.ATTACK_KNOCKBACK,
            ForgeUpgrade.Effect.REACH, ForgeUpgrade.Effect.SWEEP_RATIO,
            ForgeUpgrade.Effect.ATTACK_SPEED_FLAT);

    /** Эффекты, которые работают только в ближнем бою. */
    private static final Set<ForgeUpgrade.Effect> MELEE_ONLY = EnumSet.of(
            ForgeUpgrade.Effect.ATTACK_PERCENT, ForgeUpgrade.Effect.ATTACK_SPEED_PERCENT,
            ForgeUpgrade.Effect.LIFESTEAL, ForgeUpgrade.Effect.CRIT_CHANCE,
            ForgeUpgrade.Effect.EXECUTE, ForgeUpgrade.Effect.SMITE,
            ForgeUpgrade.Effect.RAW_DAMAGE, ForgeUpgrade.Effect.ATTACK_KNOCKBACK,
            ForgeUpgrade.Effect.REACH, ForgeUpgrade.Effect.SWEEP_RATIO,
            ForgeUpgrade.Effect.IGNITE, ForgeUpgrade.Effect.SLOW_HIT,
            ForgeUpgrade.Effect.POISON_HIT, ForgeUpgrade.Effect.BLIND_HIT,
            ForgeUpgrade.Effect.FIRST_STRIKE, ForgeUpgrade.Effect.ARMOR_PIERCE,
            ForgeUpgrade.Effect.RAGE, ForgeUpgrade.Effect.THUNDER,
            ForgeUpgrade.Effect.WITHER_HEX, ForgeUpgrade.Effect.DEBUFF_DAMAGE,
            ForgeUpgrade.Effect.PVP_DAMAGE, ForgeUpgrade.Effect.ANIMAL_DAMAGE,
            ForgeUpgrade.Effect.CLEANSE_HIT, ForgeUpgrade.Effect.FREEZE_HIT,
            ForgeUpgrade.Effect.HOOK, ForgeUpgrade.Effect.ATTACK_SPEED_FLAT,
            ForgeUpgrade.Effect.PARRY);

    @Test
    void toolsGetMiningAndNeverCombat() {
        for (ItemKind kind : TOOLS) {
            assertEquals(List.of(TierBonus.MINING_SPEED), TierBonus.forKind(kind),
                    kind + " должен получать только скорость добычи");
            assertTrue(TierBonus.hasMiningBonus(kind));
            assertFalse(TierBonus.hasCombatBonus(kind), kind + " не должен получать урон тира");
        }
    }

    @Test
    void meleeGetsAttackAndNeverMining() {
        for (ItemKind kind : MELEE) {
            assertEquals(List.of(TierBonus.ATTACK_DAMAGE), TierBonus.forKind(kind));
            assertTrue(TierBonus.hasCombatBonus(kind));
            assertFalse(TierBonus.hasMiningBonus(kind));
        }
    }

    @Test
    void rangedGetsProjectileBonusOnly() {
        for (ItemKind kind : RANGED) {
            assertEquals(List.of(TierBonus.PROJECTILE_DAMAGE), TierBonus.forKind(kind));
            assertTrue(TierBonus.hasCombatBonus(kind));
            assertFalse(TierBonus.hasMiningBonus(kind));
        }
    }

    @Test
    void axeStaysHybrid() {
        assertEquals(List.of(TierBonus.ATTACK_DAMAGE, TierBonus.MINING_SPEED),
                TierBonus.forKind(ItemKind.AXE));
    }

    @Test
    void armorGetsDefenceOnly() {
        for (ItemKind kind : ARMOR) {
            assertEquals(List.of(TierBonus.ARMOR, TierBonus.ARMOR_TOUGHNESS,
                    TierBonus.MOVEMENT_SPEED), TierBonus.forKind(kind));
            assertFalse(TierBonus.hasCombatBonus(kind));
            assertFalse(TierBonus.hasMiningBonus(kind));
        }
    }

    @Test
    void upgradeEffectsNeverLeakIntoWrongRole() {
        for (ForgeUpgrade.Def def : ForgeUpgrade.ALL) {
            if (def.target() == ForgeUpgrade.Target.ARMOR) {
                // Парирование на броне — защита, а не чужой удар; режем только
                // эффекты, которые усиливают атаку владельца.
                assertFalse(OFFENSIVE.contains(def.effect()),
                        "боевой эффект атаки на броне: " + def.id());
            }
            if (def.target() == ForgeUpgrade.Target.TOOL) {
                assertFalse(MELEE_ONLY.contains(def.effect()),
                        "боевой эффект у инструмента: " + def.id());
            }
            assertFalse(def.target() == ForgeUpgrade.Target.WEAPON
                            && !MELEE_ONLY.contains(def.effect())
                            && def.effect() == ForgeUpgrade.Effect.SNIPE
                            && def.fits(ItemKind.SWORD),
                    "«Снайпер» не должен подходить мечу: " + def.id());
        }
        // «Снайпер» существует и подходит луку
        ForgeUpgrade.Def snipe = ForgeUpgrade.ALL.stream()
                .filter(def -> def.effect() == ForgeUpgrade.Effect.SNIPE)
                .findFirst().orElse(null);
        assertTrue(snipe != null && snipe.fits(ItemKind.BOW),
                "у лука должен быть «Снайпер»");
        assertFalse(snipe.fits(ItemKind.SWORD));
        assertFalse(snipe.fits(ItemKind.PICKAXE));
    }

    @Test
    void attackUpgradesNeverFitTools() {
        for (ForgeUpgrade.Def def : ForgeUpgrade.ALL) {
            if (!def.effect().name().contains("ATTACK") && def.effect() != ForgeUpgrade.Effect.RAW_DAMAGE
                    && def.effect() != ForgeUpgrade.Effect.REACH
                    && def.effect() != ForgeUpgrade.Effect.SWEEP_RATIO) {
                continue;
            }
            for (ItemKind kind : TOOLS) {
                assertFalse(def.fits(kind), def.id() + " не должен подходить " + kind);
            }
        }
    }

    @Test
    void utilityGetsNoCombatMiningOrArmorProfile() {
        // 1.4.142: утилита (щит, рог, фонарь, тотем) — четвёртая роль. Ей можно
        // тир, но нельзя чужие бонусы: проверяем и проценты, и все три пула.
        assertEquals(List.of(TierBonus.MOVEMENT_SPEED, TierBonus.LUCK),
                TierBonus.forKind(ItemKind.UTILITY));
        assertFalse(TierBonus.hasCombatBonus(ItemKind.UTILITY));
        assertFalse(TierBonus.hasMiningBonus(ItemKind.UTILITY));

        for (String tier : TierDef.LADDER) {
            for (TierProperty property : TierProperties.candidates(tier, ItemKind.UTILITY)) {
                assertEquals(ForgeUpgrade.Target.ANY, property.target(),
                        property.id() + " — утилите досталось свойство чужой роли");
                assertFalse(MELEE_ONLY.contains(property.effect()),
                        property.id() + " — боевое свойство на утилите");
                assertFalse(OFFENSIVE.contains(property.effect()),
                        property.id() + " — усиление атаки на утилите");
                assertFalse(property.effect() == ForgeUpgrade.Effect.DIG_SPEED_PERCENT
                                || property.effect() == ForgeUpgrade.Effect.MINING_EFFICIENCY
                                || property.effect() == ForgeUpgrade.Effect.ORE_DOUBLE,
                        property.id() + " — добыча на утилите");
            }
        }
        for (TierAffix affix : TierAffixes.all()) {
            if (!affix.fits(ItemKind.UTILITY)) {
                continue;
            }
            assertEquals(ForgeUpgrade.Target.ANY, affix.target(),
                    affix.id() + " — аффикс чужой роли на утилите");
            assertFalse(MELEE_ONLY.contains(affix.effect()),
                    affix.id() + " — боевой аффикс на утилите");
        }
        for (ForgeUpgrade.Def def : ForgeUpgrade.ALL) {
            if (!def.fits(ItemKind.UTILITY)) {
                continue;
            }
            assertEquals(ForgeUpgrade.Target.ANY, def.target(),
                    def.id() + " — ковка чужой роли на утилите");
        }
        assertTrue(TierEnchants.pool(ItemKind.UTILITY).isEmpty(),
                "утилита не зачаровывается — чары на ней были бы мёртвой строкой");
    }

    @Test
    void propertyPoolsCoverRangedInEveryTier() {
        for (String tier : TierDef.LADDER) {
            assertTrue(TierProperties.countFor(tier, ItemKind.BOW) >= 3,
                    tier + ": у лука меньше трёх свойств");
            assertTrue(TierProperties.countFor(tier, ItemKind.CROSSBOW) >= 3,
                    tier + ": у арбалета меньше трёх свойств");
            for (TierProperty property : TierProperties.candidates(tier, ItemKind.BOW)) {
                assertFalse(MELEE_ONLY.contains(property.effect()),
                        property.id() + " — боевое свойство ближнего боя на луке");
                assertFalse(property.effect() == ForgeUpgrade.Effect.MINING_EFFICIENCY,
                        property.id() + " — добыча на луке");
            }
            for (TierProperty property : TierProperties.candidates(tier, ItemKind.SWORD)) {
                assertFalse(property.effect() == ForgeUpgrade.Effect.SNIPE,
                        property.id() + " — снайпер на мече");
                assertFalse(property.effect() == ForgeUpgrade.Effect.DIG_SPEED_PERCENT
                                || property.effect() == ForgeUpgrade.Effect.ORE_DOUBLE,
                        property.id() + " — добыча на мече");
            }
        }
    }
}
