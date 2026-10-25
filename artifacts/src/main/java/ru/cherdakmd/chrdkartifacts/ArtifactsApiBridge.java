package ru.cherdakmd.chrdkartifacts;

import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.api.ChrdkArtifactsApi;

import java.util.List;

/**
 * Реализация {@link ChrdkArtifactsApi} (1.4.166): другие аддоны — прежде всего
 * CHRDK_Treasures — могут выдать игроку настоящий артефакт, не зная ничего о
 * каталоге, свойствах и разметке модуля.
 *
 * <p>Свойства бросаются в момент {@link #create(String, int)} — ровно так же,
 * как при находке в сундуке или с моба: две копии одной реликвии могут
 * отличаться, и «таинственность» сохраняется.</p>
 *
 * <p>Артефакт, выключенный в конфиге аддона ({@code artifacts.<ид>.enabled:
 * false}), не попадает ни в список идов, ни в выдачу: чужой источник не должен
 * возвращать в мир то, что администратор убрал.</p>
 */
public final class ArtifactsApiBridge implements ChrdkArtifactsApi {

    private final ArtifactsPlugin plugin;

    public ArtifactsApiBridge(ArtifactsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> ids() {
        return ArtifactRegistry.all().stream()
                .filter(this::enabled)
                .map(ArtifactDef::id)
                .toList();
    }

    @Override
    public List<String> ids(boolean cursed) {
        ArtifactDef.Side side = cursed ? ArtifactDef.Side.CURSED : ArtifactDef.Side.GOOD;
        return ArtifactRegistry.bySide(side).stream()
                .filter(this::enabled)
                .map(ArtifactDef::id)
                .toList();
    }

    /** Выключенный в конфиге артефакт не попадает ни в пул, ни в выдачу. */
    private boolean enabled(ArtifactDef def) {
        ArtifactsConfig config = plugin.config();
        return config == null || config.isEnabled(def.id());
    }

    @Override
    public boolean isKnown(String id) {
        return ArtifactRegistry.byId(id) != null;
    }

    @Override
    public boolean isCursed(String id) {
        ArtifactDef def = ArtifactRegistry.byId(id);
        return def != null && def.side() == ArtifactDef.Side.CURSED;
    }

    @Override
    public String name(String id) {
        ArtifactDef def = ArtifactRegistry.byId(id);
        if (def == null || plugin.items() == null) {
            return "";
        }
        return plugin.items().nameOf(def);
    }

    @Override
    public ItemStack create(String id, int amount) {
        ArtifactDef def = ArtifactRegistry.byId(id);
        if (def == null || plugin.items() == null || !enabled(def)) {
            return null;
        }
        return plugin.items().build(def, Math.max(1, Math.min(64, amount)));
    }
}
