package ru.cherdakmd.chrdkitems;

import org.bukkit.entity.Cat;
import org.bukkit.entity.Player;

/** Read-only utilities; no blocks, other players, inventories or entities are modified. */
final class BuiltinUse {
    static boolean supports(ItemCatalog.Definition item) {
        return BuiltinItems.isBuiltin(item) && (item.id().equals("cat_finder")
                || item.id().equals("spawn_guide") || item.id().equals("weather_gauge"));
    }
    static String describe(ItemCatalog.Definition item, Player player) {
        var location = player.getLocation();
        var world = location.getWorld();
        if (world == null || !supports(item)) return null;
        return switch (item.id()) {
            case "cat_finder" -> {
                double distance = player.getNearbyEntities(16, 16, 16).stream()
                        .filter(entity -> entity instanceof Cat)
                        .mapToDouble(entity -> entity.getLocation().distanceSquared(location))
                        .filter(d -> d <= 256).min().orElse(-1);
                yield distance < 0 ? "Кошек в радиусе 16 блоков не найдено."
                        : "Ближайшая кошка: примерно " + (int) Math.ceil(Math.sqrt(distance)) + " блоков.";
            }
            case "spawn_guide" -> {
                var spawn = world.getSpawnLocation();
                yield "Спавн мира " + world.getName() + ": " + spawn.getBlockX() + ", " + spawn.getBlockY() + ", " + spawn.getBlockZ() + ".";
            }
            case "weather_gauge" -> {
                String weather = world.isThundering() ? "гроза" : world.hasStorm() ? "осадки" : "ясно";
                yield "Погода: " + weather + "; до смены состояния осадков примерно "
                        + Math.max(0, world.getWeatherDuration() / 20) + " сек. Игровое правило может остановить смену погоды.";
            }
            default -> null;
        };
    }
}
