package ru.cherdakmd.chrdkpets;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Реализация {@link PetsApi} поверх хранилища и текущего конфига. */
final class PetsApiImpl implements PetsApi {

    private final PetsPlugin plugin;

    PetsApiImpl(PetsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isPet(UUID petId) {
        return petId != null && plugin.store().has(petId);
    }

    @Override
    public int levelOf(UUID petId) {
        return plugin.levelOf(petId);
    }

    @Override
    public long xpOf(UUID petId) {
        PetStore.PetData data = plugin.store().get(petId);
        return data == null ? 0L : data.xp();
    }

    @Override
    public String displayNameOf(UUID petId) {
        PetStore.PetData data = plugin.store().get(petId);
        return data == null ? "" : plugin.service().displayName(data);
    }

    @Override
    public List<UUID> petsOf(UUID ownerId) {
        List<UUID> out = new ArrayList<>();
        for (PetStore.PetData data : plugin.store().petsOf(ownerId)) {
            out.add(data.id());
        }
        return List.copyOf(out);
    }

    @Override
    public double ownerSpeedBonusOf(UUID petId) {
        return stats(petId).ownerSpeedScalar();
    }

    @Override
    public double ownerLuckBonusOf(UUID petId) {
        return stats(petId).ownerLuck();
    }

    private PetBuffs.Stats stats(UUID petId) {
        PetStore.PetData data = plugin.store().get(petId);
        if (data == null) {
            return PetBuffs.Stats.NONE;
        }
        return PetBuffs.stats(plugin.config().species(data.species()).buffs(),
                plugin.levelOf(petId));
    }
}
