package ru.cherdakmd.chrdkpets;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Реализация {@link PetsApi} поверх {@link PetStore} и текущей кривой. */
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
    public String nameOf(UUID petId) {
        PetStore.PetData data = plugin.store().get(petId);
        return data == null ? null : data.name();
    }

    @Override
    public List<UUID> petsOf(UUID ownerId) {
        List<UUID> out = new ArrayList<>();
        for (PetStore.PetData data : plugin.store().petsOf(ownerId)) {
            out.add(data.id());
        }
        return List.copyOf(out);
    }
}
