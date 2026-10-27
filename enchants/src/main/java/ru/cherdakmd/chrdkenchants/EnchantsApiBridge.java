package ru.cherdakmd.chrdkenchants;

import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.api.ChrdkEnchantsApi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Реализация {@link ChrdkEnchantsApi} (1.4.128): другие аддоны — прежде всего
 * CHRDK_TierForge — могут спросить каталог чар и наложить их на предмет, не
 * зная ничего о внутренностях модуля.
 *
 * <p>Правила те же, что у стола и наковальни: вид предмета, лимит чар,
 * конфликтные группы, включённость в конфиге и мастер-выключатель проклятий.
 * Все методы терпимы к чужим предметам и выключенному конфигу.</p>
 */
public final class EnchantsApiBridge implements ChrdkEnchantsApi {

    private final EnchantsPlugin plugin;

    public EnchantsApiBridge(EnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    private EnchantsConfig config() {
        return plugin.config();
    }

    @Override
    public boolean isKnown(String id) {
        return EnchantRegistry.byId(id) != null;
    }

    @Override
    public boolean isCurse(String id) {
        EnchantDef def = EnchantRegistry.byId(id);
        return def != null && def.curse();
    }

    @Override
    public List<String> lightIds() {
        EnchantsConfig config = config();
        if (config == null) {
            return List.of();
        }
        return config.enabledLight().stream().map(EnchantDef::id).toList();
    }

    @Override
    public List<String> curseIds() {
        EnchantsConfig config = config();
        if (config == null || !config.cursesEnabled()) {
            return List.of();
        }
        return config.enabledCurses().stream().map(EnchantDef::id).toList();
    }

    @Override
    public String name(String id) {
        EnchantDef def = EnchantRegistry.byId(id);
        if (def == null || plugin.messages() == null) {
            return "";
        }
        return plugin.messages().raw("en." + def.id() + ".name");
    }

    @Override
    public String rarity(String id) {
        EnchantDef def = EnchantRegistry.byId(id);
        return def == null ? "" : def.rarity().name();
    }

    @Override
    public int maxLevel(String id) {
        EnchantDef def = EnchantRegistry.byId(id);
        return def == null ? 0 : def.maxLevel();
    }

    @Override
    public int levelOf(ItemStack item, String id) {
        return id == null ? 0 : plugin.items().levelOf(item, id);
    }

    @Override
    public Map<String, Integer> enchantsOf(ItemStack item) {
        return plugin.items().read(item);
    }

    @Override
    public int count(ItemStack item) {
        return plugin.items().read(item).size();
    }

    @Override
    public boolean apply(ItemStack item, String id, int level) {
        EnchantsConfig config = config();
        EnchantDef def = EnchantRegistry.byId(id);
        if (config == null || item == null || def == null || !config.isEnabled(def.id())) {
            return false;
        }
        Map<String, Integer> present = plugin.items().read(item);
        if (present.containsKey(def.id())) {
            return true; // уже стоит — считаем успехом, уровень не понижаем
        }
        List<EnchantDef> allowed = EnchantRoller.applicable(List.of(def),
                item.getType().name(), present,
                config.maxPerItem(), config.maxLegendaryPerItem());
        if (allowed.isEmpty()) {
            return false;
        }
        plugin.items().apply(item, def, Math.max(1, level));
        return true;
    }

    @Override
    public void copy(ItemStack from, ItemStack to) {
        if (from == null || to == null) {
            return;
        }
        Map<String, Integer> source = plugin.items().read(from);
        if (source.isEmpty()) {
            return;
        }
        Map<String, Integer> merged = new LinkedHashMap<>(plugin.items().read(to));
        source.forEach((id, level) -> merged.merge(id, level, Math::max));
        plugin.items().write(to, merged);
    }

    @Override
    public void refresh(ItemStack item) {
        plugin.items().refresh(item);
    }

    /**
     * 1.4.166: готовая книга чары для источников вне модуля (клады).
     *
     * <p>Правила те же, что у жителей: проклятие не выдаётся при выключенных
     * проклятиях, выключенная в конфиге чара отсутствует, уровень зажимается
     * максимумом. Книга собирается штатным {@code makeEnchantBook}: имя, лор
     * и PDC совпадают с книгой со стола, поэтому наковальня принимает её без
     * исключений.</p>
     */
    @Override
    public ItemStack book(String id, int level) {
        EnchantsConfig config = config();
        EnchantDef def = EnchantRegistry.byId(id);
        if (config == null || def == null || plugin.items() == null
                || !config.isEnabled(def.id())) {
            return null;
        }
        if (def.curse() && !config.cursesEnabled()) {
            return null;
        }
        int clamped = Math.max(1, Math.min(def.maxLevel(), level));
        return plugin.items().makeEnchantBook(def, clamped);
    }
}
