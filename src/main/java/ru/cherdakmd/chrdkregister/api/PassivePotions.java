package ru.cherdakmd.chrdkregister.api;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Общий владелец пассивных зелий Core API32. Только основной поток.
 * Источники публикуют полный снимок; мгновенные и разовые эффекты сюда не входят.
 * Конечная длительность страхует от вечных эффектов после аварийной остановки.
 */
public final class PassivePotions implements Listener {
    private record Source(Plugin plugin, String channel) { }
    private record Key(UUID player, PotionEffectType type) { }
    private static final class State {
        PotionEffect owned;
        long ownedAt;
        PotionEffect fallback;
        long fallbackAt;
    }
    private static PassivePotions service;
    private final PassivePotionFrames<UUID, Source, PotionEffectType, PotionEffect> frames = new PassivePotionFrames<>();
    private final Map<Key, State> states = new HashMap<>();
    private final Set<Key> writing = new HashSet<>();
    private final Map<Key, PotionEffect> forceOld = new HashMap<>();
    private BukkitTask task;

    private PassivePotions() { }

    public static void start(Plugin core) {
        stop();
        service = new PassivePotions();
        Bukkit.getPluginManager().registerEvents(service, core);
        service.task = Bukkit.getScheduler().runTaskTimer(core, service::tick, 1L, 1L);
    }

    public static void stop() {
        if (service == null) return;
        PassivePotions old = service;
        if (old.task != null) old.task.cancel();
        for (UUID id : new HashSet<>(old.frames.players())) old.frames.remove(id);
        for (Key key : List.copyOf(old.states.keySet())) old.release(key);
        old.states.clear();
        old.frames.clear();
        HandlerList.unregisterAll(old);
        service = null;
    }

    /** Длительность входного PotionEffect не используется: это описание уровня и флагов. */
    public static void replace(Plugin source, Player player, Collection<PotionEffect> effects, int period) {
        replace(source, "passive", player, effects, period);
    }

    public static void replace(Plugin source, String channel, Player player, Collection<PotionEffect> effects, int period) {
        if (service == null) return;
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Пассивы обновляются только в основном потоке");
        Map<PotionEffectType, PotionEffect> wanted = new LinkedHashMap<>();
        if (source.isEnabled() && player.isOnline() && !player.isDead()) {
            for (PotionEffect effect : effects) {
                if (effect.getType().isInstant()) throw new IllegalArgumentException("Мгновенный эффект не является пассивом");
                wanted.merge(effect.getType(), effect, PassivePotions::stronger);
            }
        }
        service.frames.replace(player.getUniqueId(), new Source(source, channel), wanted,
                now() + 2L * Math.max(1, Math.min(1200, period)) + 40L);
        // Применение следующим тиком: несколько каналов одного инвентарного события
        // сначала публикуют полный результат, без промежуточного remove/add.
    }

    public static void cancel(Plugin source) {
        if (service == null) return;
        Set<UUID> affected = new HashSet<>(service.frames.players());
        service.frames.removeSources(key -> key.plugin() == source);
        for (UUID id : affected) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) service.reconcile(player);
        }
    }

    /** Длительность пульсового пассива не масштабируется как выпитое/боевое зелье. */
    public static boolean isApplying(Player player) {
        return service != null && service.writing.stream().anyMatch(key -> key.player().equals(player.getUniqueId()));
    }

    private static long now() { return Integer.toUnsignedLong(Bukkit.getCurrentTick()); }

    private static PotionEffect stronger(PotionEffect a, PotionEffect b) {
        if (a.getAmplifier() != b.getAmplifier()) return a.getAmplifier() > b.getAmplifier() ? a : b;
        return new PotionEffect(a.getType(), 1, a.getAmplifier(), a.isAmbient() && b.isAmbient(),
                a.hasParticles() || b.hasParticles(), a.hasIcon() || b.hasIcon());
    }

    private void tick() {
        Set<UUID> players = new HashSet<>(frames.players());
        for (Key key : states.keySet()) players.add(key.player());
        for (UUID id : players) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || player.isDead()) {
                frames.remove(id);
                for (Key key : List.copyOf(states.keySet())) if (key.player().equals(id)) release(key);
            } else reconcile(player);
        }
    }

    private void reconcile(Player player) {
        UUID id = player.getUniqueId();
        Map<PotionEffectType, PotionEffect> wanted = frames.desired(id, now(), source -> source.plugin().isEnabled(), PassivePotions::stronger);
        for (Key key : List.copyOf(states.keySet())) {
            if (key.player().equals(id) && !wanted.containsKey(key.type())) release(key);
        }
        for (PotionEffect effect : wanted.values()) {
            Key key = new Key(id, effect.getType());
            State state = states.computeIfAbsent(key, ignored -> new State());
            PotionEffect current = player.getPotionEffect(key.type());
            boolean own = owns(state, current);
            if (!own && current != null && PassivePotionPolicy.protectsExternal(
                    current.getAmplifier(), current.getDuration(), effect.getAmplifier())) continue;
            if (own && current.getAmplifier() == effect.getAmplifier()
                    && current.getDuration() > PassivePotionPolicy.REFRESH_AT
                    && flags(current, effect)) continue;
            if (!own && current != null) {
                state.fallback = current;
                state.fallbackAt = now();
            }
            int duration = PassivePotionPolicy.DURATION;
            if (own && current.getAmplifier() == effect.getAmplifier() && key.type().equals(PotionEffectType.REGENERATION)) {
                duration = PassivePotionPolicy.renewalDuration(current.getDuration(), Math.max(1, 50 >> Math.min(30, effect.getAmplifier())));
            }
            PotionEffect fresh = new PotionEffect(key.type(), duration,
                    effect.getAmplifier(), effect.isAmbient(), effect.hasParticles(), effect.hasIcon());
            if (write(player, key, fresh, own ? current : null)) {
                PotionEffect actual = player.getPotionEffect(key.type());
                state.owned = actual != null && flags(actual, fresh)
                        && actual.getAmplifier() == fresh.getAmplifier()
                        && Math.abs((long) actual.getDuration() - fresh.getDuration()) <= 2 ? actual : null;
                state.ownedAt = now();
            }
        }
    }

    private static boolean flags(PotionEffect a, PotionEffect b) {
        return a.isAmbient() == b.isAmbient() && a.hasParticles() == b.hasParticles() && a.hasIcon() == b.hasIcon();
    }

    private static boolean owns(State state, PotionEffect current) {
        return state.owned != null && current != null && flags(current, state.owned)
                && PassivePotionPolicy.matches(current.getAmplifier(), current.getDuration(),
                state.owned.getAmplifier(), state.owned.getDuration(), now() - state.ownedAt);
    }

    private boolean write(Player player, Key key, PotionEffect fresh, PotionEffect oldOwned) {
        writing.add(key);
        if (oldOwned != null) forceOld.put(key, oldOwned);
        try { return player.addPotionEffect(fresh); }
        finally { writing.remove(key); forceOld.remove(key); }
    }

    private void release(Key key) {
        release(key, Bukkit.getPlayer(key.player()));
    }

    private void release(Key key, Player player) {
        State state = states.remove(key);
        if (state == null || player == null) return;
        PotionEffect current = player.getPotionEffect(key.type());
        if (!owns(state, current)) return; // чужую замену не удаляем
        PotionEffect fallback = state.fallback;
        int left = fallback == null ? 0 : PassivePotionPolicy.remaining(fallback.getDuration(), now() - state.fallbackAt);
        if (left == -1 || left > 0) {
            write(player, key, new PotionEffect(key.type(), left, fallback.getAmplifier(),
                    fallback.isAmbient(), fallback.hasParticles(), fallback.hasIcon()), current);
        } else {
            writing.add(key);
            try { player.removePotionEffect(key.type()); }
            finally { writing.remove(key); }
        }
    }

    /** Меняем уровень собственного пассива без remove/add и разрыва эффекта. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void allowOwnedTransition(EntityPotionEffectEvent event) {
        Key key = new Key(event.getEntity().getUniqueId(), event.getModifiedType());
        PotionEffect expected = forceOld.get(key);
        if (expected != null && expected.equals(event.getOldEffect())) event.setOverride(true);
    }

    /** Любая разрешённая внешняя замена больше не считается нашим эффектом. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void observe(EntityPotionEffectEvent event) {
        Key key = new Key(event.getEntity().getUniqueId(), event.getModifiedType());
        State state = states.get(key);
        if (state == null || writing.contains(key)) return;
        PotionEffect incoming = event.getNewEffect();
        if (incoming != null) {
            // Даже короткое зелье под более длинным пассивом должно пережить снятие вещи.
            int oldLeft = state.fallback == null ? 0 : PassivePotionPolicy.remaining(state.fallback.getDuration(), now() - state.fallbackAt);
            if (state.fallback == null || PassivePotionPolicy.preferIncoming(state.fallback.getAmplifier(), oldLeft,
                    incoming.getAmplifier(), incoming.getDuration())) {
                state.fallback = incoming;
                state.fallbackAt = now();
            }
        }
        if (event.getAction() != EntityPotionEffectEvent.Action.CHANGED || event.isOverride()) {
            state.owned = null;
            if (incoming == null && event.getCause() != EntityPotionEffectEvent.Cause.EXPIRATION) {
                state.fallback = null; // молоко/очистка не должны возвращать ранее снятое зелье
            }
        }
    }

    @EventHandler public void quit(PlayerQuitEvent event) { clearPlayer(event.getPlayer()); }
    @EventHandler public void death(PlayerDeathEvent event) { clearPlayer(event.getEntity()); }
    @EventHandler public void disabled(PluginDisableEvent event) { cancel(event.getPlugin()); }

    private void clearPlayer(Player player) {
        UUID id = player.getUniqueId();
        frames.remove(id);
        for (Key key : List.copyOf(states.keySet())) if (key.player().equals(id)) release(key, player);
    }
}
