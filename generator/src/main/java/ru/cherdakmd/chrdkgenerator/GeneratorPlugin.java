package ru.cherdakmd.chrdkgenerator;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.ConfigUpgrader;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.api.MoneyLoot;
import ru.cherdakmd.chrdkregister.api.PaidActions;

import java.util.UUID;

/**
 * CHRDK_Generator — личный генератор ресурсов (п.31): физическая камера с
 * лавой и водой, тиры производства и модули улучшений.
 *
 * <p>Ядро-предмет: крафтится на верстаке из конфигурации, ставится в свой
 * приват CHRDK_Claims, снимается владельцем. Камера: два источника — лава и
 * вода — рядом с ядром; цикл производства, бункер офлайн-накопления и
 * настраиваемое топливо. GUI и экономика — следующими шагами по дизайну
 * {@code docs/design/GENERATOR.md}.</p>
 */
public final class GeneratorPlugin extends JavaPlugin {

    private Messages messages;
    private ChrdkRegisterApi coreApi;
    private GeneratorStore store;
    private GeneratorConfig config;
    private GeneratorCoreItem coreItem;
    private NamespacedKey recipeKey;
    private GeneratorTicker ticker;
    private MoneyLoot moneyLoot;
    private PaidActions payments;
    private GeneratorUpgrades upgrades;
    private GeneratorWorldListener worldListener;
    private GeneratorGui gui;
    private GeneratorTake take;
    private GeneratorHolograms holograms;
    private GeneratorBunkerNotifier notifier;
    private GeneratorAutoSell autoSell;
    private GeneratorPlaceholders placeholders;
    private final java.util.Map<String, String> states = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigUpgrader.apply(this);
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        if (coreApi == null) {
            getLogger().severe("CHRDK_Core не найден: модуль остановлен без данных");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        try {
            this.config = GeneratorConfig.load(getConfig());
        } catch (IllegalArgumentException exception) {
            getLogger().severe("конфигурация генератора не принята: " + exception.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.store = new GeneratorStore(getDataFolder().toPath(), getLogger());
        this.store.load();
        try {
            this.store.markRegistered();
        } catch (Exception exception) {
            getLogger().severe("CHRDK_Generator: generator.json не принят диском: " + exception.getMessage());
        }
        this.coreItem = new GeneratorCoreItem(this);
        this.recipeKey = new NamespacedKey(this, "core");
        try {
            this.moneyLoot = new MoneyLoot(this, this::coreApi, this::messages, "generator-payouts");
        } catch (RuntimeException error) {
            getLogger().severe("денежный источник generator-payouts недоступен; данные не сброшены: "
                    + error.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        try {
            this.payments = new PaidActions(this, this::coreApi, this::messages, "generator-upgrades", true);
        } catch (Exception error) {
            getLogger().severe("платёжный протокол generator-upgrades недоступен; данные не сброшены: "
                    + error.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.upgrades = new GeneratorUpgrades(this);
        this.take = new GeneratorTake(this);
        this.gui = new GeneratorGui(this);
        this.holograms = new GeneratorHolograms(this);
        this.notifier = new GeneratorBunkerNotifier(this);
        this.autoSell = new GeneratorAutoSell(this);
        this.worldListener = new GeneratorWorldListener(this);
        registerCraft();
        Bukkit.getPluginManager().registerEvents(this.worldListener, this);
        Bukkit.getPluginManager().registerEvents(this.gui, this);
        Bukkit.getPluginManager().registerEvents(this.notifier, this);
        Bukkit.getPluginManager().registerEvents(new GeneratorRebirthListener(this), this);
        PluginCommand command = getCommand("generator");
        if (command != null) {
            GeneratorCommand handler = new GeneratorCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        PluginCommand admin = getCommand("generatoradmin");
        if (admin != null) {
            GeneratorAdminCommand handler = new GeneratorAdminCommand(this);
            admin.setExecutor(handler);
            admin.setTabCompleter(handler);
        }
        this.ticker = new GeneratorTicker(this);
        this.ticker.start();
        registerPlaceholders();
        getLogger().info("CHRDK_Generator включён: ядро, камера, производство (п.31).");
    }

    /**
     * PlaceholderAPI — мягкая зависимость: без плагина аддон работает как
     * обычно, а с ним отдаёт %chrdkgenerator_tier% и соседние значения.
     */
    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            this.placeholders = new GeneratorPlaceholders(this);
            this.placeholders.register();
            getLogger().info("CHRDK_Generator: PlaceholderAPI — доступны %chrdkgenerator_tier|fill|rate|status%.");
        } catch (RuntimeException | LinkageError error) {
            getLogger().warning("CHRDK_Generator: PlaceholderAPI не подключился — " + error.getMessage());
            if (this.placeholders != null) {
                try {
                    this.placeholders.unregister();
                } catch (RuntimeException ignored) {
                    // Регистрация не удалась — снимать нечего.
                }
                this.placeholders = null;
            }
        }
    }

    /** Рецепт ядра на верстаке; результат — шаблон, идентификатор штампует крафт. */
    private void registerCraft() {
        GeneratorConfig.Craft craft = config.craft();
        if (!craft.enabled()) {
            return;
        }
        ItemStack template = coreItem.build(null);
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, template);
        recipe.shape(craft.shape().toArray(new String[0]));
        for (var row : craft.ingredients().entrySet()) {
            recipe.setIngredient(row.getKey(), org.bukkit.Material.matchMaterial(row.getValue()));
        }
        Bukkit.addRecipe(recipe);
    }

    @Override
    public void onDisable() {
        if (this.ticker != null) {
            this.ticker.stop();
            this.ticker = null;
        }
        if (this.placeholders != null) {
            try {
                this.placeholders.unregister();
            } catch (RuntimeException ignored) {
                // Пока выключаемся — не мешаем остановке.
            }
            this.placeholders = null;
        }
        if (this.moneyLoot != null) {
            this.moneyLoot.close();
            this.moneyLoot = null;
        }
        if (this.store != null) {
            this.store.saveNow();
            this.store = null;
        }
        this.messages = null;
        this.coreApi = null;
        this.config = null;
        this.coreItem = null;
        this.recipeKey = null;
        this.payments = null;
        this.upgrades = null;
        this.worldListener = null;
        this.gui = null;
        this.take = null;
    }

    public Messages messages() {
        return messages;
    }

    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    public GeneratorStore store() {
        return store;
    }

    public GeneratorConfig config() {
        return config;
    }

    /**
     * Владелец у установленного ядра генератора: тот же чанк или в радиусе
     * {@code generator.menu-range} блоков. Без установленного ядра (запись
     * снята) управление не привязано к месту — гейт не действует.
     */
    public boolean nearCore(Player player, GeneratorStore.Entry entry) {
        if (entry == null || !entry.installed()) {
            return false;
        }
        if (!player.getWorld().getName().equals(entry.world())) {
            return false;
        }
        var location = player.getLocation();
        return GeneratorMath.withinMenuRange(location.getBlockX() >> 4, location.getBlockZ() >> 4,
                location.getX(), location.getY(), location.getZ(),
                entry.x(), entry.y(), entry.z(), config.menuRange());
    }

    /**
     * Цель команд и кнопок меню: ближайшее установленное ядро игрока в
     * радиусе доступа; {@code null} — рядом ничего нет.
     */
    public GeneratorStore.Entry nearbyEntry(Player player) {
        GeneratorStore.Entry best = null;
        double bestDistance = Double.MAX_VALUE;
        var location = player.getLocation();
        for (GeneratorStore.Entry entry : store.entries(player.getUniqueId())) {
            if (!entry.installed() || !nearCore(player, entry)) {
                continue;
            }
            double dx = location.getX() - (entry.x() + 0.5);
            double dy = location.getY() - (entry.y() + 0.5);
            double dz = location.getZ() - (entry.z() + 0.5);
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry;
            }
        }
        return best;
    }

    /** Доступные игроку слоты генераторов: базовый + перерождение + платный ранг. */
    public int maxSlots(Player player) {
        var primary = store.entry(player.getUniqueId());
        int rebirths = primary == null ? 0 : primary.rebirths();
        int rankStep = coreApi == null ? 0 : coreApi.rankLevel(player.getName());
        return GeneratorSlots.maxSlots(rebirths, rankStep,
                config.slotsRebirthUnlockAt(), config.slotsPaidRankMinStep());
    }

    public GeneratorCoreItem coreItem() {
        return coreItem;
    }

    public NamespacedKey recipeKey() {
        return recipeKey;
    }

    public MoneyLoot moneyLoot() {
        return moneyLoot;
    }

    public PaidActions payments() {
        return payments;
    }

    public GeneratorUpgrades upgrades() {
        return upgrades;
    }

    public GeneratorWorldListener worldListener() {
        return worldListener;
    }

    public GeneratorGui gui() {
        return gui;
    }

    public GeneratorTake take() {
        return take;
    }

    public GeneratorHolograms holograms() {
        return holograms;
    }

    public GeneratorBunkerNotifier notifier() {
        return notifier;
    }

    public GeneratorAutoSell autoSell() {
        return autoSell;
    }

    /** Кэш состояния камеры: тикер пишет, меню и PAPI читают без чтений мира. */
    public void publishState(UUID owner, String state) {
        publishState(owner, 0, state);
    }

    public void publishState(UUID owner, int slot, String state) {
        states.put(owner + ":" + slot, state);
    }

    public String stateOf(UUID owner) {
        return stateOf(owner, 0);
    }

    public String stateOf(UUID owner, int slot) {
        return states.getOrDefault(owner + ":" + slot, "state-unloaded");
    }
}
