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
import org.bukkit.event.player.PlayerQuitEvent;
import ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent;
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
    private BukkitTask trailTask, saveTask;
    private CosmeticsGui gui;
    private final java.util.Set<java.util.UUID> welcomed = new java.util.HashSet<>();
    /** Частицы, о которых уже предупредили: не спамим в лог каждый тик. */
    private final java.util.Set<String> warnedParticles =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Неизменяемый снимок конфига. */
    record CosmeticsConfig(boolean passOnly, boolean shopEnabled, List<String> packProducts,
                           List<CosmeticsDefs.TrailDef> trails,
                           int everyTicks, CosmeticsDefs.JoinEffect joinEffect, int joinMinRank) {
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.store = new SelectionStore(getDataFolder().toPath(), getLogger());
        store.load();
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        reloadCosmeticsConfig();

        gui = new CosmeticsGui(this);
        Bukkit.getPluginManager().registerEvents(gui, this);
        Bukkit.getPluginManager().registerEvents(this, this);
        saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, store::save, 100L, 100L);
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
        if (gui != null) gui.stop();
        if (saveTask != null) saveTask.cancel();
        welcomed.clear();
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
                CosmeticsDefs.parseJoinEffect(joinRaw),
                Math.max(0, getConfig().getInt("cosmetics.join-effect.min-rank", 4)));
        if (gui != null) gui.closeAll();
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
            if (def == null || !eligible(player) || player.isInsideVehicle() || player.isInvisible()
                    || player.getMetadata("vanished").stream().anyMatch(org.bukkit.metadata.MetadataValue::asBoolean)) {
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
    public void onAuthentication(PlayerAuthCompletedEvent event) {
        Player player = event.getPlayer();
        if (welcomed.size() >= 4096 || !welcomed.add(player.getUniqueId())) return;
        CosmeticsConfig snapshot = config;
        Bukkit.getScheduler().runTask(this, () -> {
            if (snapshot != config || !eligible(player) || !store.joinEnabled(player.getUniqueId()) || !joinUnlocked(player)) return;
            CosmeticsDefs.JoinEffect effect = snapshot.joinEffect();
            Sound sound = resolveSound(effect.sound());
            if (sound != null) player.playSound(player.getLocation(), sound, (float) effect.volume(), (float) effect.pitch());
            Particle particle = resolveParticle(effect.particle());
            if (particle != null && !player.isInvisible()
                    && player.getMetadata("vanished").stream().noneMatch(org.bukkit.metadata.MetadataValue::asBoolean))
                player.getWorld().spawnParticle(particle, player.getLocation().add(0, 1.0, 0),
                        effect.particleCount(), 0.4, 0.8, 0.4, 0.05);
        });
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { welcomed.remove(event.getPlayer().getUniqueId()); }
    boolean eligible(Player player) {
        return isEnabled() && player.isOnline() && !player.isDead() && player.hasPermission("chrdkcosmetics.use")
                && coreApi != null && coreApi.isAuthenticated(player);
    }
    boolean joinUnlocked(Player player) {
        return coreApi != null && CosmeticsDefs.joinEffectUnlocked(config.joinEffect(), coreApi.rankLevel(player.getName()),
                config.joinMinRank(), id -> config.shopEnabled() && coreApi.hasEntitlement(player.getName(), id));
    }
    CosmeticsGui gui() { return gui; }
    boolean select(Player player, String id) {
        if (!eligible(player)) return false;
        if (id == null) { store.select(player.getUniqueId(), null); messages.send(player, "cleared"); return true; }
        var def = CosmeticsDefs.find(config.trails(), id);
        if (def == null) return false;
        if (!unlocked(player.getName(), def)) {
            messages.send(player, "locked", "name", Messages.trusted(def.displayName())); return false;
        }
        if (resolveParticle(def.particle()) == null) { messages.send(player, "unavailable-effect"); return false; }
        store.select(player.getUniqueId(), def.id());
        messages.send(player, "selected", "name", Messages.trusted(def.displayName()));
        return true;
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
                if (warnedParticles.size() < 256 && warnedParticles.add(id)) {
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
            // Совместимость с прежним default, который был enum-подобным, а не registry id.
            String key = id.equalsIgnoreCase("entity_player_levelup") ? "minecraft:entity.player.levelup" : id;
            NamespacedKey soundKey = NamespacedKey.fromString(key);
            return soundKey == null ? null : Registry.SOUNDS.get(soundKey);
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
