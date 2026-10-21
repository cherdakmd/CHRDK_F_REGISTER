package ru.cherdakmd.chrdktreasures;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import ru.cherdakmd.chrdkregister.Messages;

import java.time.Duration;

/**
 * Эффектное появление клада: заголовок, строка действия и фейерверк.
 *
 * <p>Оформление ступенчатое, как в оригинальном датапаке: обычный и редкий клад
 * обходятся звуком, частицами и сообщением; эпический добавляет заголовок на
 * экране; легендарный и мифический — ещё и фейерверк. Пороги настраиваются в
 * {@code effects}, поэтому сервер без права на фейерверки может их выключить,
 * не теряя остальных эффектов.</p>
 *
 * <p>Заголовок и строка действия подчиняются переключателю
 * {@code /treasures toggle announce}, фейерверк — переключателю частиц: игрок,
 * которому мешает визуальный шум, отключает его целиком, а не по одному
 * элементу.</p>
 */
public final class TreasureShowcase {

    private static final Title.Times TIMES = Title.Times.times(
            Duration.ofMillis(300), Duration.ofMillis(1800), Duration.ofMillis(600));

    private final TreasuresPlugin plugin;

    TreasureShowcase(TreasuresPlugin plugin) {
        this.plugin = plugin;
    }

    void show(Player player, TreasureService.Found found) {
        if (player == null || found == null || found.tier() == null) {
            return;
        }
        TreasuresConfig.Effects effects = plugin.config().effects();
        String key = player.getUniqueId().toString();
        boolean announce = plugin.store().announce(key);
        boolean particles = plugin.store().particles(key);
        TreasureTier tier = found.tier();

        if (announce && effects.actionbar()) {
            player.sendActionBar(plugin.messages().get("actionbar-found",
                    "tier", Messages.trusted(plugin.messages().raw("tier." + tier.id())),
                    "streak", String.valueOf(Math.max(1, found.streak())),
                    "found", String.valueOf(plugin.treasures().foundItems(player)),
                    "total", String.valueOf(TreasureCatalog.size())));
        }
        if (announce && effects.titles() && atLeast(tier, effects.titleMinTier())) {
            Component subtitle = found.named()
                    ? plugin.messages().get("title-found-named",
                    "name", Messages.trusted(plugin.messages().raw(
                            "treasure." + found.item().id() + ".name")))
                    : plugin.messages().get("title-found-loot");
            player.showTitle(Title.title(
                    plugin.messages().get("title-found",
                            "tier", Messages.trusted(plugin.messages().raw("tier." + tier.id()))),
                    subtitle, TIMES));
        }
        if (particles && effects.fireworks() && atLeast(tier, effects.fireworkMinTier())) {
            launch(player, tier);
        }
    }

    private static boolean atLeast(TreasureTier tier, TreasureTier threshold) {
        return threshold == null || tier.ordinal() >= threshold.ordinal();
    }

    /** Фейерверк взрывается сразу на месте находки — ракета не улетает от игрока. */
    private void launch(Player player, TreasureTier tier) {
        World world = player.getWorld();
        Location at = player.getLocation().clone().add(0.0, 1.2, 0.0);
        try {
            Firework firework = world.spawn(at, Firework.class);
            FireworkMeta meta = firework.getFireworkMeta();
            meta.setPower(0);
            meta.addEffect(effectOf(tier));
            if (tier == TreasureTier.MYTHICAL) {
                meta.addEffect(FireworkEffect.builder()
                        .with(FireworkEffect.Type.BURST)
                        .withColor(Color.fromRGB(0x00FFCC))
                        .withFade(Color.fromRGB(0xFFFFFF))
                        .trail(true)
                        .build());
            }
            firework.setFireworkMeta(meta);
            firework.detonate();
        } catch (RuntimeException exception) {
            // Фейерверк — украшение: если сервер запретил спавн, находка не страдает.
            plugin.getLogger().fine("Фейерверк клада не запустился: " + exception.getMessage());
        }
    }

    private static FireworkEffect effectOf(TreasureTier tier) {
        return switch (tier) {
            case LEGENDARY -> FireworkEffect.builder()
                    .with(FireworkEffect.Type.BALL_LARGE)
                    .withColor(Color.fromRGB(0xFFD700))
                    .withFade(Color.fromRGB(0xFF7A00))
                    .trail(true)
                    .flicker(true)
                    .build();
            case MYTHICAL -> FireworkEffect.builder()
                    .with(FireworkEffect.Type.STAR)
                    .withColor(Color.fromRGB(0xFF3355), Color.fromRGB(0xCC00FF))
                    .withFade(Color.fromRGB(0x00FFCC))
                    .trail(true)
                    .flicker(true)
                    .build();
            default -> FireworkEffect.builder()
                    .with(FireworkEffect.Type.BALL)
                    .withColor(Color.fromRGB(0xB266FF))
                    .withFade(Color.fromRGB(0x33CCFF))
                    .trail(true)
                    .build();
        };
    }
}
