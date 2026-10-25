package ru.cherdakmd.chrdkbosses;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Боссы и зоны опасности (1.4.162).
 *
 * <p>Сервис держит живые бои: спавн, фазы, способности, вклад участников и
 * награду. Босс — это обычный моб с усиленным здоровьем и тегом: он не
 * ломает другие аддоны, но его смерть считается отдельно и даёт отдельную
 * награду. Награда делится по нанесённому урону, трофей забирает лучший —
 * так вклад «постоял рядом» не оплачивается.</p>
 *
 * <p>Зоны опасности — места, где мир сопротивляется: эффекты и капель урона
 * в обмен на надбавку к опыту. Урон зоны никогда не отнимает последние два
 * сердца: убивать должен бой, а не география.</p>
 */
public final class BossService {

    /** Тег, по которому босс узнаётся после перезапуска и в чужих событиях. */
    public static final String BOSS_TAG = "chrdk_boss";
    /** Тег свиты — её убирают вместе с хозяином. */
    public static final String MINION_TAG = "chrdk_boss_minion";
    /** Префикс тега с идом босса. */
    public static final String ID_TAG = "chrdk_boss:";
    /** Пауза перед первым появлением после старта сервера. */
    private static final long STARTUP_GRACE_MILLIS = 60_000L;

    private final BossesPlugin plugin;
    private final BossStore store;
    private final Map<UUID, ActiveBoss> byEntity = new LinkedHashMap<>();
    private final Map<String, ActiveBoss> byId = new LinkedHashMap<>();
    private final Map<UUID, DangerZone> playerZone = new HashMap<>();
    private final Map<UUID, Long> nextZoneEffect = new HashMap<>();
    private final Map<UUID, Long> nextZoneBonus = new HashMap<>();
    private volatile BossConfig config;

    private long lastBarUpdate;

    public BossService(BossesPlugin plugin, BossStore store, BossConfig config) {
        this.plugin = plugin;
        this.store = store;
        this.config = config == null ? BossConfig.defaults() : config;
    }

    public BossConfig config() {
        return config;
    }

    public void applyConfig(BossConfig config) {
        if (config != null) {
            this.config = config;
        }
    }

    /* ---------------------------------- доступ -------------------------------- */

    /** Живой босс по сущности или {@code null}. */
    public ActiveBoss byEntity(UUID entityId) {
        return entityId == null ? null : byEntity.get(entityId);
    }

    /** Живой босс по иду или {@code null}. */
    public ActiveBoss current(String bossId) {
        if (bossId == null) {
            return null;
        }
        return byId.get(bossId.trim().toLowerCase(Locale.ROOT));
    }

    /** Все идущие бои. */
    public List<ActiveBoss> active() {
        return List.copyOf(byId.values());
    }

    public int aliveCount() {
        return byId.size();
    }

    /** Зона, в которой стоит игрок, или {@code null}. */
    public DangerZone zoneAt(Player player) {
        if (player == null) {
            return null;
        }
        Location location = player.getLocation();
        return BossMath.zoneAt(config.activeZones(), location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ());
    }

    /* ---------------------------------- спавн --------------------------------- */

    /**
     * Ставит босса на арену.
     *
     * @param forced ручной запуск: игнорирует паузу и порог онлайна
     * @return сессия боя или {@code null}, если поставить не удалось
     */
    public ActiveBoss spawn(String bossId, boolean forced) {
        if (!config.enabled()) {
            return null;
        }
        BossDefinition definition = config.boss(bossId);
        if (definition == null) {
            return null;
        }
        if (byId.containsKey(definition.id())) {
            return null;
        }
        World world = Bukkit.getWorld(definition.arena().world());
        if (world == null) {
            plugin.getLogger().warning("[CHRDK_Bosses] Мир «" + definition.arena().world()
                    + "» не загружен — босс " + definition.id() + " не появился.");
            return null;
        }
        Location location = spawnPoint(world, definition.arena());
        Entity spawned = world.spawnEntity(location, definition.entity());
        if (!(spawned instanceof LivingEntity living)) {
            spawned.remove();
            plugin.getLogger().warning("[CHRDK_Bosses] " + definition.entity().name()
                    + " — не живое существо, босс " + definition.id() + " не появился.");
            return null;
        }
        long now = System.currentTimeMillis();
        BossBar bar = BossBar.bossBar(
                plugin.messages().get("boss-bar", "name", definition.name(),
                        "phase", definition.phases().get(0).name(), "health", "100"),
                1.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        AttributeInstance baseSpeed = living.getAttribute(Attribute.MOVEMENT_SPEED);
        ActiveBoss boss = new ActiveBoss(definition, living, now,
                now + definition.durationSeconds() * 1_000L, bar,
                baseSpeed == null ? 0.23 : baseSpeed.getBaseValue());
        configure(living, definition);
        byEntity.put(living.getUniqueId(), boss);
        byId.put(definition.id(), boss);
        announceSpawn(definition);
        plugin.requestSave();
        return boss;
    }

    private void configure(LivingEntity living, BossDefinition definition) {
        AttributeInstance maxHealth = living.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(definition.health());
        }
        living.setHealth(Math.max(1.0, definition.health()));
        living.customName(nameComponent(definition, definition.phases().get(0).name()));
        living.setCustomNameVisible(true);
        living.setPersistent(true);
        living.setGlowing(definition.glowing());
        living.addScoreboardTag(BOSS_TAG);
        living.addScoreboardTag(ID_TAG + definition.id());
        living.setCanPickupItems(false);
        if (living instanceof Mob mob) {
            mob.setAware(true);
            mob.setRemoveWhenFarAway(false);
        }
    }

    private Location spawnPoint(World world, Arena arena) {
        double y = arena.autoHeight()
                ? world.getHighestBlockYAt((int) Math.floor(arena.x()), (int) Math.floor(arena.z())) + 1.0
                : arena.y();
        return new Location(world, arena.x() + 0.5, y, arena.z() + 0.5);
    }

    private void announceSpawn(BossDefinition definition) {
        Component message = plugin.messages().get("boss-spawned", "name", definition.name(),
                "arena", definition.arena().label());
        Bukkit.getServer().broadcast(message);
        plugin.getLogger().info("[CHRDK_Bosses] Босс " + definition.id()
                + " появился: " + definition.arena().label());
    }

    /* ---------------------------------- смерть -------------------------------- */

    /**
     * Засчитывает победу: делит награду по вкладу и закрывает бой.
     *
     * @param killer последний ударивший; может быть {@code null}
     */
    public void handleDeath(ActiveBoss boss, Player killer) {
        if (boss == null) {
            return;
        }
        long now = System.currentTimeMillis();
        BossDefinition definition = boss.definition();
        BossReward reward = definition.reward();
        Map<UUID, Double> shares = boss.shares();

        for (Map.Entry<UUID, Double> entry : shares.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            int xp = BossMath.xpShare(reward.xp(), entry.getValue());
            if (xp > 0) {
                player.giveExp(xp);
                plugin.messages().send(player, "boss-reward-xp", "xp", String.valueOf(xp));
            }
            for (BossReward.Item item : reward.items()) {
                giveItem(player, new ItemStack(item.material(), item.amount()));
            }
        }

        UUID top = boss.topDamager();
        if (top != null && reward.hasTrophy()) {
            Player owner = Bukkit.getPlayer(top);
            if (owner != null && !owner.getGameMode().equals(GameMode.SPECTATOR)) {
                ItemStack trophy = trophy(definition, reward);
                giveItem(owner, trophy);
                plugin.messages().send(owner, "boss-reward-trophy",
                        "name", trophyName(definition, reward));
            }
        }

        String killerName = killer == null ? "—" : killer.getName();
        Bukkit.getServer().broadcast(plugin.messages().get("boss-killed",
                "name", definition.name(),
                "killer", killerName,
                "participants", String.valueOf(shares.size())));
        store.recordKill(definition, killerName, shares.size(), now);
        store.setNextAt(definition.id(), now + definition.spawnIntervalSeconds() * 1_000L);
        close(boss, false);
        plugin.requestSave();
    }

    private String trophyName(BossDefinition definition, BossReward reward) {
        return reward.trophyName().isEmpty() ? definition.name() : reward.trophyName();
    }

    private ItemStack trophy(BossDefinition definition, BossReward reward) {
        ItemStack stack = new ItemStack(reward.trophy());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.messages().get("boss-trophy-name",
                    "name", trophyName(definition, reward)));
            meta.lore(plugin.messages().getList("boss-trophy-lore", "name", definition.name()));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void giveItem(Player player, ItemStack stack) {
        for (ItemStack rest : player.getInventory().addItem(stack).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
            plugin.messages().send(player, "boss-reward-dropped", "item",
                    rest.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
    }

    /** Убирает бой с объявлением причины. */
    public void despawn(ActiveBoss boss, String messageKey) {
        if (boss == null) {
            return;
        }
        if (messageKey != null) {
            Bukkit.getServer().broadcast(plugin.messages().get(messageKey,
                    "name", boss.definition().name()));
        }
        store.setNextAt(boss.id(), System.currentTimeMillis()
                + boss.definition().spawnIntervalSeconds() * 1_000L);
        close(boss, true);
        plugin.requestSave();
    }

    /** Убирает всех боссов без объявлений — выключение сервера. */
    public void despawnAll() {
        for (ActiveBoss boss : List.copyOf(byId.values())) {
            close(boss, true);
        }
    }

    private void close(ActiveBoss boss, boolean removeEntity) {
        byEntity.remove(boss.entityId());
        byId.remove(boss.id());
        for (UUID viewer : boss.viewers()) {
            Player player = Bukkit.getPlayer(viewer);
            if (player != null) {
                player.hideBossBar(boss.bar());
            }
        }
        for (UUID minionId : boss.minions()) {
            Entity minion = Bukkit.getEntity(minionId);
            if (minion != null && minion.isValid()) {
                minion.remove();
            }
        }
        if (removeEntity && boss.entity().isValid()) {
            boss.entity().remove();
        }
    }

    /** Забывает бой без удаления сущности (сущность уже исчезла). */
    private void forget(ActiveBoss boss) {
        byEntity.remove(boss.entityId());
        byId.remove(boss.id());
        for (UUID viewer : boss.viewers()) {
            Player player = Bukkit.getPlayer(viewer);
            if (player != null) {
                player.hideBossBar(boss.bar());
            }
        }
    }

    /* ---------------------------------- урон ---------------------------------- */

    /**
     * Записывает урон игрока по боссу.
     *
     * @param damage сколько урона пройдёт после брони
     * @return новая фаза, если она началась, иначе {@code null}
     */
    public BossPhase damage(ActiveBoss boss, Player attacker, double damage) {
        if (boss == null || attacker == null || damage <= 0) {
            return null;
        }
        double healthAfter = Math.max(0.0, boss.entity().getHealth() - damage);
        BossPhase phase = boss.recordDamage(attacker.getUniqueId(), damage, healthAfter);
        if (phase != null) {
            applyPhase(boss, phase);
        }
        return phase;
    }

    private void applyPhase(ActiveBoss boss, BossPhase phase) {
        LivingEntity entity = boss.entity();
        entity.customName(nameComponent(boss.definition(), phase.name()));
        AttributeInstance speed = entity.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(boss.baseSpeed() * phase.speedMultiplier());
        }
        if (phase.speedMultiplier() > 1.0) {
            PotionEffectType type = effect("speed");
            if (type != null) {
                entity.addPotionEffect(new PotionEffect(type, 20 * 60 * 10, 1, true, false));
            }
        }
        boss.resetCast();
        Bukkit.getServer().broadcast(plugin.messages().get("boss-phase",
                "name", boss.definition().name(),
                "phase", phase.name()));
    }

    private Component nameComponent(BossDefinition definition, String phase) {
        return plugin.messages().get("boss-name",
                "name", definition.name(),
                "phase", phase);
    }

    /* ---------------------------------- тик ----------------------------------- */

    /** Один такт сервиса: жизнь боссов, полосы, способности и зоны. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (ActiveBoss boss : List.copyOf(byId.values())) {
            if (!boss.entity().isValid() || boss.entity().isDead()) {
                forget(boss);
                continue;
            }
            if (now >= boss.endsAt()) {
                despawn(boss, "boss-timeout");
                continue;
            }
            keepOnArena(boss);
            updateViewers(boss);
            cast(boss, now);
        }
        updateBars(now);
        tickZones(now);
    }

    /** Босс не уходит с арены: игрок не должен уводить его к чужой базе. */
    private void keepOnArena(ActiveBoss boss) {
        Arena arena = boss.definition().arena();
        LivingEntity entity = boss.entity();
        Location location = entity.getLocation();
        if (arena.contains(location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ())) {
            return;
        }
        World world = location.getWorld();
        entity.teleport(spawnPoint(world, arena));
    }

    private void updateViewers(ActiveBoss boss) {
        Arena arena = boss.definition().arena();
        Location location = boss.entity().getLocation();
        Set<UUID> inside = new HashSet<>();
        for (Player player : location.getWorld().getPlayers()) {
            Location where = player.getLocation();
            if (arena.contains(location.getWorld().getName(),
                    where.getX(), where.getY(), where.getZ())) {
                inside.add(player.getUniqueId());
            }
        }
        for (UUID viewer : boss.viewers()) {
            if (inside.contains(viewer)) {
                continue;
            }
            Player player = Bukkit.getPlayer(viewer);
            if (player != null) {
                player.hideBossBar(boss.bar());
            }
            boss.removeViewer(viewer);
        }
        for (UUID id : inside) {
            if (boss.viewing(id)) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.showBossBar(boss.bar());
                boss.addViewer(id);
            }
        }
    }

    private void updateBars(long now) {
        if (!config.bossbar() || now - lastBarUpdate < 1_000L) {
            return;
        }
        lastBarUpdate = now;
        for (ActiveBoss boss : byId.values()) {
            BossPhase phase = boss.phase();
            boss.bar().progress(boss.progress());
            boss.bar().name(plugin.messages().get("boss-bar",
                    "name", boss.definition().name(),
                    "phase", phase.name(),
                    "health", String.valueOf(Math.max(0,
                            (int) Math.round(boss.progress() * 100)))));
        }
    }

    private void cast(ActiveBoss boss, long now) {
        BossPhase phase = boss.phase();
        if (!phase.hasAbilities()) {
            return;
        }
        Location center = boss.entity().getLocation();
        double arenaRadius = boss.definition().arena().radius();
        for (BossAbility ability : phase.abilities()) {
            if (!boss.due(ability, now)) {
                continue;
            }
            double radius = Math.min(ability.radius(), arenaRadius);
            List<Player> targets = playersNear(center, radius);
            if (targets.isEmpty()) {
                continue;
            }
            use(boss, ability, targets);
            return;
        }
    }

    private List<Player> playersNear(Location center, double radius) {
        List<Player> out = new ArrayList<>();
        double squared = radius * radius;
        for (Player player : center.getWorld().getPlayers()) {
            if (player.getGameMode() == GameMode.CREATIVE
                    || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            Location location = player.getLocation();
            if (location.distanceSquared(center) <= squared) {
                out.add(player);
            }
        }
        return out;
    }

    private void use(ActiveBoss boss, BossAbility ability, List<Player> targets) {
        Location center = boss.entity().getLocation();
        switch (ability.type()) {
            case STOMP -> {
                for (Player player : targets) {
                    hurt(player, ability.amount());
                    Vector push = player.getLocation().toVector().subtract(center.toVector());
                    if (push.lengthSquared() > 0.01) {
                        player.setVelocity(push.normalize().multiply(0.6).setY(0.35));
                    }
                }
                center.getWorld().playSound(center, "entity.generic.explode", 1.0f, 0.6f);
            }
            case SUMMON -> summon(boss, ability);
            case FIRE_NOVA -> {
                for (Player player : targets) {
                    player.setFireTicks(Math.max(player.getFireTicks(),
                            ability.durationSeconds() * 20));
                }
            }
            case PULL -> {
                for (Player player : targets) {
                    Vector pull = center.toVector().subtract(player.getLocation().toVector());
                    if (pull.lengthSquared() > 1.0) {
                        player.setVelocity(pull.normalize().multiply(1.1).setY(0.25));
                    }
                }
            }
            case LEAP -> {
                Player target = targets.get((int) (Math.random() * targets.size()));
                boss.entity().teleport(target.getLocation().add(1.5, 0, 1.5));
            }
            case CURSE -> {
                PotionEffectType type = effect(ability.effect());
                if (type == null) {
                    return;
                }
                for (Player player : targets) {
                    player.addPotionEffect(new PotionEffect(type,
                            ability.durationSeconds() * 20, ability.amplifier(), true, true));
                }
            }
            case HEAL -> {
                AttributeInstance maxHealth = boss.entity().getAttribute(Attribute.MAX_HEALTH);
                double max = maxHealth == null ? boss.definition().health() : maxHealth.getValue();
                double health = Math.min(max, boss.entity().getHealth()
                        + max * ability.healPercent() / 100.0);
                boss.entity().setHealth(health);
            }
            case ENRAGE -> {
                PotionEffectType type = effect("speed");
                if (type != null) {
                    boss.entity().addPotionEffect(new PotionEffect(type,
                            Math.max(20, ability.durationSeconds() * 20),
                            ability.amplifier(), true, false));
                }
            }
        }
    }

    private void summon(ActiveBoss boss, BossAbility ability) {
        World world = boss.entity().getWorld();
        Location center = boss.entity().getLocation();
        for (int index = 0; index < ability.summons(); index++) {
            double angle = Math.random() * Math.PI * 2;
            double distance = 2.0 + Math.random() * Math.max(1.0, ability.radius() - 2.0);
            Location place = center.clone().add(Math.cos(angle) * distance, 0,
                    Math.sin(angle) * distance);
            Entity minion = world.spawnEntity(place, ability.entity());
            minion.addScoreboardTag(MINION_TAG);
            boss.addMinion(minion.getUniqueId());
        }
    }

    private void hurt(Player player, double amount) {
        if (amount <= 0 || player.isDead()) {
            return;
        }
        player.damage(amount);
    }

    private static PotionEffectType effect(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return Registry.EFFECT.get(NamespacedKey.minecraft(id.trim().toLowerCase(Locale.ROOT)));
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /* ---------------------------------- зоны ---------------------------------- */

    private void tickZones(long now) {
        List<DangerZone> zones = config.activeZones();
        if (zones.isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.CREATIVE
                    || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            Location location = player.getLocation();
            DangerZone zone = BossMath.zoneAt(zones, location.getWorld().getName(),
                    location.getX(), location.getY(), location.getZ());
            DangerZone previous = playerZone.put(player.getUniqueId(), zone);
            if (zone == null) {
                if (previous != null) {
                    plugin.messages().send(player, "zone-left", "zone", previous.name());
                }
                continue;
            }
            if (previous == null || !previous.id().equals(zone.id())) {
                plugin.messages().send(player, "zone-enter",
                        "zone", zone.name(),
                        "level", zone.levelLabel());
            }
            long nextEffect = nextZoneEffect.getOrDefault(player.getUniqueId(), 0L);
            if (now >= nextEffect) {
                nextZoneEffect.put(player.getUniqueId(), now
                        + zone.effectIntervalSeconds() * 1_000L);
                applyZoneEffects(player, zone);
            }
            double damage = BossMath.zoneDamage(zone.damagePerSecond(),
                    player.getHealth());
            if (damage > 0) {
                player.damage(damage);
            }
        }
    }

    private void applyZoneEffects(Player player, DangerZone zone) {
        for (ZoneEffect effect : zone.effects()) {
            PotionEffectType type = effect(effect.effect());
            if (type == null) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(type, effect.durationSeconds() * 20,
                    effect.amplifier(), true, false));
        }
        if (config.actionbar()) {
            player.sendActionBar(plugin.messages().get("zone-actionbar",
                    "zone", zone.name(),
                    "level", zone.levelLabel()));
        }
    }

    /** Надбавка к опыту за моба, убитого в зоне; 0 — если зоны нет. */
    public int bonusXp(Player player, int baseXp) {
        DangerZone zone = zoneAt(player);
        if (zone == null) {
            return 0;
        }
        return BossMath.bonusXp(baseXp, zone.xpBonusPercent());
    }

    /** Показывает надбавку, но не чаще раза в пять секунд. */
    public void showBonus(Player player, int bonus) {
        if (bonus <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long next = nextZoneBonus.getOrDefault(player.getUniqueId(), 0L);
        if (now < next) {
            return;
        }
        nextZoneBonus.put(player.getUniqueId(), now + 5_000L);
        if (config.actionbar()) {
            player.sendActionBar(plugin.messages().get("zone-bonus",
                    "xp", String.valueOf(bonus)));
        }
    }

    /** Забывает игрока: вышел или сменил мир. */
    public void forget(UUID playerId) {
        if (playerId == null) {
            return;
        }
        playerZone.remove(playerId);
        nextZoneEffect.remove(playerId);
        nextZoneBonus.remove(playerId);
    }

    /* -------------------------------- автозапуск ------------------------------ */

    /**
     * Проверяет, не пора ли выпустить босса самому.
     *
     * <p>Авто-спавн выключен по умолчанию: координаты арен зависят от карты,
     * и ставить босса в чужую базу нельзя. Админ включает его, когда проверил
     * точки.</p>
     */
    public void autoSpawn() {
        if (!config.enabled() || !config.autoSpawn()) {
            return;
        }
        long now = System.currentTimeMillis();
        int online = Bukkit.getOnlinePlayers().size();
        for (BossDefinition definition : config.bossList()) {
            if (!definition.auto() || byId.containsKey(definition.id())) {
                continue;
            }
            if (online < definition.minPlayers()) {
                continue;
            }
            if (store.nextAt(definition.id()) > now) {
                continue;
            }
            if (store.nextAt(definition.id()) == 0L
                    && now - plugin.startedAt() < STARTUP_GRACE_MILLIS) {
                continue;
            }
            spawn(definition.id(), false);
        }
    }

    /* -------------------------------- помощники ------------------------------- */

    /** Сколько осталось до следующего появления (0 — можно сразу). */
    public long secondsToSpawn(String bossId, long now) {
        long next = store.nextAt(bossId);
        return next <= now ? 0L : (next - now + 999) / 1_000L;
    }

    /** Все иды боссов — для автодополнения. */
    public List<String> bossIds() {
        return config.bossIds();
    }

}
