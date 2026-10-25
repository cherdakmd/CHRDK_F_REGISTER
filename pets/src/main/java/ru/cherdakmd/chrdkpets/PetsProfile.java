package ru.cherdakmd.chrdkpets;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Раздел питомцев в {@code /profile} (1.4.156).
 *
 * <p>Сколько питомцев живо и кто из них старший по уровню. Погибшие записи не
 * считаются: профиль показывает только тех, кто рядом.</p>
 */
final class PetsProfile implements ChrdkRegisterApi.ProfileProvider {

    private final PetsPlugin plugin;

    PetsProfile(PetsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public ChrdkRegisterApi.ProfileSection section(ChrdkRegisterApi.ProfileContext context) {
        if (context == null || plugin.store() == null || plugin.service() == null) {
            return null;
        }
        List<PetStore.PetData> pets = plugin.store().alivePetsOf(context.playerId());
        if (pets.isEmpty()) {
            return null;
        }
        PetStore.PetData best = null;
        int bestLevel = 0;
        for (PetStore.PetData pet : pets) {
            int level = plugin.service().level(pet);
            if (best == null || level > bestLevel) {
                best = pet;
                bestLevel = level;
            }
        }
        Messages messages = plugin.messages();
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get("profile-count", "count", String.valueOf(pets.size())));
        if (best != null) {
            lines.add(messages.get("profile-best",
                    "name", plugin.service().displayName(best),
                    "level", String.valueOf(bestLevel)));
        }
        return new ChrdkRegisterApi.ProfileSection(messages.raw("profile-title"),
                Material.BONE, lines);
    }
}
