package ru.cherdakmd.chrdkpets;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.UUID;

/**
 * CHRDK_Pets — кошки и волки, которых игрок уже приручил, получают уровни,
 * клички и бонусы. Новых сущностей модуль не спавнит: он работает с ванильным
 * поголовьем и не мешает ни клаймам, ни квестам.
 *
 * <p>Прогресс живёт в {@code pets.json} по UUID сущности, уровень выводится из
 * опыта. Бонусы питомца — базовое здоровье и транзитные модификаторы урона и
 * скорости; аура хозяина пересчитывается пульсом раз в
 * {@code general.refresh-ticks} тиков и снимается, как только питомец ушёл,
 * погиб или модуль выключили.</p>
 */
public final class PetsPlugin extends JavaPlugin {

    private volatile Messages messages;
    private volatile PetsConfig config;
    private PetStore store;
    private PetService service;
    private PetAura aura;
    private BukkitTask auraTask;
    private BukkitTask saveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.store = new PetStore(getDataFolder().toPath(), getLogger());
        this.store.load();
        reloadPetsConfig();

        int purged = store.purgeDead(config.keepDeadDays(), System.currentTimeMillis());
        if (purged > 0) {
            getLogger().info("Удалено записей о погибших питомцах: " + purged + ".");
        }

        this.service = new PetService(this);
        this.aura = new PetAura(this);

        Bukkit.getPluginManager().registerEvents(new PetListener(this), this);
        PetCommandHandler commands = new PetCommandHandler(this);
        PluginCommand pet = getCommand("pet");
        PluginCommand admin = getCommand("petsadmin");
        if (pet != null) {
            pet.setExecutor(commands);
            pet.setTabCompleter(commands);
        }
        if (admin != null) {
            admin.setExecutor(commands);
            admin.setTabCompleter(commands);
        }

        registerApi();
        this.saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                this::saveState, config.saveIntervalSeconds() * 20L, config.saveIntervalSeconds() * 20L);
        this.auraTask = Bukkit.getScheduler().runTaskTimer(this, this::refreshAuras,
                config.refreshTicks(), config.refreshTicks());

        service.adoptLoaded();
        for (Player player : Bukkit.getOnlinePlayers()) {
            aura.refresh(player);
        }

        getLogger().info("CHRDK_Pets включён: питомцев " + store.size()
                + " (живых " + store.aliveCount() + "), видов "
                + (config.species(PetSpecies.WOLF).enabled() ? "волк " : "")
                + (config.species(PetSpecies.CAT).enabled() ? "кошка" : ""));
    }

    @Override
    public void onDisable() {
        if (auraTask != null) {
            auraTask.cancel();
            auraTask = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (aura != null) {
            aura.clearAll();
        }
        if (store != null) {
            store.flush();
        }
        getLogger().info("CHRDK_Pets выключен.");
    }

    /** Перечитывает config.yml/messages.yml и переставляет бонусы загруженным питомцам. */
    public void reloadPetsConfig() {
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"),
                getLogger());
        this.config = PetsConfig.parse(getConfig(), getLogger());
        if (service != null) {
            service.adoptLoaded();
            if (aura != null) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    aura.refresh(player);
                }
            }
        }
    }

    private void refreshAuras() {
        if (!config.enabled()) {
            aura.clearAll();
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                aura.refresh(player);
            } catch (RuntimeException exception) {
                getLogger().warning("Не удалось обновить ауру " + player.getName()
                        + ": " + exception.getMessage());
            }
        }
    }

    private void saveState() {
        try {
            if (store.isDirty()) {
                store.saveNow();
            }
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось сохранить питомцев: " + exception.getMessage());
        }
    }

    /** Регистрация публичного API: другие аддоны видят только интерфейс. */
    public void registerApi() {
        Bukkit.getServicesManager().register(PetsApi.class,
                new PetsApiImpl(this), this, ServicePriority.Normal);
    }

    public Messages messages() {
        return messages;
    }

    public PetsConfig config() {
        return config;
    }

    public PetStore store() {
        return store;
    }

    public PetService service() {
        return service;
    }

    public PetAura aura() {
        return aura;
    }

    /** Уровень питомца по UUID — для API (без Bukkit-сущности). */
    int levelOf(UUID petId) {
        PetStore.PetData data = store.get(petId);
        return data == null ? 0 : PetCurve.levelFor(data.xp(), config.curve());
    }
}
