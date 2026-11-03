package ru.cherdakmd.chrdktierforge;

import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.api.ForgeMaterialSource;

/**
 * RC16: живой производитель восьми PDC-расходников для витрины магазина.
 * Регистрируется в {@code Bukkit.getServicesManager()} только на время работы
 * CHRDK_TierForge. Если аддон выключен или операция отключена в конфиге, SKU не
 * продаётся; уже принятые сделки восстанавливаются Core по замороженному гранту
 * и от этого моста не зависят.
 */
final class ForgeMaterialSourceImpl implements ForgeMaterialSource {

    private final TierForgePlugin plugin;

    ForgeMaterialSourceImpl(TierForgePlugin plugin) { this.plugin = plugin; }

    @Override public String provider() { return "CHRDK_TierForge"; }

    @Override public boolean available(String sku) {
        ForgeMaterial kind = ForgeMaterial.byId(sku);
        return kind != null && plugin.materials() != null && plugin.materials().operationEnabled(kind);
    }

    @Override public ItemStack prototype(String sku) {
        ForgeMaterial kind = ForgeMaterial.byId(sku);
        if (kind == null || !available(sku)) return null;
        return plugin.materials().item(kind, 1);
    }
}
