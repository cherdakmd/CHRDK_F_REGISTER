package ru.cherdakmd.chrdktreasures;

import java.util.Random;

/**
 * Броски особых находок (1.4.166): выпадет ли реликвия и книга чар, какой
 * стороны и какой уровень.
 *
 * <p>Класс чистый — без Bukkit и без аддонов-источников. Именно поэтому шансы
 * и границы проверяются тестами, а не «на живом сервере»: ошибка здесь стоит
 * игроку находки, а администратору — веры в конфиг.</p>
 */
final class TreasureSpecialRolls {

    /**
     * План одной находки.
     *
     * @param artifact       выпадает ли реликвия
     * @param artifactCursed проклятая ли она (иначе светлая)
     * @param enchant        выпадает ли книга чар
     * @param enchantCursed  проклятая ли чара в книге
     * @param levelCap       потолок уровня книги по редкости клада
     */
    record Plan(boolean artifact, boolean artifactCursed,
                boolean enchant, boolean enchantCursed, int levelCap) {

        boolean any() {
            return artifact || enchant;
        }
    }

    private TreasureSpecialRolls() {
    }

    /**
     * Попадание процента.
     *
     * <p>0 — никогда, 100 и выше — всегда; отрицательные и запредельные
     * значения трактуются так же, поэтому опечатка в конфиге не может
     * «выключить» находку, которую администратор хотел включить наверняка.</p>
     */
    static boolean hit(double percent, Random random) {
        if (percent <= 0.0) {
            return false;
        }
        if (percent >= 100.0) {
            return true;
        }
        return random.nextDouble() * 100.0 < percent;
    }

    /** План по редкости клада и настройкам; источник недоступен — пустой план. */
    static Plan plan(TreasuresConfig.Specials settings, TreasureTier tier, Random random) {
        if (settings == null || !settings.enabled() || tier == null) {
            return new Plan(false, false, false, false, 1);
        }
        boolean artifact = settings.artifacts()
                && hit(settings.artifactChance(tier), random);
        boolean artifactCursed = artifact && hit(settings.cursedArtifactPercent(), random);
        boolean enchant = settings.enchantments()
                && hit(settings.enchantChance(tier), random);
        boolean enchantCursed = enchant && hit(settings.curseBookPercent(), random);
        return new Plan(artifact, artifactCursed, enchant, enchantCursed,
                settings.enchantLevelCap(tier));
    }

    /** Уровень книги: 1..потолок, потолок не ниже единицы. */
    static int level(int cap, Random random) {
        int top = Math.max(1, Math.min(5, cap));
        return top <= 1 ? 1 : 1 + random.nextInt(top);
    }
}
