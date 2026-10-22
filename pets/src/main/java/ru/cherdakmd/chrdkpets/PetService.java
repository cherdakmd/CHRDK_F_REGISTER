package ru.cherdakmd.chrdkpets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Приём питомцев, опыт, уровни, клички и боевые бонусы.
 *
 * <p>Сервис работает только с уже приручёнными кошками и волками. Повторный
 * приём идемпотентен: если запись по UUID сущности уже есть, прогресс не
 * сбрасывается, а только применяются бонусы и имя. Это важно для загрузки
 * чанков и перезапусков.</p>
 */
public final class PetService {

    private final PetsPlugin plugin;
    private final PetStore store;
    private final NamespacedKey petKey;
    private final NamespacedKey speciesKey;
    private final NamespacedKey baseHealthKey;
    private final NamespacedKey damageKey;
    private final NamespacedKey speedKey;
    private final Map<UUID, XpBudget> budgets = new HashMap<>();

    PetService(PetsPlugin plugin) {
        this.plugin = plugin;
        this.store = plugin.store();
        this.petKey = new NamespacedKey(plugin, "pet");
        this.speciesKey = new NamespacedKey(plugin, "species");
        this.baseHealthKey = new NamespacedKey(plugin, "base_health");
        this.damageKey = new NamespacedKey(plugin, "level_damage");
        this.speedKey = new NamespacedKey(plugin, "level_speed");
    }

    /**
     * Подбирает сущность, если она приручена и её вид включён.
     *
     * @return {@code true}, если питомец появился в базе впервые
     */
    public boolean adopt(Entity entity) {
        if (!plugin.config().enabled() || !(entity instanceof Tameable tameable) || !tameable.isTamed()) {
            return false;
        }
        PetSpecies species = PetSpecies.of(entity);
        if (species == null || !plugin.config().species(species).enabled()) {
            return false;
        }
        UUID ownerId = tameable.getOwnerUniqueId();
        if (ownerId == null) {
            return false;
        }
        PetStore.PetData data = store.get(entity.getUniqueId());
        boolean created = false;
        if (data == null) {
            data = store.create(entity.getUniqueId(), ownerId, ownerName(ownerId), species,
                    legacyName(entity), System.currentTimeMillis());
            if (data == null) {
                return false;
            }
            created = true;
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner != null) {
                plugin.messages().send(owner, "pet-adopted",
                        "name", displayName(data), "level", String.valueOf(level(data)));
            }
        } else if (!ownerId.equals(data.ownerId())) {
            store.setOwner(data.id(), ownerId, ownerName(ownerId));
        }
        mark(entity, species);
        applyStats(entity, data);
        applyDisplay(entity, data);
        return created;
    }

    /** Подбирает всех уже загруженных питомцев; возвращает число новых записей. */
    public int adoptLoaded() {
        if (!plugin.config().enabled()) {
            return 0;
        }
        int adopted = 0;
        for (var world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                if (adopt(entity)) {
                    adopted++;
                }
            }
        }
        return adopted;
    }

    /** Помечает питомца погибшим, снимает ауру и сообщает хозяину. */
    public void onDeath(Entity entity) {
        PetStore.PetData data = store.get(entity.getUniqueId());
        if (data == null || data.dead()) {
            return;
        }
        store.markDead(data.id(), System.currentTimeMillis());
        Player owner = Bukkit.getPlayer(data.ownerId());
        if (owner != null) {
            plugin.messages().send(owner, "pet-died",
                    "name", displayName(data), "level", String.valueOf(level(data)));
            plugin.aura().clear(owner);
        }
    }

    /**
     * Начисляет опыт с минутным потолком. При повышении уровня сразу
     * пересчитывает бонусы и обновляет имя над головой.
     */
    public void addXp(LivingEntity pet, int amount) {
        if (pet == null || amount <= 0) {
            return;
        }
        PetStore.PetData data = store.get(pet.getUniqueId());
        if (data == null || data.dead()) {
            return;
        }
        int granted = budget(data.id(), amount);
        if (granted <= 0) {
            return;
        }
        int before = level(data);
        store.addXp(data.id(), granted);
        int after = level(data);
        if (after > before) {
            applyStats(pet, data);
            applyDisplay(pet, data);
            Player owner = Bukkit.getPlayer(data.ownerId());
            if (owner != null) {
                plugin.messages().send(owner, "level-up",
                        "name", displayName(data), "level", String.valueOf(after));
                owner.playSound(owner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
            }
        }
    }

    /**
     * Опыт за мирное событие: кошке достаётся множитель из
     * {@code xp.cat-multiplier}. Волк получает базовое число.
     */
    public void addPeacefulXp(LivingEntity pet, int amount) {
        if (pet == null || amount <= 0) {
            return;
        }
        PetStore.PetData data = store.get(pet.getUniqueId());
        if (data == null) {
            return;
        }
        double multiplier = data.species() == PetSpecies.CAT
                ? plugin.config().xp().catMultiplier() : 1.0;
        addXp(pet, (int) Math.max(1L, Math.round(amount * multiplier)));
    }

    private int budget(UUID petId, int requested) {
        int max = plugin.config().xp().maxPerMinute();
        if (max <= 0) {
            return requested;
        }
        long minute = System.currentTimeMillis() / 60_000L;
        XpBudget budget = budgets.computeIfAbsent(petId, key -> new XpBudget());
        if (budget.minute != minute) {
            budget.minute = minute;
            budget.granted = 0;
        }
        int allowed = Math.max(0, Math.min(requested, max - budget.granted));
        budget.granted += allowed;
        return allowed;
    }

    /** Переименование ближайшего питомца; сообщения отправляет сервис. */
    public void rename(Player player, LivingEntity pet, String rawName) {
        PetStore.PetData data = store.get(pet.getUniqueId());
        if (data == null) {
            plugin.messages().send(player, "no-pet");
            return;
        }
        String clean = PetNames.sanitize(rawName, plugin.config().maxNameLength());
        if (!PetNames.valid(clean, plugin.config().minNameLength(), plugin.config().maxNameLength())) {
            plugin.messages().send(player, "name-length",
                    "min", String.valueOf(plugin.config().minNameLength()),
                    "max", String.valueOf(plugin.config().maxNameLength()));
            return;
        }
        if (clean.equals(data.name())) {
            plugin.messages().send(player, "name-same");
            return;
        }
        store.rename(data.id(), clean);
        applyDisplay(pet, data);
        plugin.messages().send(player, "name-changed", "name", clean);
    }

    /** Имя для показа: кличка или видовое название. */
    public String displayName(PetStore.PetData data) {
        if (data == null) {
            return "";
        }
        if (!data.name().isBlank()) {
            return data.name();
        }
        return plugin.messages().plain(data.species() == PetSpecies.WOLF
                ? "default-wolf-name" : "default-cat-name");
    }

    /** Ближайший живой питомец игрока в радиусе; {@code null} — нет. */
    public LivingEntity nearestOwned(Player player, double radius) {
        if (player == null || !player.isOnline()) {
            return null;
        }
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof Tameable tameable) || !tameable.isTamed()) {
                continue;
            }
            if (!player.getUniqueId().equals(tameable.getOwnerUniqueId())) {
                continue;
            }
            PetStore.PetData data = store.get(entity.getUniqueId());
            if (data == null || data.dead()) {
                continue;
            }
            double distance = entity.getLocation().distanceSquared(player.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = (LivingEntity) entity;
            }
        }
        return best;
    }

    /** Запись питомца по сущности; {@code null} — это не наш питомец. */
    public PetStore.PetData data(Entity entity) {
        return entity == null ? null : store.get(entity.getUniqueId());
    }

    /** Уровень по записи; 1 — минимальный. */
    public int level(PetStore.PetData data) {
        return data == null ? 1 : PetCurve.levelFor(data.xp(), plugin.config().curve());
    }

    /** Админская установка уровня (с пересчётом бонусов и имени). */
    public void setLevel(PetStore.PetData data, int level) {
        if (data == null) {
            return;
        }
        store.setXp(data.id(), PetCurve.totalForLevel(level, plugin.config().curve()));
        Entity entity = Bukkit.getServer().getEntity(data.id());
        if (entity instanceof LivingEntity living) {
            applyStats(living, data);
            applyDisplay(living, data);
        }
    }

    /* --------------------------------------------------------------- служебное */

    /**
     * Применяет бонусы уровня.
     *
     * <p>Здоровье меняется <b>базовым значением</b> атрибута, а не
     * модификатором: базовый запас сохраняется в NBT, поэтому после
     * перезагрузки чанка питомец не выглядит «побитым» (20 из 38). Ванильная
     * база запоминается в PDC при первом расчёте и не теряется при смене
     * уровня. Урон и скорость — транзитные модификаторы.</p>
     */
    private void applyStats(Entity entity, PetStore.PetData data) {
        if (!(entity instanceof LivingEntity pet)) {
            return;
        }
        PetBuffs.Stats stats = PetBuffs.stats(
                plugin.config().species(data.species()).buffs(), level(data));
        AttributeInstance health = pet.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            double base = baseHealth(pet, health);
            double oldMax = health.getValue();
            boolean full = pet.getHealth() >= oldMax - 0.01;
            health.setBaseValue(Math.max(1.0, base + stats.health()));
            double newMax = health.getValue();
            if (full) {
                pet.setHealth(newMax);
            } else if (newMax > oldMax) {
                pet.setHealth(Math.min(newMax, pet.getHealth() + (newMax - oldMax)));
            } else if (pet.getHealth() > newMax) {
                pet.setHealth(newMax);
            }
        }
        if (data.species().canAttack()) {
            PetAttributes.apply(pet, Attribute.ATTACK_DAMAGE, damageKey, stats.damage(),
                    AttributeModifier.Operation.ADD_NUMBER);
        } else {
            PetAttributes.remove(pet, Attribute.ATTACK_DAMAGE, damageKey);
        }
        PetAttributes.apply(pet, Attribute.MOVEMENT_SPEED, speedKey, stats.speedScalar(),
                AttributeModifier.Operation.MULTIPLY_SCALAR_1);
    }

    /**
     * Ванильный запас здоровья из PDC. При первом расчёте туда попадает
     * текущая база атрибута — до всех прибавок модуля.
     */
    private double baseHealth(LivingEntity pet, AttributeInstance health) {
        PersistentDataContainer container = pet.getPersistentDataContainer();
        Double stored = container.get(baseHealthKey, PersistentDataType.DOUBLE);
        if (stored != null) {
            return stored;
        }
        double base = Math.max(1.0, health.getBaseValue());
        container.set(baseHealthKey, PersistentDataType.DOUBLE, base);
        return base;
    }

    /**
     * Возвращает питомцам ванильный запас здоровья. Вызывается при выключении
     * модуля: снятый аддон не должен оставлять за спиной «зашитые» сердца.
     *
     * @return сколько сущностей затронуто
     */
    public int releaseBonuses() {
        int released = 0;
        for (var world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                PersistentDataContainer container = entity.getPersistentDataContainer();
                Double base = container.get(baseHealthKey, PersistentDataType.DOUBLE);
                if (base == null) {
                    continue;
                }
                AttributeInstance health = entity.getAttribute(Attribute.MAX_HEALTH);
                if (health != null) {
                    health.setBaseValue(base);
                    if (entity.getHealth() > health.getValue()) {
                        entity.setHealth(health.getValue());
                    }
                }
                container.remove(baseHealthKey);
                PetAttributes.remove(entity, Attribute.ATTACK_DAMAGE, damageKey);
                PetAttributes.remove(entity, Attribute.MOVEMENT_SPEED, speedKey);
                released++;
            }
        }
        return released;
    }

    /** Имя над головой: кличка + уровень (или без уровня по конфигу). */
    private void applyDisplay(Entity entity, PetStore.PetData data) {
        if (!(entity instanceof LivingEntity pet)) {
            return;
        }
        if (!plugin.config().showLevel() && data.name().isBlank()) {
            pet.customName(null);
            return;
        }
        Component name = plugin.config().showLevel()
                ? plugin.messages().get("name-format",
                        "name", displayName(data), "level", String.valueOf(level(data)))
                : plugin.messages().get("name-format-plain", "name", displayName(data));
        pet.customName(name);
    }

    /** Имя, которое игрок дал питомцу до установки модуля (если оно нормальное). */
    private String legacyName(Entity entity) {
        Component custom = entity.customName();
        if (custom == null) {
            return "";
        }
        String raw = PlainTextComponentSerializer.plainText().serialize(custom);
        int marker = raw.indexOf(" · ");
        if (marker > 0) {
            raw = raw.substring(0, marker);
        }
        String clean = PetNames.sanitize(raw, plugin.config().maxNameLength());
        return PetNames.valid(clean, plugin.config().minNameLength(), plugin.config().maxNameLength())
                ? clean : "";
    }

    private void mark(Entity entity, PetSpecies species) {
        PersistentDataContainer container = entity.getPersistentDataContainer();
        container.set(petKey, PersistentDataType.BYTE, (byte) 1);
        container.set(speciesKey, PersistentDataType.STRING, species.id());
    }

    private String ownerName(UUID ownerId) {
        Player owner = Bukkit.getPlayer(ownerId);
        return owner == null ? "" : owner.getName();
    }

    /** Счётчик опыта за текущую минуту — защита от ферм. */
    private static final class XpBudget {
        private long minute;
        private int granted;
    }
}
