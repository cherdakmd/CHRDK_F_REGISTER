package ru.cherdakmd.chrdkevents;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CHRDK_Events — мировые события: таймер, объявления и мягкие бонусы на время
 * (1.4.160).
 *
 * <p>Раз в 10–30 минут (настраивается) сервер объявляет событие: надбавку к
 * опыту, лишний кусок руды, лишний дроп с мобов или общие эффекты. Событие
 * висит от 30 секунд до получаса, отсчитывается в bossbar и заканчивается
 * само.</p>
 *
 * <p>Это не «ивент-плагин» с аренами и боссами: событие должно собирать людей
 * в мир, а не уводить их с него. Поэтому все бонусы — надбавки к обычным
 * действиям и ни один не выдаёт монеты, алмазы или силу напрямую.</p>
 */
public final class EventsPlugin extends JavaPlugin {

    private EventConfig config;
    private Messages messages;
    private EventService service;
    private MeteorService meteors;
    private HordeService horde;
    private EventsStore store;
    private BossBar bar;
    private BukkitTask tickTask;
    private BukkitTask saveTask;
    private BukkitTask queuedSave;
    private boolean saveQueued;
    private ChrdkRegisterApi coreApi;
    private ru.cherdakmd.chrdkregister.api.MoneyLoot moneyLoot;
    public ru.cherdakmd.chrdkregister.api.MoneyLoot moneyLoot(){return moneyLoot;}
    private final Random random = new Random();
    /** Эффекты, о неизвестности которых уже предупредили: не спамим в лог. */
    private final Set<String> warnedEffects = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            applyBundledDefaults();
            this.config = EventConfig.parse(getConfig(), getLogger());
            this.messages = Messages.load(this, config.locale(), getLogger());
            this.store = new EventsStore(getDataFolder().toPath(), getLogger());
            store.load();
            this.service = new EventService(config, random);
            this.meteors = new MeteorService(this);
            this.horde = new HordeService(this);
            service.restoreSchedule(store.nextAt(), store.cooldowns(), System.currentTimeMillis());
            getServer().getPluginManager().registerEvents(new EventsListener(this), this);
            registerCommands();
            registerPlaceholders();
            this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
            moneyLoot=new ru.cherdakmd.chrdkregister.api.MoneyLoot(this,()->coreApi,this::messages,"events-loot");
            if (coreApi != null) {
                try {
                    coreApi.registerProfile("events", new EventsProfile(this));
                } catch (RuntimeException | LinkageError error) {
                    getLogger().warning("[CHRDK_Events] раздел профиля недоступен "
                            + "(нужно ядро 1.4.156+): " + error);
                }
            }
            this.tickTask = getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
            this.saveTask = getServer().getScheduler().runTaskTimerAsynchronously(this,
                    this::saveNow, 200L, 200L);
            getLogger().info("CHRDK_Events включён: событий " + config.events().size()
                    + ", пауза " + config.minDelaySeconds() + "–" + config.maxDelaySeconds()
                    + " с, порог онлайна " + config.minPlayers()
                    + ", в истории " + store.totalAll() + ".");
        } catch (RuntimeException exception) {
            getLogger().severe("CHRDK_Events не смог запуститься: " + exception.getMessage());
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if(moneyLoot!=null)moneyLoot.close();
        if (coreApi != null) {
            try {
                coreApi.unregisterProfile("events");
            } catch (RuntimeException | LinkageError ignored) {
                // Ядро старше API v8 — снимать нечего.
            }
            this.coreApi = null;
        }
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (queuedSave != null) {
            queuedSave.cancel();
            queuedSave = null;
        }
        hideBar();
        stopSupports();
        if (store != null && service != null) {
            long now = System.currentTimeMillis();
            // Событие, которое шло в момент выключения, в историю закрывается,
            // но эффекты и bossbar после старта не продолжаются (см. EventsStore).
            WorldEvent running = service.stop(now);
            if (running != null) {
                store.recordStop(running, now);
            }
            store.setCooldowns(service.cooldowns());
            store.setNextAt(service.nextAt());
            store.closeOpen(now);
            store.flush();
        }
        getLogger().info("CHRDK_Events выключен.");
    }

    /** Перечитывает конфиг и тексты; текущее событие не обрывается. */
    public void reloadEvents() {
        reloadConfig();
        applyBundledDefaults();
        this.config = EventConfig.parse(getConfig(), getLogger());
        this.messages = Messages.load(this, config.locale(), getLogger());
        service.applyConfig(config);
        if (!config.enabled() && service.active()) {
            WorldEvent stopped = service.stop(System.currentTimeMillis());
            if (stopped != null) {
                announceEnd(stopped);
                store.recordStop(stopped, System.currentTimeMillis());
                requestSave();
            }
            hideBar();
            stopSupports();
        }
        if (!config.enabled() || config.events().isEmpty()) {
            hideBar();
        }
    }

    /** Секции, появившиеся в 1.4.176: их и подставляем в старый config.yml. */
    private static final List<String[]> NEW_SECTIONS = List.of(
            new String[]{"starfall", "meteors"},
            new String[]{"meteor_shower", "meteors"},
            new String[]{"blood_moon", "horde"});

    /**
     * Дополняет конфиг новыми секциями из ресурса, не перезаписывая файл.
     *
     * <p>С 1.4.176 у событий появились секции {@code meteors} и {@code horde}.
     * Если на сервере лежит config.yml с прошлой версии, без этого приёма
     * звёзды и нашествие не включились бы до ручной правки файла — «Звездопад»
     * снова остался бы только надбавкой к руде.</p>
     *
     * <p>Копируются ровно три секции и только если само событие у владельца
     * есть: удалённое из конфига событие не воскрешается, а комментарии и
     * значения файла остаются нетронутыми — мы правим конфиг в памяти.</p>
     */
    private void applyBundledDefaults() {
        try (InputStream stream = getResource("config.yml")) {
            if (stream == null) {
                return;
            }
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (String[] section : NEW_SECTIONS) {
                copySection(bundled, section[0], section[1]);
            }
        } catch (IOException | RuntimeException error) {
            getLogger().warning("[CHRDK_Events] Не удалось дополнить config.yml значениями "
                    + "по умолчанию: " + error);
        }
    }

    /** Копирует {@code events.<id>.<section>} из ресурса, если её нет у владельца. */
    private void copySection(YamlConfiguration bundled, String eventId, String section) {
        String path = "events." + eventId + "." + section;
        if (!getConfig().isConfigurationSection("events." + eventId) || getConfig().isSet(path)) {
            return;
        }
        ConfigurationSection source = bundled.getConfigurationSection(path);
        if (source == null) {
            return;
        }
        getConfig().createSection(path);
        for (String key : source.getKeys(true)) {
            if (!source.isConfigurationSection(key)) {
                getConfig().set(path + "." + key, source.get(key));
            }
        }
        getLogger().info("[CHRDK_Events] config.yml: секция " + path
                + " взята из ресурса (файл не изменён).");
    }

    private void registerCommands() {
        EventsCommandHandler handler = new EventsCommandHandler(this);
        for (String name : new String[]{"events", "eventsadmin"}) {
            PluginCommand command = getCommand(name);
            if (command == null) {
                getLogger().warning("Команда " + name + " не объявлена в plugin.yml — модуль без неё.");
                continue;
            }
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
    }

    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            new EventsPlaceholders(this).register();
            getLogger().info("PlaceholderAPI: доступны %chrdkevents_...%.");
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось зарегистрировать PlaceholderAPI: "
                    + exception.getMessage());
        }
    }

    /* ---------------------------------- таймер --------------------------------- */

    /** Один шаг в секунду: закрыть истёкшее событие, начать следующее, обновить bar. */
    private void tick() {
        long now = System.currentTimeMillis();
        WorldEvent ended = service.tick(now);
        if (ended != null) {
            announceEnd(ended);
            hideBar();
            stopSupports();
            store.recordStop(ended, now);
            store.setCooldowns(service.cooldowns());
            store.setNextAt(service.nextAt());
            requestSave();
        }
        int online = Bukkit.getOnlinePlayers().size();
        WorldEvent started = service.tryStart(now, online);
        if (started != null) {
            startEvent(started, now, false);
        } else if (service.active()) {
            updateBar(now);
        }
    }

    /** Запускает событие по иду вручную — объявление, bossbar и запись включаются. */
    WorldEvent startById(String id) {
        long now = System.currentTimeMillis();
        WorldEvent started = service.start(id, now);
        if (started != null) {
            startEvent(started, now, true);
        }
        return started;
    }

    /** Запускает случайное событие вручную, минуя паузы (для админа). */
    WorldEvent forceRandom() {
        if (service.active() || config.events().isEmpty()) {
            return null;
        }
        WorldEvent picked = EventPicker.pick(config.events(), Map.of(),
                System.currentTimeMillis(), random);
        return picked == null ? null : startById(picked.id());
    }

    /** Останавливает текущее событие; {@code null}, если его нет. */
    WorldEvent stopCurrent() {
        long now = System.currentTimeMillis();
        WorldEvent stopped = service.stop(now);
        if (stopped == null) {
            return null;
        }
        announceEnd(stopped);
        hideBar();
        stopSupports();
        store.recordStop(stopped, now);
        store.setCooldowns(service.cooldowns());
        store.setNextAt(service.nextAt());
        requestSave();
        return stopped;
    }

    private void startEvent(WorldEvent event, long now, boolean forced) {
        announceStart(event);
        showBar(event);
        applyEffectsToAll(event, now);
        startSupports(event);
        store.recordStart(event, now, forced);
        store.setNextAt(service.nextAt());
        requestSave();
    }

    /**
     * Включает «физическую» половину события (1.4.176): падающие звёзды и
     * нашествие мобов. У события без этих секций сервисы просто не запускаются.
     */
    private void startSupports(WorldEvent event) {
        if (meteors != null) {
            meteors.start(event);
        }
        if (horde != null) {
            horde.start(event);
        }
    }

    /** Гасит звёзды и волны: событие закончилось или плагин выключается. */
    private void stopSupports() {
        if (meteors != null) {
            meteors.stop();
        }
        if (horde != null) {
            horde.stop();
        }
    }

    /** Помечает данные изменёнными: запись уйдёт через две секунды, вне главного потока. */
    void requestSave() {
        if (saveQueued || !isEnabled()) {
            return;
        }
        saveQueued = true;
        this.queuedSave = getServer().getScheduler().runTaskLaterAsynchronously(this, () -> {
            saveQueued = false;
            queuedSave = null;
            saveNow();
        }, 40L);
    }

    private void saveNow() {
        if (store != null) {
            try {
                if (store.isDirty()) {
                    store.saveNow();
                }
            } catch (RuntimeException error) {
                getLogger().warning("Не удалось сохранить events.json: " + error.getMessage());
            }
        }
    }

    /* ------------------------------ объявления и bar --------------------------- */

    private void announceStart(WorldEvent event) {
        Component message = messages.get("start-broadcast",
                "name", event.name(),
                "description", event.description(),
                "time", EventText.duration(event.durationSeconds()));
        getServer().broadcast(message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.1f);
        }
    }

    private void announceEnd(WorldEvent event) {
        getServer().broadcast(messages.get("end-broadcast", "name", event.name()));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 0.8f);
        }
    }

    private void showBar(WorldEvent event) {
        hideBar();
        if (!config.bossbar()) {
            return;
        }
        this.bar = BossBar.bossBar(messages.get("bossbar",
                        "name", event.name(),
                        "time", EventText.clock(event.durationSeconds())),
                1.0f, colorFor(event.kind()), BossBar.Overlay.PROGRESS);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showBossBar(bar);
        }
    }

    private void updateBar(long now) {
        WorldEvent event = service.current();
        if (event == null || !config.bossbar()) {
            return;
        }
        if (bar == null) {
            showBar(event);
            return;
        }
        long left = service.secondsLeft(now);
        float progress = Math.clamp(left / (float) Math.max(1, event.durationSeconds()),
                0.0f, 1.0f);
        bar.progress(progress);
        bar.name(messages.get("bossbar",
                "name", event.name(),
                "time", EventText.clock(left)));
    }

    private void hideBar() {
        if (bar == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(bar);
        }
        this.bar = null;
    }

    private static BossBar.Color colorFor(EventKind kind) {
        return switch (kind) {
            case XP -> BossBar.Color.GREEN;
            case MINING -> BossBar.Color.YELLOW;
            case LOOT -> BossBar.Color.RED;
            case EFFECT -> BossBar.Color.PURPLE;
        };
    }

    /* --------------------------------- эффекты --------------------------------- */

    private void applyEffectsToAll(WorldEvent event, long now) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyEffects(player, event, now);
        }
    }

    /** Выдаёт эффекты события; длительность не выходит за остаток события. */
    void applyEffects(Player player, WorldEvent event, long now) {
        if (player == null || event == null || event.kind() != EventKind.EFFECT) {
            return;
        }
        long left = service.secondsLeft(now);
        if (left <= 0L) {
            return;
        }
        for (WorldEvent.Effect effect : event.effects()) {
            PotionEffectType type = effectType(effect.type());
            if (type == null) {
                continue;
            }
            int seconds = (int) Math.min(effect.seconds(), left);
            player.addPotionEffect(new PotionEffect(type, seconds * 20, effect.amplifier(),
                    true, false, true));
        }
    }

    /** Эффект по иду Minecraft; {@code null}, если реестр такого не знает. */
    PotionEffectType effectType(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String key = id.trim().toLowerCase(Locale.ROOT);
        try {
            PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(key));
            if (type == null && warnedEffects.add(key)) {
                getLogger().warning("[CHRDK_Events] Неизвестный эффект «" + key
                        + "» — пропущен. Проверьте events.*.effects в config.yml.");
            }
            return type;
        } catch (RuntimeException | LinkageError error) {
            if (warnedEffects.add(key)) {
                getLogger().warning("[CHRDK_Events] Реестр эффектов недоступен: " + error);
            }
            return null;
        }
    }

    /* ---------------------------------- игроки --------------------------------- */

    void handleJoin(Player player) {
        if (player == null || !service.active()) {
            return;
        }
        WorldEvent event = service.current();
        player.sendMessage(messages.get("join-active",
                "name", event.name(),
                "time", EventText.clock(service.secondsLeft(System.currentTimeMillis()))));
        if (bar != null) {
            player.showBossBar(bar);
        }
        applyEffects(player, event, System.currentTimeMillis());
    }

    void handleQuit(Player player) {
        if (player != null && bar != null) {
            player.hideBossBar(bar);
        }
    }

    /* ---------------------------------- доступ --------------------------------- */

    public EventConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public EventService service() {
        return service;
    }

    /** Сервис падающих звёзд (1.4.176). */
    MeteorService meteors() {
        return meteors;
    }

    /** Сервис нашествия мобов (1.4.176). */
    HordeService horde() {
        return horde;
    }

    EventsStore store() {
        return store;
    }
}
