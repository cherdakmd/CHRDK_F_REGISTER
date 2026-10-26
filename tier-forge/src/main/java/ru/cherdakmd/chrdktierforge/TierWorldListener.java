package ru.cherdakmd.chrdktierforge;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Random;

/**
 * Редкость мировой добычи (1.4.185): вещь, появившаяся НЕ из крафта, получает
 * тир в момент появления, а не тогда, когда игрок её наденет.
 *
 * <p>Два источника, которые Bukkit отдаёт отдельными событиями: лут-таблицы
 * сундуков и структур ({@link LootGenerateEvent}) и рыбалка
 * ({@link PlayerFishEvent}). Дроп с мобов и крафт живут в своих слушателях с
 * 1.4.4x, а клады выдают тир через {@code ChrdkTierApi}; вместе это закрывает
 * все пути, которыми на сервере появляется новая экипировка.</p>
 *
 * <p>Почему не «скан инвентаря»: скан выдал бы редкость и старым вещам,
 * которые игрок носит с прошлой волны, — а тир пересобирает модификаторы, и
 * случайный «проклятый» ролл молча ослабил бы чужой меч. Здесь редкость
 * получает только то, что мир создал после обновления.</p>
 *
 * <p>При {@code identify.enabled: true} предмет уходит в сундук/на крючок
 * «неопознанным», и редкость открывается в инвентаре — ровно как у дропа с
 * мобов. Крафт сюда не попадает: у него свой шанс ({@code craft.*}).</p>
 */
public final class TierWorldListener implements Listener {

    /** Источник для PDC — витрина {@code /tiers info} показывает, откуда тир. */
    public static final String SOURCE = "world";

    private final TierForgePlugin plugin;
    private final Random random = new Random();

    public TierWorldListener(TierForgePlugin plugin) {
        this.plugin = plugin;
    }

    /** Сундуки, бочки и находки структур: лут-таблица наполняет инвентарь. */
    @EventHandler(ignoreCancelled = true)
    public void onLootGenerate(LootGenerateEvent event) {
        TierForgeConfig config = plugin.config();
        if (config == null || !config.worldLootEnabled()) {
            return;
        }
        if (config.worldWorldBlocked(event.getWorld().getName())) {
            return;
        }
        List<ItemStack> loot = event.getLoot();
        if (loot == null || loot.isEmpty()) {
            return;
        }
        // LootGenerateEvent#getLoot() — изменяемый список: правки попадают в сундук.
        Player owner = event.getEntity() instanceof Player player ? player : null;
        for (ItemStack stack : loot) {
            if (stamp(stack, config) && owner != null) {
                credit(owner, stack);
            }
        }
    }

    /** Рыбалка: пойманная вещь ещё висит на крючке — правим до подбора. */
    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        TierForgeConfig config = plugin.config();
        if (config == null || !config.worldFishingEnabled()) {
            return;
        }
        if (config.worldWorldBlocked(event.getPlayer().getWorld().getName())) {
            return;
        }
        if (!(event.getCaught() instanceof Item caught)) {
            return;
        }
        ItemStack stack = caught.getItemStack();
        if (stamp(stack, config)) {
            credit(event.getPlayer(), stack);
        }
        // У сущности-предмета свой снимок стека: без записи обратно тир
        // остался бы только в нашей копии.
        caught.setItemStack(stack);
    }

    /**
     * Разыгрывает шанс и применяет редкость к одному предмету.
     *
     * @return {@code true}, если тир уже стоит на предмете (в identify-флоу —
     *         {@code false}: редкость откроется при опознании)
     */
    private boolean stamp(ItemStack stack, TierForgeConfig config) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (plugin.items().tierOf(stack) != null) {
            return false; // уже с тиром (крафт/дроп/клад/прошлый проход)
        }
        if (plugin.items().isUnidentified(stack)) {
            return false;
        }
        ItemKind kind = ItemKind.classify(stack.getType().name());
        if (!config.kindAllowed(kind)) {
            return false;
        }
        if (config.materialBlocked(stack.getType())) {
            return false;
        }
        int chance = config.worldChanceFor(kind);
        if (chance <= 0 || random.nextInt(100) >= chance) {
            return false;
        }
        if (config.identifyUnidentified()) {
            plugin.items().markUnidentified(stack, SOURCE,
                    plugin.messages().get("unidentified-name"),
                    List.of(plugin.messages().get("unidentified-lore")));
            return false;
        }
        TierDef.Snapshot roll = TierRoller.roll(config.tiers(), random);
        if (roll == null) {
            return false; // страховка: роллер не должен вернуть null
        }
        plugin.items().apply(stack, roll);
        plugin.items().setSource(stack, SOURCE);
        return true;
    }

    /** Статистика и коллекция: только когда известен получатель. */
    private void credit(Player player, ItemStack stack) {
        String tier = plugin.items().tierOf(stack);
        if (tier == null) {
            return;
        }
        plugin.stats().count(player.getUniqueId(), tier);
        plugin.collection().record(player, stack);
    }
}
