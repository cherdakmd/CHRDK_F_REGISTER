package ru.cherdakmd.chrdkcosmetics;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CHRDK_Cosmetics — косметика владельцев проходки: шлейфы частиц при ходьбе
 * и торжественный эффект входа на сервер.
 */
public final class CosmeticsPlugin extends JavaPlugin implements Listener {

    private volatile Messages messages;
    private volatile CosmeticsConfig config;
    private volatile ChrdkRegisterApi coreApi;
    private SelectionStore store;
    private BukkitTask trailTask;

    /** Неизменяемый снимок конфига. */
    record CosmeticsConfig(boolean passOnly, List<CosmeticsDefs.TrailDef> trails,
                           int everyTicks, CosmeticsDefs.JoinEffect joinEffect) {
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.store = new SelectionStore(getDataFolder().toPath(), getLogger());
        store.load();
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        reloadCosmeticsConfig();

        Bukkit.getPluginManager().registerEvents(this, this);
        PluginCommand command = getCommand("cosmetics");
        if (command != null) {
            CosmeticsCommandHandler handler = new CosmeticsCommandHandler(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        getLogger().info("CHRDK_Cosmetics включён: шлейфов " + config.trails().size()
                + ", вход-эффект " + (config.joinEffect().enabled() ? "вкл" : "выкл") + ".");
    }

    @Override
    public void onDisable() {
        if (trailTask != null) {
            trailTask.cancel();
        }
        if (store != null) {
            store.save();
        }
        getLogger().info("CHRDK_Cosmetics выключен.");
    }

    @SuppressWarnings("unchecked")
    public void reloadCosmeticsConfig() {
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());

        List<Map<?, ?>> rawTrails = new ArrayList<>();
        if (getConfig().getList("cosmetics.trail.defs") != null) {
            for (Object entry : getConfig().getList("cosmetics.trail.defs", List.of())) {
                if (entry instanceof Map<?, ?> map) {
                    rawTrails.add(map);
                }
            }
        }
        boolean trailEnabled = getConfig().getBoolean("cosmetics.trail.enabled", true);
        List<CosmeticsDefs.TrailDef> trails = CosmeticsDefs.parseTrails(rawTrails, trailEnabled);

        Map<?, ?> joinRaw = getConfig().getConfigurationSection("cosmetics.join-effect") != null
                ? getConfig().getConfigurationSection("cosmetics.join-effect").getValues(false)
                : Map.of();

        this.config = new CosmeticsConfig(
                getConfig().getBoolean("cosmetics.access-pass-only", true),
                trails,
                Math.max(2, getConfig().getInt("cosmetics.trail.every-ticks", 4)),
                CosmeticsDefs.parseJoinEffect(joinRaw));
        restartTrailTask();
    }

    private void restartTrailTask() {
        if (trailTask != null) {
            trailTask.cancel();
        }
        if (config == null || config.trails().isEmpty()) {
            return;
        }
        trailTask = Bukkit.getScheduler().runTaskTimer(this, this::spawnTrails,
                config.everyTicks(), config.everyTicks());
    }

    /** Порция частиц за спинами выбравших шлейф игроков. */
    private void spawnTrails() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            String selection = store.selectionOf(player.getUniqueId());
            boolean hasPass = coreApi != null && coreApi.hasActivePass(player.getName());
            if (!CosmeticsDefs.trailAllowed(!config.trails().isEmpty(), config.passOnly(),
                    hasPass, selection)) {
                continue;
            }
            CosmeticsDefs.TrailDef def = CosmeticsDefs.find(config.trails(), selection);
            if (def == null || player.isInsideVehicle()) {
                continue;
            }
            Particle particle = resolveParticle(def.particle());
            if (particle == null) {
                continue;
            }
            player.getWorld().spawnParticle(particle,
                    player.getLocation().add(0, 0.15, 0),
                    def.count(), def.spread(), def.spread(), def.spread(), def.speed());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        CosmeticsDefs.JoinEffect effect = config.joinEffect();
        if (!effect.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (config.passOnly()
                    && (coreApi == null || !coreApi.hasActivePass(player.getName()))) {
                return;
            }
            Sound sound = resolveSound(effect.sound());
            if (sound != null) {
                player.playSound(player.getLocation(), sound,
                        (float) effect.volume(), (float) effect.pitch());
            }
            Particle particle = resolveParticle(effect.particle());
            if (particle != null) {
                player.getWorld().spawnParticle(particle,
                        player.getLocation().add(0, 1.0, 0),
                        effect.particleCount(), 0.4, 0.8, 0.4, 0.05);
            }
        });
    }

    static Particle resolveParticle(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(id));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    static Sound resolveSound(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return Registry.SOUNDS.get(NamespacedKey.minecraft(id));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public Messages messages() {
        return messages;
    }

    public CosmeticsConfig config() {
        return config;
    }

    public SelectionStore store() {
        return store;
    }

    ChrdkRegisterApi coreApi() {
        return coreApi;
    }
}
