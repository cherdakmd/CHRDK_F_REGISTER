package ru.cherdakmd.chrdkgenerator;

import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Справочник ванильного топлива из API запущенного сервера, без ручного списка. */
final class GeneratorFurnaceFuel {
    /** Один предмет, его время горения и возвращаемая пустая тара, если есть. */
    record Fuel(String material, long burnMillis, String remainder) {
        Fuel {
            if (material == null || material.isBlank() || burnMillis <= 0
                    || (remainder != null && remainder.isBlank())) {
                throw new IllegalArgumentException("generator fuel definition");
            }
        }
    }

    private GeneratorFurnaceFuel() { }

    /**
     * Пустые, повреждённые, именованные и иные изменённые предметы не принимаются:
     * сведения об их компонентах нельзя терять при записи только материала.
     * Метод вызывается после инициализации реестров сервера, на основном потоке.
     */
    static Optional<Fuel> inspect(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0 || stack.getType().isAir()) return Optional.empty();
        Material material = stack.getType();
        var itemType = material.asItemType();
        if (itemType == null || !stack.isSimilar(new ItemStack(material))
                || stack.getAmount() > stack.getMaxStackSize()) return Optional.empty();
        if (!itemType.isFuel()) return Optional.empty();
        int ticks = itemType.getBurnDuration();
        var remainder = itemType.getCraftingRemainingItem();
        Material remainderMaterial = remainder == null ? null : remainder.asMaterial();
        if (remainder != null && remainderMaterial == null) return Optional.empty();
        return definition(material.name(), ticks, remainderMaterial == null ? null : remainderMaterial.name());
    }

    /** Отдельная чистая граница для проверки расчёта и отказа на неверных данных API. */
    static Optional<Fuel> definition(String material, long ticks, String remainder) {
        if (material == null || !material.matches("[A-Z0-9_]{1,128}") || ticks <= 0
                || (remainder != null && !remainder.matches("[A-Z0-9_]{1,128}"))) return Optional.empty();
        try {
            return Optional.of(new Fuel(material, GeneratorFuelBudget.fromFurnaceTicks(ticks, 1), remainder));
        } catch (ArithmeticException error) {
            return Optional.empty();
        }
    }
}
