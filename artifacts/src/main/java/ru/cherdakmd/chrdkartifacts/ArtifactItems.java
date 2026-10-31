package ru.cherdakmd.chrdkartifacts;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Предметы артефактов: постройка стека (имя из messages.yml, плашки лора, glint у
 * светлых), чтение/поиск по инвентарю и уникальные свойства (волна 19).
 *
 * <p>Хранилище — только PDC предмета: {@code artifact_id} и
 * {@code artifact_traits} («id:level;…»). Отдельного файла у плагина нет.
 * Свойства бросаются в момент выдачи, поэтому две копии одного артефакта могут
 * отличаться — это и есть вариативность.</p>
 */
public final class ArtifactItems {

    /** Ключ PDC со свойствами артефакта («id:level;…»). */
    public static final String TRAITS_KEY = "artifact_traits";

    /** Маркеры строк лора: польза и вред. */
    public static final String GOOD_TRAIT_MARKER = "▲ ";
    public static final String BAD_TRAIT_MARKER = "▼ ";

    private final ArtifactsPlugin plugin;
    private final NamespacedKey idKey;
    private final NamespacedKey traitKey;
    private final Random random = new Random();

    public ArtifactItems(ArtifactsPlugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "artifact_id");
        this.traitKey = new NamespacedKey(plugin, TRAITS_KEY);
    }

    /** Собирает стек артефакта. Количество ограничено размером стака материала. */
    public ItemStack build(ArtifactDef def, int amount) {
        return build(def, amount, rollTraits(def));
    }

    /** Собирает стек с заранее известными свойствами (тесты, повторная выдача). */
    public ItemStack build(ArtifactDef def, int amount, Map<String, Integer> traits) {
        ItemStack stack = new ItemStack(def.material(),
                Math.max(1, Math.min(amount, def.material().getMaxStackSize())));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(nameOf(def), color(def))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(buildLore(def, traits));
        if (def.side() == ArtifactDef.Side.GOOD) {
            meta.setEnchantmentGlintOverride(Boolean.TRUE);
        }
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, def.id());
        String encoded = ArtifactTraitCodec.encode(traits);
        if (!encoded.isEmpty()) {
            meta.getPersistentDataContainer().set(traitKey, PersistentDataType.STRING, encoded);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /** Бросок свойств по текущему конфигу; пусто — предмет «чистый». */
    public Map<String, Integer> rollTraits(ArtifactDef def) {
        ArtifactsConfig.Traits config = plugin.config().traits();
        if (config == null || !config.enabled()) {
            return Map.of();
        }
        List<ArtifactTraits.Roll> rolls = ArtifactTraits.roll(def.side(), random,
                config.chancePercent(), config.maxPerItem(), config.maxLevel(),
                config.crossChancePercent());
        if (rolls.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (ArtifactTraits.Roll roll : rolls) {
            result.merge(roll.id(), roll.level(), Math::max);
        }
        return Collections.unmodifiableMap(result);
    }

    /** Свойства конкретного предмета (пустая карта — их нет). */
    public Map<String, Integer> traitsOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Map.of();
        }
        String raw = item.getItemMeta().getPersistentDataContainer()
                .get(traitKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, Integer> decoded = ArtifactTraitCodec.decode(raw);
        Map<String, Integer> valid = new LinkedHashMap<>();
        decoded.forEach((id, level) -> {
            ArtifactTraits.Def def = ArtifactTraits.byId(id);
            if (def != null) {
                valid.put(def.id(), Math.min(level, def.maxLevel()));
            }
        });
        return valid.isEmpty() ? Map.of() : Collections.unmodifiableMap(valid);
    }

    /**
     * Свойства всех носимых артефактов: id → максимальный уровень.
     * Дубли не складываются, выключенные в конфиге артефакты не учитываются.
     */
    public Map<String, Integer> carriedTraitLevels(Player player) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        collectTraits(merged, player.getInventory().getStorageContents());
        collectTraits(merged, player.getInventory().getArmorContents());
        collectTraits(merged, new ItemStack[]{player.getInventory().getItemInOffHand(),
                player.getInventory().getItemInMainHand()});
        return merged.isEmpty() ? Map.of() : Collections.unmodifiableMap(merged);
    }

    private void collectTraits(Map<String, Integer> merged, ItemStack[] items) {
        if (items == null) {
            return;
        }
        for (ItemStack item : items) {
            String id = idOf(item);
            if (id == null || !plugin.config().isEnabled(id)) {
                continue;
            }
            traitsOf(item).forEach((trait, level) -> merged.merge(trait, level, Math::max));
        }
    }

    /**
     * Имя артефакта для предмета и сообщений: строка из messages.yml, а если её
     * нет (удалённая секция, неполный перевод) — читаемое имя из ида. Так игрок
     * никогда не видит безымянный предмет и пустое место в шаблоне.
     */
    public String nameOf(ArtifactDef def) {
        return ArtifactText.orFallback(
                plugin.messages().raw("art." + def.id() + ".name"), def.id());
    }

    /** Ид артефакта из PDC предмета (null для обычных предметов). */
    public String idOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer()
                .get(idKey, PersistentDataType.STRING);
        return id != null && ArtifactRegistry.byId(id) != null ? id : null;
    }

    /** Все иды артефактов, что игрок носит при себе (основной инвентарь + броня + вторая рука). */
    public Set<String> carriedIds(Player player) {
        Set<String> ids = new HashSet<>();
        collect(ids, player.getInventory().getStorageContents());
        collect(ids, player.getInventory().getArmorContents());
        collect(ids, new ItemStack[]{player.getInventory().getItemInOffHand(),
                player.getInventory().getItemInMainHand()});
        return ids;
    }

    private void collect(Set<String> into, ItemStack[] items) {
        if (items == null) {
            return;
        }
        for (ItemStack item : items) {
            String id = idOf(item);
            if (id != null) {
                into.add(id);
            }
        }
    }

    private static NamedTextColor color(ArtifactDef def) {
        if (def.side() == ArtifactDef.Side.GOOD) {
            return def.mode() == ArtifactDef.Mode.ACTIVE ? NamedTextColor.GOLD : NamedTextColor.YELLOW;
        }
        return def.mode() == ArtifactDef.Mode.ACTIVE ? NamedTextColor.DARK_RED : NamedTextColor.RED;
    }

    /** Бережно обновляет только прежние строки свойств в рюкзаке/броне/второй руке. */
    public void refreshCarriedLore(Player player) {
        var inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (idOf(item) == null) continue;
            Map<String, Integer> traits = traitsOf(item);
            if (traits.isEmpty()) continue;
            ItemMeta meta = item.getItemMeta();
            List<Component> before = meta.lore();
            if (before == null) continue;
            Map<Component, Component> replacements = new LinkedHashMap<>();
            traits.forEach((id, level) -> {
                ArtifactTraits.Def trait = ArtifactTraits.byId(id);
                replacements.put(traitLine(trait, level, false), traitLine(trait, level, true));
            });
            List<Component> after = ArtifactLore.replaceKnown(before, replacements);
            if (!before.equals(after)) {
                meta.lore(after);
                item.setItemMeta(meta);
                inventory.setItem(slot, item);
            }
        }
    }

    private Component traitLine(ArtifactTraits.Def trait, int level, boolean exact) {
        int bounded = Math.max(1, Math.min(level, trait.maxLevel()));
        return plugin.messages().get(trait.polarity().good() ? "trait-line-good" : "trait-line-bad",
                "name", plugin.messages().mini("trait." + trait.id() + ".name"),
                "levelroman", ArtifactTraitCodec.roman(bounded),
                "desc", exact
                        ? plugin.messages().mini("trait." + trait.id() + ".detail",
                                "value", ArtifactTraitText.signedValue(trait, bounded),
                                "effectlevel", ArtifactTraitText.effectLevel(trait, bounded))
                        : plugin.messages().mini("trait." + trait.id() + ".desc"));
    }

    private List<Component> buildLore(ArtifactDef def, Map<String, Integer> traits) {
        List<Component> lore = new ArrayList<>();
        String lineKey = switch (def.side()) {
            case GOOD -> def.mode() == ArtifactDef.Mode.PASSIVE
                    ? "line-good-passive" : "line-good-active";
            case CURSED -> def.mode() == ArtifactDef.Mode.PASSIVE
                    ? "line-cursed-passive" : "line-cursed-active";
        };
        lore.add(plugin.messages().get(lineKey));
        String desc = ArtifactText.loreOrEmpty(plugin.messages().raw("art." + def.id() + ".lore"));
        if (!desc.isEmpty()) {
            lore.add(plugin.messages().get("lore-desc", "text", Messages.trusted(desc)));
        }
        if (traits != null) {
            for (Map.Entry<String, Integer> entry : traits.entrySet()) {
                ArtifactTraits.Def trait = ArtifactTraits.byId(entry.getKey());
                if (trait == null) {
                    continue;
                }
                lore.add(traitLine(trait, entry.getValue(), true));
            }
        }
        if (def.mode() == ArtifactDef.Mode.ACTIVE) {
            lore.add(plugin.messages().get("lore-active-info",
                    "cooldown", String.valueOf(plugin.config().cooldownSeconds(def)),
                    "xp", String.valueOf(plugin.config().xpCostLevels(def))));
        }
        return lore;
    }
}
