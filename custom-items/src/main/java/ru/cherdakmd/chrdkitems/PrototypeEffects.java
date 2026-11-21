package ru.cherdakmd.chrdkitems;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.util.Map;

/** Non-combat, self-only, test abilities. PDC is an identifier, not an authenticity guarantee. */
final class PrototypeEffects {
    enum Effect {
        COMPASS("Показывает направление взгляда.", false),
        CLOCK("Показывает время текущего мира.", false),
        DAYS("Показывает номер игрового дня мира.", false),
        LIGHT("Показывает освещённость у игрока.", false),
        HUNGER("Показывает уровень сытости игрока.", false),
        ALTITUDE("Показывает текущую высоту.", false),
        TARGET("Определяет видимый блок в пределах 5 блоков.", false),
        SKY("Показывает уровень освещения небом у игрока.", false),
        BIOME("Показывает текущий биом.", false),
        POSITION("Одноразово показывает координаты и мир.", true),
        EXPERIENCE("Одноразово показывает уровень опыта.", true),
        RESPAWN("Одноразово показывает свою точку возрождения, если она установлена.", true);
        final String description;
        final boolean consumable;
        Effect(String description, boolean consumable) { this.description = description; this.consumable = consumable; }
    }
    private static final Map<String, Effect> EFFECTS = Map.ofEntries(
            Map.entry("test_blade", Effect.COMPASS), Map.entry("test_sunblade", Effect.CLOCK),
            Map.entry("test_moonblade", Effect.DAYS), Map.entry("test_helm", Effect.LIGHT),
            Map.entry("test_chest", Effect.HUNGER), Map.entry("test_boots", Effect.ALTITUDE),
            Map.entry("test_pick", Effect.TARGET), Map.entry("test_shovel", Effect.SKY),
            Map.entry("test_hoe", Effect.BIOME), Map.entry("test_scroll", Effect.POSITION),
            Map.entry("test_rune", Effect.EXPERIENCE), Map.entry("test_charm", Effect.RESPAWN));

    static Effect effect(ItemCatalog.Definition definition) {
        return PrototypeVisuals.modelData(definition) == null ? null : EFFECTS.get(definition.id());
    }

    static boolean identityMatches(ItemStack item, ItemCatalog.Definition definition) {
        if (item == null || definition == null || item.getAmount() < 1 || item.getType() != definition.material()) return false;
        var meta = item.getItemMeta();
        if (meta == null) return false;
        var data = meta.getPersistentDataContainer();
        return definition.id().equals(data.get(PrototypeItems.ID, PersistentDataType.STRING))
                && Integer.valueOf(PrototypeItems.CURRENT_SCHEMA).equals(data.get(PrototypeItems.SCHEMA, PersistentDataType.INTEGER));
    }

    /** Null means no effect was possible, so a consumable must not be spent. */
    static String describe(Effect effect, Player player) {
        var location = player.getLocation();
        var world = location.getWorld();
        if (world == null) return null;
        return switch (effect) {
            case COMPASS -> "Направление взгляда: " + player.getFacing().name();
            case CLOCK -> "Время мира (тики): " + world.getTime() + " / 24000.";
            case DAYS -> "Игровой день мира: " + (Math.floorDiv(world.getFullTime(), 24000L) + 1) + ".";
            case LIGHT -> "Освещённость в вашем блоке: " + location.getBlock().getLightLevel() + " / 15.";
            case HUNGER -> "Сытость: " + player.getFoodLevel() + " / 20.";
            case ALTITUDE -> "Текущая высота Y: " + location.getBlockY() + ".";
            case TARGET -> {
                var block = player.getTargetBlockExact(5);
                yield block == null ? "Видимый блок в пределах 5 блоков не найден." : "Видимый блок: " + block.getType().name() + ".";
            }
            case SKY -> "Свет неба в вашем блоке: " + location.getBlock().getLightFromSky() + " / 15.";
            case BIOME -> "Биом: " + location.getBlock().getBiome() + ".";
            case POSITION -> "Мир " + world.getName() + ": " + coordinates(location);
            case EXPERIENCE -> "Уровень опыта: " + player.getLevel() + ".";
            case RESPAWN -> {
                Location spawn = player.getRespawnLocation();
                yield spawn == null || spawn.getWorld() == null ? null
                        : "Точка возрождения: " + spawn.getWorld().getName() + ": " + coordinates(spawn);
            }
        };
    }
    private static String coordinates(Location location) {
        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + ".";
    }
}
