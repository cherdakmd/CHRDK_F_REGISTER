package ru.cherdakmd.chrdktreasures;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Проклятия находок: проверка шанса и применение расплаты.
 *
 * <p>Проверка идёт по надетым предметам в момент события: удар, полученный
 * урон или добытый блок. За одно событие срабатывает не больше одного
 * проклятия, и одно и то же проклятие не повторяется чаще, чем раз в
 * {@value #QUIET_MILLIS} мс, — иначе легендарный клинок превращался бы в
 * бесконечную слепоту.</p>
 *
 * <p>Эффект всегда временный и всегда на владельца: проклятие не отбирает
 * предмет, не ломает его и не может убить. Это цена силы, а не наказание.</p>
 */
public final class TreasureCurses {

    /** Пауза между срабатываниями одного и того же проклятия. */
    private static final long QUIET_MILLIS = 3_000L;

    private final TreasuresPlugin plugin;
    private final Random random = new Random();
    private final Map<UUID, Map<TreasureCurse.Kind, Long>> quietUntil =
            new ConcurrentHashMap<>();

    public TreasureCurses(TreasuresPlugin plugin) {
        this.plugin = plugin;
    }

    /** Проверяет проклятия на удар по мобу. */
    public void onHit(Player attacker) {
        roll(attacker, TreasureCurse.Trigger.HIT);
    }

    /** Проверяет проклятия на полученный урон. */
    public void onDamaged(Player victim) {
        roll(victim, TreasureCurse.Trigger.DAMAGED);
    }

    /** Проверяет проклятия на добытый блок. */
    public void onMine(Player player) {
        roll(player, TreasureCurse.Trigger.MINE);
    }

    /** Снимает историю игрока при выходе. */
    public void forget(UUID playerId) {
        if (playerId != null) {
            quietUntil.remove(playerId);
        }
    }

    private void roll(Player player, TreasureCurse.Trigger trigger) {
        if (player == null || trigger == null || !plugin.config().cursesEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<TreasureCurse.Kind, Long> history =
                quietUntil.computeIfAbsent(player.getUniqueId(), key -> new ConcurrentHashMap<>());
        for (TreasureItemDef def : plugin.items().equipped(player)) {
            TreasureCurse curse = def.curse();
            if (curse == null || curse.kind().trigger() != trigger) {
                continue;
            }
            if (history.getOrDefault(curse.kind(), 0L) > now) {
                continue;
            }
            if (random.nextDouble() >= curse.chance()) {
                continue;
            }
            history.put(curse.kind(), now + QUIET_MILLIS);
            apply(player, curse.kind());
            return;
        }
    }

    private void apply(Player player, TreasureCurse.Kind kind) {
        PotionEffectType type = potionOf(kind);
        if (type == null) {
            return;
        }
        player.addPotionEffect(new PotionEffect(type, kind.durationTicks(), 0,
                true, false, true));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 0.8f);
        plugin.messages().send(player, "curse-trigger",
                "name", Messages.trusted(plugin.messages().raw(kind.textKey())));
    }

    /** Эффект-зелье проклятия. */
    public static PotionEffectType potionOf(TreasureCurse.Kind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case HUNGER -> PotionEffectType.HUNGER;
            case WEAKNESS -> PotionEffectType.WEAKNESS;
            case SLOWNESS -> PotionEffectType.SLOWNESS;
            case BLINDNESS -> PotionEffectType.BLINDNESS;
            case GLOWING -> PotionEffectType.GLOWING;
            case LEVITATION -> PotionEffectType.LEVITATION;
        };
    }
}
