package ru.cherdakmd.chrdkpets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
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
 * Приём питомцев, опыт, уровни, бонусы и клички.
 *
 * <p>Модуль не спавнит сущностей: он «подбирает» уже приручённых ванильных
 * кошек и волков. Приём идёт из трёх мест — {@code EntityTameEvent} (только что
 * приручили), загрузка чанка и старт сервера (питомцы, приручённые до установки
 * модуля). Повторный приём идемпотентен: запись ищется по UUID сущности.</p>
 *
 * <p>Здоровье меняется <b>базовым значением</b> атрибута, а не модификатором:
 * база сохраняется в NBT сущности, поэтому после перезагрузки чанка питомец не
 * выглядит «побитым» (20/38). Урон и скорость — транзитные модификаторы, их
 * модуль переставляет сам при каждой загрузке.</p>
 */
public final class PetService {

    /** Сколько опыта питомец может получить за одну минуту (защита от ферм). */
    private static final class Budget {
        private long minute;
        private int granted;
    }

    private final PetsPlugin plugin;
    private final PetStore store;
    private final NamespacedKey petKey;
    private final Map<UUID, Budget> budgets = new HashMap<>();

    PetService(PetsPlugin plugin) {
        this.plugin = plugin;
        this.store = plugin.store();
        this.petKey = new NamespacedKey(plugin, "pet");
    }

    /* ------------------------------------------------------------------ приём */

    /**
     * Принимает сущность в питомцы, если она приручена, принадлежит игроку и её
     * вид включён. Возвращает {@code true}, только если запись создана впервые.
     */
    public boolean adopt(Entity entity) {
        if (entity == null || !plugin.config().enabled()) {
            return false;
        }
        if (!(entity instanceof Tameable tameable) || !tameable.isTamed()) {
            return false;
        }
        if (!(entity instanceof LivingEntity living)) {
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
        PetStore.PetData existing = store.get(entity.getUniqueId());
        if (existing != null) {
            if (!ownerId.equals(existing.ownerId())) {
                store.setOwner(existing.id(), ownerId, ownerName(ownerId));
            }
            refresh(living);
            return false;
        }
        String name = "";
        Component custom = entity.customName();
        if (custom != null) {
            name = PetNames.sanitize(PlainTextComponentSerializer.plainText().serialize(custom),
                    plugin.config().maxNameLength());
        }
        PetStore.PetData data = store.create(entity.getUniqueId(), ownerId,
                ownerName(ownerId), species, name, System.currentTimeMillis());
        if (data == null) {
            return false;
        }
        mark(entity);
        applyStats(living, data, true);
        applyDisplay(living, data);
        return true;
    }

    /** Приём всех уже загруженных питомцев (старт сервера, загрузка чанка). */
    public void adoptLoaded() {
        if (!plugin.config().enabled()) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                try {
                    adopt(entity);
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Не удалось принять питомца "
                            + entity.getType() + ": " + exception.getMessage());
                }
            }
        }
    }

    /** Переставляет бонусы и подпись по текущим данным; здоровье не лечит. */
    public void refresh(Entity entity) {
        PetStore.PetData data = store.get(entity.getUniqueId());
        if (data == null || data.dead() || !(entity instanceof LivingEntity living)) {
            return;
        }
        if (!plugin.config().species(data.species()).enabled()) {
            clear(living, data);
            return;
        }
        applyStats(living, data, false);
        applyDisplay(living, data);
    }

    /* ------------------------------------------------------------------- опыт */

    /**
     * Начисляет опыт питомцу. Кошки получают видовой множитель: им положено
     * меньше боя и больше «уютных» событий — рыбалки и утреннего сна.
     */
    public void addXp(LivingEntity pet, int baseAmount) {
        if (pet == null || baseAmount <= 0 || !plugin.config().enabled()) {
            return;
        }
        PetStore.PetData data = store.get(pet.getUniqueId());
        if (data == null || data.dead()) {
            return;
        }
        if (!plugin.config().species(data.species()).enabled()) {
            return;
        }
        int amount = data.species() == PetSpecies.CAT
                ? (int) Math.round(baseAmount * plugin.config().xp().catMultiplier())
                : baseAmount;
        amount = budget(data.id(), amount);
        if (amount <= 0) {
            return;
        }
        int oldLevel = level(data);
        store.addXp(data.id(), amount);
        int newLevel = level(data);
        if (newLevel > oldLevel) {
            applyStats(pet, data, true);
            applyDisplay(pet, data);
            announceLevelUp(data, newLevel);
        }
    }

    /** Минутный лимит опыта; {@code 0} в конфиге — без лимита. */
    private int budget(UUID petId, int amount) {
        int max = plugin.config().xp().maxPerMinute();
        if (max <= 0) {
            return amount;
        }
        long minute = System.currentTimeMillis() / 60_000L;
        Budget entry = budgets.computeIfAbsent(petId, id -> new Budget());
        if (entry.minute != minute) {
            entry.minute = minute;
            entry.granted = 0;
        }
        int allowed = Math.min(amount, Math.max(0, max - entry.granted));
        entry.granted += allowed;
        return Math.max(0, allowed);
    }

    private void announceLevelUp(PetStore.PetData data, int level) {
        Player owner = Bukkit.getPlayer(data.ownerId());
        if (owner == null) {
            return;
        }
        plugin.messages().send(owner, "level-up",
                "name", displayName(data),
                "level", String.valueOf(level));
        Sound sound = resolveSound(data.species() == PetSpecies.WOLF
                ? "entity.wolf.ambient" : "entity.cat.purr");
        if (sound != null) {
            owner.playSound(owner.getLocation(), sound, 0.8f, 1.2f);
        }
    }

    /* ---------------------------------------------------------------- смерть */

    /** Помечает питомца погибшим и сообщает хозяину, если он онлайн. */
    public void onDeath(Entity entity) {
        PetStore.PetData data = store.get(entity.getUniqueId());
        if (data == null || data.dead()) {
            return;
        }
        int level = level(data);
        store.markDead(data.id(), System.currentTimeMillis());
        budgets.remove(data.id());
        Player owner = Bukkit.getPlayer(data.ownerId());
        if (owner != null) {
            plugin.messages().send(owner, "pet-died",
                    "name", displayName(data),
                    "level", String.valueOf(level));
            plugin.aura().clear(owner);
        }
    }

    /* ---------------------------------------------------------------- клички */

    /** Сохраняет уже проверенную кличку и обновляет подпись над головой. */
    public void rename(LivingEntity pet, String cleanName) {
        PetStore.PetData data = store.get(pet.getUniqueId());
        if (data == null) {
            return;
        }
        store.rename(data.id(), cleanName);
        applyDisplay(pet, data);
    }

    /** Имя для показа: кличка или видовое название по умолчанию. */
    public String displayName(PetStore.PetData data) {
        if (data == null) {
            return "";
        }
        if (!data.name().isBlank()) {
            return data.name();
        }
        String key = data.species() == PetSpecies.WOLF ? "default-wolf-name" : "default-cat-name";
        String fallback = plugin.messages().plain(key);
        return fallback.isBlank() ? data.species().id() : fallback;
    }

    /* ----------------------------------------------------------- поиск и уровень */

    /** Ближайший живой питомец игрока в радиусе; {@code null} — нет. */
    public LivingEntity nearestOwned(Player player, double radius) {
        if (player == null || radius <= 0) {
            return null;
        }
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(),
                radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || living.isDead()) {
                continue;
            }
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
                best = living;
            }
        }
        return best;
    }

    public PetStore.PetData data(Entity entity) {
        return entity == null ? null : store.get(entity.getUniqueId());
    }

    public boolean isPet(Entity entity) {
        return entity != null && (store.has(entity.getUniqueId()) || marked(entity));
    }

    public int level(PetStore.PetData data) {
        return data == null ? 1 : PetCurve.levelFor(data.xp(), plugin.config().curve());
    }

    /** Админская установка уровня: опыт пересчитывается по кривой. */
    public void setLevel(PetStore.PetData data, int level) {
        if (data == null) {
            return;
        }
        int target = Math.max(1, Math.min(plugin.config().curve().maxLevel(), level));
        store.setXp(data.id(), PetCurve.totalForLevel(target, plugin.config().curve()));
        Entity entity = Bukkit.getServer().getEntity(data.id());
        if (entity instanceof LivingEntity living) {
            applyStats(living, data, true);
            applyDisplay(living, data);
        }
    }

    /* ------------------------------------------------------------- внутреннее */

    private void applyStats(LivingEntity pet, PetStore.PetData data, boolean restoreHealth) {
        PetsConfig.Species species = plugin.config().species(data.species());
        PetBuffs.Stats stats = PetBuffs.stats(species.buffs(), level(data));
        AttributeInstance health = pet.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(species.baseHealth() + stats.health());
        }
        if (data.species().canAttack()) {
            PetAttributes.set(pet, Attribute.ATTACK_DAMAGE, key("damage"),
                    stats.damage(), AttributeModifier.Operation.ADD_NUMBER);
        } else {
            PetAttributes.remove(pet, Attribute.ATTACK_DAMAGE, key("damage"));
        }
        PetAttributes.set(pet, Attribute.MOVEMENT_SPEED, key("speed"),
                stats.speedScalar(), AttributeModifier.Operation.MULTIPLY_SCALAR_1);

        double max = maxHealth(pet);
        if (max > 0 && pet.getHealth() > max) {
            pet.setHealth(max);
        }
        if (restoreHealth && max > 0) {
            pet.setHealth(max);
        }
    }

    private void clear(LivingEntity pet, PetStore.PetData data) {
        AttributeInstance health = pet.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(plugin.config().species(data.species()).baseHealth());
        }
        PetAttributes.remove(pet, Attribute.ATTACK_DAMAGE, key("damage"));
        PetAttributes.remove(pet, Attribute.MOVEMENT_SPEED, key("speed"));
    }

    private void applyDisplay(LivingEntity living, PetStore.PetData data) {
        boolean named = !data.name().isBlank();
        if (!named && !plugin.config().showLevel()) {
            living.customName(null);
            living.setCustomNameVisible(false);
            return;
        }
        Component name = plugin.config().showLevel()
                ? plugin.messages().get("name-format",
                        "name", displayName(data),
                        "level", String.valueOf(level(data)))
                : plugin.messages().get("name-format-plain", "name", displayName(data));
        living.customName(name);
        living.setCustomNameVisible(true);
    }

    private void mark(Entity entity) {
        PersistentDataContainer container = entity.getPersistentDataContainer();
        container.set(petKey, PersistentDataType.BYTE, (byte) 1);
    }

    private boolean marked(Entity entity) {
        return entity.getPersistentDataContainer().has(petKey, PersistentDataType.BYTE);
    }

    private NamespacedKey key(String name) {
        return new NamespacedKey(plugin, name);
    }

    private String ownerName(UUID ownerId) {
        Player online = Bukkit.getPlayer(ownerId);
        return online == null ? "" : online.getName();
    }

    private static double maxHealth(LivingEntity entity) {
        AttributeInstance attribute = entity.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? entity.getHealth() : attribute.getValue();
    }

    private static Sound resolveSound(String id) {
        try {
            return Registry.SOUNDS.get(NamespacedKey.minecraft(id));
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
