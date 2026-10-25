package ru.cherdakmd.chrdkhunt;

/**
 * Материал, который падает с моба или лежит в сундуке охотника.
 *
 * <p>Это не валюта: монеты и осколки лежат в кошельке, а здесь — обычные
 * ванильные вещи, которые приятно найти. Шанс в процентах (0..100), количество
 * в диапазоне.</p>
 *
 * @param material  что падает
 * @param minAmount минимальное количество
 * @param maxAmount максимальное количество
 * @param chance    шанс выпадения в процентах
 */
public record HuntLoot(org.bukkit.Material material, int minAmount, int maxAmount, double chance) {

    /** Больше стака в один дроп не кладём — иначе он всё равно рассыплется. */
    public static final int MAX_AMOUNT = 64;

    public HuntLoot {
        if (material == null) {
            throw new IllegalArgumentException("Материал добычи не может быть пустым");
        }
        minAmount = Math.clamp(minAmount, 1, MAX_AMOUNT);
        maxAmount = Math.clamp(maxAmount, minAmount, MAX_AMOUNT);
        chance = Double.isFinite(chance) ? Math.clamp(chance, 0.0, 100.0) : 0.0;
    }

    /** Выпадает ли дроп по броску; {@code null} — шанс 100 %. */
    public boolean rolls(java.util.Random random) {
        if (chance >= 100.0) {
            return true;
        }
        if (chance <= 0.0) {
            return false;
        }
        java.util.Random rng = random == null ? new java.util.Random() : random;
        return rng.nextDouble() * 100.0 < chance;
    }

    /** Случайное количество в диапазоне; без {@link java.util.Random} — минимальное. */
    public int amount(java.util.Random random) {
        if (maxAmount <= minAmount || random == null) {
            return minAmount;
        }
        return minAmount + random.nextInt(maxAmount - minAmount + 1);
    }
}
