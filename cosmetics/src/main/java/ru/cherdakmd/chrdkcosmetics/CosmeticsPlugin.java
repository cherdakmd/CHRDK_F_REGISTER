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
 * CHRDK_Cosmetics — косметика владельцев ранга: шлейфы частиц при ходьбе
 * и торжественный эффект входа на сервер.
 */
public final class CosmeticsPlugin extends JavaPlugin implements Listener {

    private volatile Messages messages;
    private volatile CosmeticsConfig config;
    private volatile ChrdkRegisterApi coreApi;
    private SelectionStore store;
    private BukkitTask trailTask;
    /** Частицы, о которых уже предупредили: не спамим в лог каждый тик. */
    private final java.util.Set<String> warnedParticles =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Неизменяемый снимок конфига. */
    record CosmeticsConfig(boolean passOnly, boolean shopEnabled, List<String> packProducts,
                           List<CosmeticsDefs.TrailDef> trails,
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
                + ", покупки " + (config.shopEnabled() ? "вкл" : "выкл")
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

        List<String> packs = new ArrayList<>();
        for (String pack : getConfig().getStringList("cosmetics.shop.pack-products")) {
            if (pack != null && !pack.isBlank()) {
                packs.add(pack.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }

        this.config = new CosmeticsConfig(
                getConfig().getBoolean("cosmetics.access-pass-only", true),
                getConfig().getBoolean("cosmetics.shop.enabled", true),
                List.copyOf(packs),
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
            CosmeticsDefs.TrailDef def = CosmeticsDefs.find(config.trails(), selection);
            if (def == null || player.isInsideVehicle()) {
                continue;
            }
            if (!CosmeticsDefs.trailAllowed(!config.trails().isEmpty(),
                    unlocked(player.getName(), def), selection)) {
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
            int rank = coreApi == null ? 0 : coreApi.rankLevel(player.getName());
            boolean shop = config.shopEnabled() && coreApi != null;
            if (!CosmeticsDefs.joinEffectUnlocked(effect, rank,
                    getConfig().getInt("cosmetics.join-effect.min-rank", 4),
                    id -> shop && coreApi.hasEntitlement(player.getName(), id))) {
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

    /**
     * Открыт ли шлейф игроку: ранг (если {@code access-pass-only}) либо покупка
     * в каталоге ядра. Покупки проверяются через API v4 — оффлайн-данные, без Bukkit.
     */
    boolean unlocked(String playerName, CosmeticsDefs.TrailDef def) {
        int rankLevel = coreApi == null ? 0 : coreApi.rankLevel(playerName);
        boolean shop = config.shopEnabled() && coreApi != null;
        return CosmeticsDefs.unlocked(def, config.passOnly(), rankLevel > 0, rankLevel,
                id -> shop && coreApi.hasEntitlement(playerName, id),
                shop ? config.packProducts() : List.of());
    }

    /**
     * Частица по имени из конфига.
     *
     * <p>1.4.170: частицы с обязательными данными ({@code dust},
     * {@code dragon_breath}, {@code block}…) для шлейфа не подходят: вызов без
     * данных бросает {@link IllegalArgumentException}, а шлейф рисуется каждые
     * несколько тиков — сервер утонул бы в одинаковых ошибках. Такую настройку
     * пропускаем и предупреждаем один раз.</p>
     */
    Particle resolveParticle(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            Particle particle = Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(id));
            if (particle != null && particle.getDataType() != Void.class) {
                if (warnedParticles.add(id)) {
                    getLogger().warning("cosmetics: частица '" + id + "' требует данных ("
                            + particle.getDataType().getSimpleName()
                            + ") и не подходит для шлейфа — эффект пропущен.");
                }
                return null;
            }
            return particle;
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
