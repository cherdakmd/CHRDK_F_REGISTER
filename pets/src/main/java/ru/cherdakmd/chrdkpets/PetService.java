package ru.cherdakmd.chrdkpets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
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
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Приём питомцев, опыт, уровни, забота, трюки, команды заботы и косметика.
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
 *
 * <p>Забота (сытость, настроение) влияет только на скорость опыта и шанс
 * выучить трюк; боевые бонусы и аура от неё не зависят. Все действия с
 * кулдаунами хранят отметку в {@code pets.json}, поэтому перезаход их не
 * сбрасывает.</p>
 */
public final class PetService {

    /** Сколько опыта питомец может получить за одну минуту (защита от ферм). */
    private static final class Budget {
        private long minute;
        private int granted;
    }

    /** Еда, которую кошка считает любимой. */
    private static final Set<Material> FISH = EnumSet.of(
            Material.COD, Material.SALMON, Material.TROPICAL_FISH, Material.PUFFERFISH,
            Material.COOKED_COD, Material.COOKED_SALMON);

    /** Еда, которую волк считает любимой. */
    private static final Set<Material> MEAT = EnumSet.of(
            Material.BEEF, Material.COOKED_BEEF, Material.PORKCHOP, Material.COOKED_PORKCHOP,
            Material.CHICKEN, Material.COOKED_CHICKEN, Material.MUTTON, Material.COOKED_MUTTON,
            Material.RABBIT, Material.COOKED_RABBIT, Material.ROTTEN_FLESH);

    private final PetsPlugin plugin;
    private final PetStore store;
    private final NamespacedKey petKey;
    private final Map<UUID, Budget> budgets = new HashMap<>();
    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, Long> togetherSeen = new HashMap<>();

    PetService(PetsPlugin plugin) {
        this.plugin = plugin;
        this.store = plugin.store();
        this.petKey = new NamespacedKey(plugin, "pet");
    }

    private PetsConfig config() {
        return plugin.config();
    }

    /* ------------------------------------------------------------------ приём */

    /**
     * Принимает сущность в питомцы, если она приручена, принадлежит игроку и её
     * вид включён. Возвращает {@code true}, только если запись создана впервые.
     */
    public boolean adopt(Entity entity) {
        if (entity == null || !config().enabled()) {
            return false;
        }
        if (!(entity instanceof Tameable tameable) || !tameable.isTamed()) {
            return false;
        }
        if (!(entity instanceof LivingEntity living)) {
            return false;
        }
        PetSpecies species = PetSpecies.of(entity);
        if (species == null || !config().species(species).enabled()) {
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
        int limit = config().maxPets();
        if (limit > 0 && store.alivePetsOf(ownerId).size() >= limit) {
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner != null) {
                plugin.messages().send(owner, "limit-reached", "max", String.valueOf(limit));
            }
            return false;
        }
        String name = "";
        Component custom = entity.customName();
        if (custom != null) {
            name = PetNames.sanitize(PlainTextComponentSerializer.plainText().serialize(custom),
                    config().maxNameLength());
        }
        long now = System.currentTimeMillis();
        PetStore.PetData data = store.create(entity.getUniqueId(), ownerId,
                ownerName(ownerId), species, name, now);
        if (data == null) {
            return false;
        }
        store.setCare(data.id(), config().care().startSatiety(),
                config().care().moodNeutral(), now);
        // Ванильную посадку не ломаем: если питомец уже сидит, это режим stay.
        if (entity instanceof Sittable sittable && sittable.isSitting()) {
            store.setMode(data.id(), PetMode.STAY);
        }
        store.addDiary(data.id(), now, "adopted", displayName(data), config().diarySize());
        mark(entity);
        applyStats(living, data, true);
        applyDisplay(living, data);
        applyMode(living, data);
        applyCosmetics(living, data);
        return true;
    }

    /** Приём всех уже загруженных питомцев (старт сервера, загрузка чанка). */
    public void adoptLoaded() {
        if (!config().enabled()) {
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

    /** Переставляет бонусы, подпись, режим и косметику; здоровье не лечит. */
    public void refresh(Entity entity) {
        PetStore.PetData data = store.get(entity.getUniqueId());
        if (data == null || data.dead() || !(entity instanceof LivingEntity living)) {
            return;
        }
        if (!config().species(data.species()).enabled()) {
            clear(living, data);
            return;
        }
        applyStats(living, data, false);
        applyDisplay(living, data);
        applyMode(living, data);
        applyCosmetics(living, data);
    }

    /* ------------------------------------------------------------------- опыт */

    /**
     * Начисляет опыт питомцу с учётом заботы, близости, ночи и выходных.
     * Кошки получают видовой множитель: им положено меньше боя и больше
     * «уютных» событий — рыбалки и утреннего сна.
     */
    public void addXp(LivingEntity pet, int baseAmount) {
        if (pet == null || baseAmount <= 0 || !config().enabled()) {
            return;
        }
        PetStore.PetData data = store.get(pet.getUniqueId());
        if (data == null || data.dead()) {
            return;
        }
        if (!config().species(data.species()).enabled()) {
            return;
        }
        World world = pet.getWorld();
        if (world != null && config().disabledWorlds()
                .contains(world.getName().toLowerCase(java.util.Locale.ROOT))) {
            return;
        }
        PetCare.State care = care(data);
        boolean near = ownerNear(pet, data);
        boolean night = isNight(world);
        boolean weekend = config().bonuses().weekend(LocalDate.now().getDayOfWeek());
        double multiplier = PetXpRules.multiplier(data.species() == PetSpecies.WOLF, care,
                near, night, weekend, config().bonuses().nightWolfOnly(), config().bonuses().factors());
        double speciesFactor = data.species() == PetSpecies.CAT ? config().xp().catMultiplier() : 1.0;
        int amount = (int) Math.max(1L, Math.round(baseAmount * speciesFactor * multiplier));
        amount = budget(data.id(), amount);
        if (amount <= 0) {
            return;
        }
        int oldLevel = level(data);
        store.addXp(data.id(), amount);
        int newLevel = level(data);
        if (newLevel > oldLevel) {
            store.setBestLevel(data.id(), newLevel);
            applyStats(pet, data, true);
            applyDisplay(pet, data);
            announceLevelUp(data, pet, newLevel);
            store.addDiary(data.id(), System.currentTimeMillis(), "level",
                    String.valueOf(newLevel), config().diarySize());
            autoName(pet, data);
            checkAchievements(data);
        }
    }

    /** Минутный лимит опыта; {@code 0} в конфиге — без лимита. */
    private int budget(UUID petId, int amount) {
        int max = config().xp().maxPerMinute();
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

    private void announceLevelUp(PetStore.PetData data, LivingEntity pet, int level) {
        Player owner = Bukkit.getPlayer(data.ownerId());
        if (owner != null) {
            plugin.messages().send(owner, "level-up",
                    "name", displayName(data),
                    "level", String.valueOf(level));
        }
        Sound sound = resolveSound(data.species() == PetSpecies.WOLF
                ? "entity.wolf.ambient" : "entity.cat.purr");
        if (owner != null && sound != null) {
            owner.playSound(owner.getLocation(), sound, 0.8f, 1.2f);
        }
        PetEffects.sparks(pet.getWorld(), pet.getLocation());
    }

    /* ------------------------------------------------------------------ забота */

    /** Текущие шкалы с учётом прошедшего времени; {@code null} — забота выключена. */
    public PetCare.State care(PetStore.PetData data) {
        if (data == null || !config().careEnabled()) {
            return null;
        }
        long now = System.currentTimeMillis();
        PetCare.State current = new PetCare.State(data.satiety(), data.mood());
        PetCare.State next = PetCare.decayed(current, data.careAt() <= 0L ? now : data.careAt(),
                now, config().care());
        if (next.satiety() != current.satiety() || next.mood() != current.mood()) {
            store.setCare(data.id(), next.satiety(), next.mood(), now);
        } else if (data.careAt() == 0L) {
            store.setCare(data.id(), next.satiety(), next.mood(), now);
        }
        return next;
    }

    /** Кормление едой из руки: сытость, настроение и немного опыта. */
    public void feed(Player player, LivingEntity pet) {
        PetStore.PetData data = data(pet);
        if (data == null) {
            plugin.messages().send(player, "no-pet");
            return;
        }
        if (!config().careEnabled()) {
            plugin.messages().send(player, "care-disabled");
            return;
        }
        long now = System.currentTimeMillis();
        long left = config().care().feedCooldownSeconds() * 1000L
                - (now - data.lastFed());
        if (left > 0L) {
            plugin.messages().send(player, "feed-cooldown",
                    "seconds", String.valueOf((left + 999L) / 1000L));
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isFood(hand)) {
            plugin.messages().send(player, "feed-no-food");
            return;
        }
        boolean favorite = isFavorite(data.species(), hand.getType());
        String itemName = itemName(hand);
        PetCare.State state = care(data);
        PetCare.State next = PetCare.fed(state, config().care());
        store.setCare(data.id(), next.satiety(), next.mood(), now);
        store.markAction(data.id(), "fed", now);
        consume(player, hand);
        int xp = config().care().feedXp() * (favorite ? 2 : 1);
        addXp(pet, xp);
        store.addDiary(data.id(), now, "fed", itemName, config().diarySize());
        plugin.messages().send(player, "feed-ok",
                "name", displayName(data),
                "item", itemName,
                "xp", String.valueOf(xp),
                "satiety", String.valueOf(next.satiety()),
                "mood", String.valueOf(next.mood()));
        PetEffects.hearts(pet.getWorld(), pet.getLocation());
        PetEffects.sound(player, "entity.generic.eat", 0.7f, 1.1f);
    }

    /** Поглаживание: настроение и капля опыта, кулдаун из конфига. */
    public void pat(Player player, LivingEntity pet) {
        PetStore.PetData data = data(pet);
        if (data == null) {
            plugin.messages().send(player, "no-pet");
            return;
        }
        if (!config().careEnabled()) {
            plugin.messages().send(player, "care-disabled");
            return;
        }
        long now = System.currentTimeMillis();
        long left = config().care().patCooldownSeconds() * 1000L
                - (now - data.lastPetted());
        if (left > 0L) {
            plugin.messages().send(player, "pat-cooldown",
                    "seconds", String.valueOf((left + 999L) / 1000L));
            return;
        }
        PetCare.State state = care(data);
        PetCare.State next = PetCare.patted(state, config().care());
        store.setCare(data.id(), next.satiety(), next.mood(), now);
        store.markAction(data.id(), "petted", now);
        addXp(pet, config().care().patXp());
        plugin.messages().send(player, "pat-ok",
                "name", displayName(data),
                "mood", String.valueOf(next.mood()));
        PetEffects.hearts(pet.getWorld(), pet.getLocation());
        PetEffects.sound(player, data.species() == PetSpecies.WOLF
                ? "entity.wolf.ambient" : "entity.cat.purr", 0.6f, 1.4f);
    }

    /** Лечение за еду: половина здоровья не выше максимума, кулдаун из конфига. */
    public void heal(Player player, LivingEntity pet) {
        PetStore.PetData data = data(pet);
        if (data == null) {
            plugin.messages().send(player, "no-pet");
            return;
        }
        long now = System.currentTimeMillis();
        long left = config().care().healCooldownSeconds() * 1000L
                - (now - data.lastHealed());
        if (left > 0L) {
            plugin.messages().send(player, "heal-cooldown",
                    "seconds", String.valueOf((left + 999L) / 1000L));
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isFood(hand)) {
            plugin.messages().send(player, "feed-no-food");
            return;
        }
        consume(player, hand);
        store.markAction(data.id(), "healed", now);
        double max = maxHealth(pet);
        double before = pet.getHealth();
        pet.setHealth(Math.min(max, before + max * 0.5));
        PetCare.State state = care(data);
        if (state != null) {
            PetCare.State next = new PetCare.State(state.satiety(),
                    state.mood() + config().care().feedMood() / 2);
            store.setCare(data.id(), next.satiety(), next.mood(), now);
        }
        plugin.messages().send(player, "heal-ok",
                "name", displayName(data),
                "health", number(pet.getHealth()),
                "max", number(max));
        PetEffects.hearts(pet.getWorld(), pet.getLocation());
        PetEffects.sound(player, "entity.generic.eat", 0.7f, 1.0f);
    }

    /* ------------------------------------------------------------------ трюки */

    /** Обучение трюку за угощение; шанс зависит от настроения. */
    public void learnTrick(Player player, LivingEntity pet, String rawId) {
        PetStore.PetData data = data(pet);
        if (data == null) {
            plugin.messages().send(player, "no-pet");
            return;
        }
        PetTricks trick = PetTricks.byId(rawId);
        if (trick == null) {
            plugin.messages().send(player, "trick-unknown", "trick", String.valueOf(rawId));
            return;
        }
        if (data.hasTrick(trick.id())) {
            plugin.messages().send(player, "trick-known",
                    "trick", plugin.messages().plain("trick-" + trick.id()));
            return;
        }
        PetCare.State state = care(data);
        int mood = state == null ? 100 : state.mood();
        if (!trick.availableAt(mood)) {
            plugin.messages().send(player, "trick-locked",
                    "trick", plugin.messages().plain("trick-" + trick.id()),
                    "mood", String.valueOf(mood),
                    "need", String.valueOf(trick.minMood()));
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isFood(hand)) {
            plugin.messages().send(player, "feed-no-food");
            return;
        }
        consume(player, hand);
        double chance = PetTricks.learnChance(mood, config().tricks().learnChanceBase(),
                config().tricks().learnChancePerMood());
        String trickName = plugin.messages().plain("trick-" + trick.id());
        if (Math.random() > chance) {
            plugin.messages().send(player, "trick-failed",
                    "name", displayName(data),
                    "trick", trickName,
                    "chance", String.valueOf(Math.round(chance * 100)));
            return;
        }
        store.learnTrick(data.id(), trick.id());
        store.addDiary(data.id(), System.currentTimeMillis(), "trick", trick.id(),
                config().diarySize());
        plugin.messages().send(player, "trick-learned",
                "name", displayName(data),
                "trick", trickName);
        PetEffects.sparks(pet.getWorld(), pet.getLocation());
        PetEffects.sound(player, "entity.player.levelup", 0.6f, 1.6f);
    }

    /** Исполнение выученного трюка: эмоция, частицы и звук. */
    public void performTrick(Player player, LivingEntity pet, String rawId) {
        PetStore.PetData data = data(pet);
        if (data == null) {
            plugin.messages().send(player, "no-pet");
            return;
        }
        PetTricks trick = PetTricks.byId(rawId);
        if (trick == null) {
            plugin.messages().send(player, "trick-unknown", "trick", String.valueOf(rawId));
            return;
        }
        String trickName = plugin.messages().plain("trick-" + trick.id());
        if (!data.hasTrick(trick.id())) {
            plugin.messages().send(player, "trick-not-learned", "trick", trickName);
            return;
        }
        long now = System.currentTimeMillis();
        long left = config().tricks().cooldownSeconds() * 1000L - (now - data.lastTrick());
        if (left > 0L) {
            plugin.messages().send(player, "trick-cooldown",
                    "seconds", String.valueOf((left + 999L) / 1000L));
            return;
        }
        store.markAction(data.id(), "trick", now);
        PetCare.State state = care(data);
        if (state != null) {
            PetCare.State next = PetCare.patted(state, config().care());
            store.setCare(data.id(), next.satiety(), next.mood(), now);
        }
        PetEffects.trick(pet.getWorld(), pet.getLocation(), trick);
        PetEffects.sound(player, "entity.parrot.imitate." + (data.species() == PetSpecies.CAT
                ? "cat" : "wolf"), 0.7f, 1.3f);
        plugin.messages().send(player, "trick-used",
                "name", displayName(data),
                "trick", trickName);
    }

    /* ------------------------------------------------------------------ режим */

    /** Меняет режим и сразу применяет посадку. */
    public void setMode(Player player, LivingEntity pet, PetMode mode) {
        PetStore.PetData data = data(pet);
        if (data == null) {
            plugin.messages().send(player, "no-pet");
            return;
        }
        store.setMode(data.id(), mode);
        applyMode(pet, data);
        plugin.messages().send(player, "mode-set",
                "mode", plugin.messages().plain("mode-" + mode.id()),
                "name", displayName(data));
        store.addDiary(data.id(), System.currentTimeMillis(), "mode", mode.id(),
                config().diarySize());
    }

    /* ------------------------------------------------------- вызов и питомцы */

    /**
     * Телепортирует ближайшего загруженного питомца к хозяину. Запрещено в бою:
     * иначе питомец стал бы бесплатным «эвакуатором» из драки.
     */
    public void call(Player player) {
        if (!config().enabled()) {
            plugin.messages().send(player, "pets-disabled");
            return;
        }
        long now = System.currentTimeMillis();
        Long blocked = combatUntil.get(player.getUniqueId());
        if (blocked != null && blocked > now) {
            plugin.messages().send(player, "call-combat",
                    "seconds", String.valueOf((blocked - now + 999L) / 1000L));
            return;
        }
        LivingEntity pet = nearestLoadedPet(player);
        if (pet == null) {
            plugin.messages().send(player, "call-no-pet");
            return;
        }
        PetStore.PetData data = data(pet);
        if (data == null) {
            plugin.messages().send(player, "call-no-pet");
            return;
        }
        long left = config().movement().callCooldownSeconds() * 1000L - (now - data.lastCalled());
        if (left > 0L) {
            plugin.messages().send(player, "call-cooldown",
                    "name", displayName(data),
                    "seconds", String.valueOf((left + 999L) / 1000L));
            return;
        }
        if (!pet.getWorld().equals(player.getWorld())) {
            plugin.messages().send(player, "call-world", "name", displayName(data));
            return;
        }
        if (!pet.teleport(player.getLocation())) {
            plugin.messages().send(player, "call-failed", "name", displayName(data));
            return;
        }
        store.markAction(data.id(), "called", now);
        store.addDiary(data.id(), now, "called", "", config().diarySize());
        plugin.messages().send(player, "call-ok", "name", displayName(data));
        PetEffects.hearts(pet.getWorld(), pet.getLocation());
    }

    /** Запоминает урон игрока: вызов питомца блокируется на несколько секунд. */
    public void noteCombat(Player player) {
        if (player == null) {
            return;
        }
        int seconds = config().movement().combatBlockSeconds();
        if (seconds <= 0) {
            return;
        }
        combatUntil.put(player.getUniqueId(),
                System.currentTimeMillis() + seconds * 1000L);
    }

    /**
     * Учитывает время рядом: пульс ауры добавляет секунды к счётчику. Дельта
     * ограничена сверху, чтобы лаг сервера не накручивал часы.
     */
    void tickTogether(PetStore.PetData data) {
        if (data == null || data.dead()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = togetherSeen.put(data.id(), now);
        if (last == null) {
            return;
        }
        long cap = Math.max(2000L, config().refreshTicks() * 100L);
        long seconds = Math.min(now - last, cap) / 1000L;
        if (seconds <= 0L) {
            return;
        }
        long before = data.minutesTogether();
        store.addTogetherSeconds(data.id(), seconds);
        if (data.minutesTogether() != before) {
            checkAchievements(data);
        }
    }

    /** Забывает отметку «рядом» — при уходе питомца или игрока. */
    void forgetTogether(UUID petId) {
        if (petId != null) {
            togetherSeen.remove(petId);
        }
    }

    /** Ближайший загруженный живой питомец игрока в любом мире. */
    private LivingEntity nearestLoadedPet(Player player) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (PetStore.PetData data : store.alivePetsOf(player.getUniqueId())) {
            Entity entity = Bukkit.getServer().getEntity(data.id());
            if (!(entity instanceof LivingEntity living) || living.isDead() || !living.isValid()) {
                continue;
            }
            double distance = living.getWorld().equals(player.getWorld())
                    ? living.getLocation().distanceSquared(player.getLocation())
                    : Double.MAX_VALUE / 2;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = living;
            }
        }
        return best;
    }

    /* --------------------------------------------------------------- достижения */

    /** Проверяет и закрывает вехи; каждой новой отправляет сообщение хозяину. */
    void checkAchievements(PetStore.PetData data) {
        if (data == null || data.dead()) {
            return;
        }
        PetAchievement.Progress progress = new PetAchievement.Progress(
                level(data), config().curve().maxLevel(), data.kills(), data.fish(),
                data.minutesTogether());
        for (PetAchievement achievement : PetAchievement.values()) {
            if (data.hasAchievement(achievement.id()) || !achievement.unlocked(progress)) {
                continue;
            }
            store.unlockAchievement(data.id(), achievement.id());
            store.addDiary(data.id(), System.currentTimeMillis(), "achievement",
                    achievement.id(), config().diarySize());
            Player owner = Bukkit.getPlayer(data.ownerId());
            if (owner != null) {
                plugin.messages().send(owner, "achievement-unlocked",
                        "name", displayName(data),
                        "achievement", plugin.messages().plain(
                                "achievement-" + achievement.id()));
            }
        }
    }

    /** Учитывает убийство волка и проверяет вехи. */
    public void noteKill(PetStore.PetData data) {
        if (data == null) {
            return;
        }
        store.addKill(data.id());
        store.addDiary(data.id(), System.currentTimeMillis(), "kill", "", config().diarySize());
        checkAchievements(data);
    }

    /** Учитывает пойманную рядом рыбу и проверяет вехи. */
    public void noteFish(PetStore.PetData data) {
        if (data == null) {
            return;
        }
        store.addFish(data.id());
        store.addDiary(data.id(), System.currentTimeMillis(), "fish", "", config().diarySize());
        checkAchievements(data);
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
        store.addDeath(data.id());
        store.addDiary(data.id(), System.currentTimeMillis(), "died", String.valueOf(level),
                config().diarySize());
        budgets.remove(data.id());
        forgetTogether(data.id());
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
        store.markAction(data.id(), "renamed", System.currentTimeMillis());
        store.addDiary(data.id(), System.currentTimeMillis(), "rename", cleanName,
                config().diarySize());
        applyDisplay(pet, data);
    }

    /**
     * Автокличка на третьем уровне: только для безымянных питомцев и только
     * если пул имён не пуст. Детерминирована по UUID, поэтому не «прыгает».
     */
    private void autoName(LivingEntity pet, PetStore.PetData data) {
        if (!data.name().isBlank() || level(data) < 3) {
            return;
        }
        String suggested = PetNicknames.suggest(config().autoNames(data.species()), data.id());
        if (suggested.isBlank()) {
            return;
        }
        String clean = PetNames.sanitize(suggested, config().maxNameLength());
        if (!PetNames.valid(clean, config().minNameLength(), config().maxNameLength())) {
            return;
        }
        store.rename(data.id(), clean);
        store.addDiary(data.id(), System.currentTimeMillis(), "rename", clean,
                config().diarySize());
        applyDisplay(pet, data);
        Player owner = Bukkit.getPlayer(data.ownerId());
        if (owner != null) {
            plugin.messages().send(owner, "name-auto", "name", clean);
        }
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
        return data == null ? 1 : PetCurve.levelFor(data.xp(), config().curve());
    }

    /** Админская установка уровня: опыт пересчитывается по кривой. */
    public void setLevel(PetStore.PetData data, int level) {
        if (data == null) {
            return;
        }
        int target = Math.max(1, Math.min(config().curve().maxLevel(), level));
        store.setXp(data.id(), PetCurve.totalForLevel(target, config().curve()));
        store.setBestLevel(data.id(), target);
        Entity entity = Bukkit.getServer().getEntity(data.id());
        if (entity instanceof LivingEntity living) {
            applyStats(living, data, true);
            applyDisplay(living, data);
        }
        checkAchievements(data);
    }

    /** Админская установка настроения — для проверки трюков и карточки. */
    public void setMood(PetStore.PetData data, int mood) {
        if (data == null) {
            return;
        }
        store.setCare(data.id(), data.satiety(), mood, System.currentTimeMillis());
    }

    /* ------------------------------------------------------------- внутреннее */

    private boolean ownerNear(LivingEntity pet, PetStore.PetData data) {
        Player owner = Bukkit.getPlayer(data.ownerId());
        if (owner == null || !owner.getWorld().equals(pet.getWorld())) {
            return false;
        }
        return owner.getLocation().distanceSquared(pet.getLocation())
                <= config().bonuses().nearRadius() * config().bonuses().nearRadius();
    }

    private static boolean isNight(World world) {
        if (world == null) {
            return false;
        }
        long time = world.getTime();
        return time >= 13_000L && time < 23_000L;
    }

    private void applyStats(LivingEntity pet, PetStore.PetData data, boolean restoreHealth) {
        PetsConfig.Species species = config().species(data.species());
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
            health.setBaseValue(config().species(data.species()).baseHealth());
        }
        PetAttributes.remove(pet, Attribute.ATTACK_DAMAGE, key("damage"));
        PetAttributes.remove(pet, Attribute.MOVEMENT_SPEED, key("speed"));
    }

    private void applyDisplay(LivingEntity living, PetStore.PetData data) {
        boolean named = !data.name().isBlank();
        if (!named && !config().showLevel()) {
            living.customName(null);
            living.setCustomNameVisible(false);
            return;
        }
        Component name = config().showLevel()
                ? plugin.messages().get("name-format",
                        "name", displayName(data),
                        "level", String.valueOf(level(data)))
                : plugin.messages().get("name-format-plain", "name", displayName(data));
        living.customName(name);
        living.setCustomNameVisible(true);
    }

    /** Восстанавливает посадку по сохранённому режиму. */
    private void applyMode(LivingEntity pet, PetStore.PetData data) {
        if (pet instanceof Sittable sittable) {
            sittable.setSitting(data.mode() == PetMode.STAY);
        }
    }

    /** Ошейник и свечение — только если косметика включена в конфиге. */
    private void applyCosmetics(LivingEntity pet, PetStore.PetData data) {
        if (config().cosmetics().collarEnabled() && !data.collar().isEmpty()) {
            DyeColor color = PetCollar.of(data.collar());
            if (color != null) {
                PetCollar.apply(pet, color);
            }
        }
        if (config().cosmetics().glowEnabled()) {
            pet.setGlowing(data.glow());
        }
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

    /* ---------------------------------------------------------------- еда */

    private static boolean isFood(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        Material type = stack.getType();
        return FISH.contains(type) || MEAT.contains(type);
    }

    private static boolean isFavorite(PetSpecies species, Material type) {
        return species == PetSpecies.CAT ? FISH.contains(type) : MEAT.contains(type);
    }

    private static void consume(Player player, ItemStack hand) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        ItemStack next = hand.clone();
        next.setAmount(next.getAmount() - 1);
        player.getInventory().setItemInMainHand(next.getAmount() <= 0 ? null : next);
    }

    /** Русское название еды из messages.yml; неизвестное — имя материала. */
    private String itemName(ItemStack stack) {
        String id = stack.getType().name().toLowerCase(java.util.Locale.ROOT);
        String key = "item-" + id;
        String translated = plugin.messages().plain(key);
        return translated.isBlank() || translated.equals(key) ? id.replace('_', ' ') : translated;
    }

    /** Число без хвостовых нулей: 18.0 → «18», 3.60 → «3.6». */
    static String number(double value) {
        String text = String.format(java.util.Locale.ROOT, "%.1f", value);
        text = text.replaceAll("0+$", "");
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.isEmpty() ? "0" : text;
    }
}
