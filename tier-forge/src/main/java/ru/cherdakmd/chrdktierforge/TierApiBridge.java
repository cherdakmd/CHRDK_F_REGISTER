package ru.cherdakmd.chrdktierforge;

import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.api.ChrdkTierApi;

import java.util.List;
import java.util.Random;

/**
 * Реализация {@link ChrdkTierApi} поверх {@link TierItems} (1.4.141).
 *
 * <p>Мост нужен, чтобы другие аддоны могли выдать предмет
 * «с тиром», не завися от классов тир-форджа. Все проверки живут здесь: вид
 * предмета, известность тира, пустой стек. Ни один метод не бросает исключений
 * наружу — аддон, вызвавший мост, не должен падать из-за тир-системы.</p>
 */
final class TierApiBridge implements ChrdkTierApi {

    private final TierForgePlugin plugin;
    private final Random random = new Random();

    TierApiBridge(TierForgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isTierItem(ItemStack item) {
        return plugin.items() != null && plugin.items().isTierItem(item);
    }

    @Override
    public String tierOf(ItemStack item) {
        return plugin.items() == null ? null : plugin.items().tierOf(item);
    }

    @Override
    public List<String> ladder() {
        return List.copyOf(TierDef.LADDER);
    }

    @Override
    public boolean applyRandomTier(ItemStack item, String source) {
        if (!supported(item)) {
            return false;
        }
        TierDef.Snapshot tier = TierRoller.roll(plugin.config().tiers(), random);
        if (tier == null) {
            return false;
        }
        plugin.items().apply(item, tier);
        plugin.items().setSource(item, source);
        return true;
    }

    @Override
    public boolean applyTier(ItemStack item, String tierId, String source) {
        if (!supported(item) || tierId == null || tierId.isBlank()) {
            return false;
        }
        TierDef.Snapshot tier = plugin.config().find(tierId.trim().toLowerCase(java.util.Locale.ROOT));
        if (tier == null) {
            return false;
        }
        plugin.items().apply(item, tier);
        plugin.items().setSource(item, source);
        return true;
    }

    @Override
    public void refresh(ItemStack item) {
        if (item == null || plugin.items() == null) {
            return;
        }
        String tierId = plugin.items().tierOf(item);
        TierDef.Snapshot tier = tierId == null ? null : plugin.config().find(tierId);
        if (tier != null) {
            plugin.items().apply(item, tier);
        }
    }

    /**
     * Поддерживает ли тир-система этот предмет. Проверяем и конфиг, и вид:
     * элитра, еда или ведро получат {@code null} из {@link ItemKind#classify}
     * и останутся без тира — иначе в лоре появилась бы плашка без бонусов.
     *
     * <p>С 1.4.142 щит, рог и фонарь — это {@link ItemKind#UTILITY}: они тир
     * получают, но по своей, утилитарной раскладке (скорость и удача) и только
     * если в конфиге не выключен {@code craft.utilities}.</p>
     */
    private boolean supported(ItemStack item) {
        if (item == null || item.getType().isAir() || plugin.config() == null
                || plugin.items() == null) {
            return false;
        }
        ItemKind kind = ItemKind.classify(item.getType().name());
        return kind != null && plugin.config().kindAllowed(kind);
    }
}
