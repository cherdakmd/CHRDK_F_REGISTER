package ru.cherdakmd.chrdkbp;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CHRDK_BattlePass — сезон с недельными волнами заданий и двумя ветками наград
 * (бесплатная + VIP для владельцев проходки). Награды — дни проходки через API ядра.
 */
public final class BattlePassPlugin extends JavaPlugin implements Listener {

    private volatile Messages messages;
    private volatile BattlePassConfig.Season season;
    private volatile ChrdkRegisterApi coreApi;
    private BattlePassStore store;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.store = new BattlePassStore(getDataFolder().toPath(), getLogger());
        store.load();
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        reloadBattlePassConfig();

        Bukkit.getPluginManager().registerEvents(this, this);

        PluginCommand command = getCommand("battlepass");
        if (command != null) {
            BattlePassCommandHandler handler = new BattlePassCommandHandler(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        PluginCommand admin = getCommand("bpadmin");
        if (admin != null) {
            BattlePassAdminCommandHandler handler = new BattlePassAdminCommandHandler(this);
            admin.setExecutor(handler);
            admin.setTabCompleter(handler);
        }
        getLogger().info("CHRDK_BattlePass включён: волн " + season.weeks().size() + ".");
    }

    @Override
    public void onDisable() {
        if (store != null) {
            store.save();
        }
        getLogger().info("CHRDK_BattlePass выключен.");
    }

    public void reloadBattlePassConfig() {
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());

        List<Map<?, ?>> rawWeeks = new ArrayList<>();
        if (getConfig().getList("battlepass.weeks") != null) {
            for (Object entry : getConfig().getList("battlepass.weeks", List.of())) {
                if (entry instanceof Map<?, ?> map) {
                    rawWeeks.add(map);
                }
            }
        }
        List<BattlePassConfig.WeekDef> weeks;
        try {
            weeks = BattlePassConfig.parseWeeks(rawWeeks);
        } catch (IllegalStateException exception) {
            getLogger().severe("[CHRDK_BattlePass] Ошибка конфига: " + exception.getMessage());
            weeks = List.of();
        }
        try {
            this.season = BattlePassConfig.season(
                    getConfig().getString("battlepass.season-name", "Сезон"),
                    getConfig().getString("battlepass.season-start", "2026-01-01"),
                    weeks);
        } catch (IllegalStateException exception) {
            getLogger().severe("[CHRDK_BattlePass] " + exception.getMessage());
            this.season = new BattlePassConfig.Season("Сезон", 0L, weeks);
        }
    }

    // ----------------------------------------------------------- слушатели --

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        recordProgress(event.getPlayer(), BattlePassConfig.MissionType.MINE,
                event.getBlock().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        recordProgress(killer, BattlePassConfig.MissionType.KILL,
                event.getEntityType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int amount = event.getRecipe().getResult().getAmount();
        recordProgress(player, BattlePassConfig.MissionType.CRAFT,
                event.getRecipe().getResult().getType().name(), amount);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        Entity caught = event.getCaught();
        String key = caught instanceof Item item
                ? item.getItemStack().getType().name()
                : "any";
        recordProgress(event.getPlayer(), BattlePassConfig.MissionType.FISH, key, 1);
    }

    /** Прогресс по всем подходящим заданиям открытых волн. Возвращает первое завершённое. */
    List<BattlePassConfig.MissionDef> recordProgress(Player player,
                                                     BattlePassConfig.MissionType type,
                                                     String actualKey, int delta) {
        List<BattlePassConfig.MissionDef> completedNow = new ArrayList<>();
        BattlePassConfig.Season current = season;
        if (current == null || current.empty()) {
            return completedNow;
        }
        long now = System.currentTimeMillis();
        for (int weekIndex = 0; weekIndex < current.weeks().size(); weekIndex++) {
            if (!BattlePassConfig.isUnlocked(current, weekIndex, now)) {
                break; // волны упорядочены по unlock-day
            }
            for (BattlePassConfig.MissionDef mission : current.weeks().get(weekIndex).missions()) {
                if (mission.type() != type
                        || !BattlePassConfig.matchesTypeKey(mission, actualKey)) {
                    continue;
                }
                int before = store.progressOf(player.getUniqueId(), mission.id());
                if (before >= mission.target()) {
                    continue; // уже выполнено — зачем дёргать диск
                }
                int after = store.addProgress(player.getUniqueId(), mission.id(),
                        delta, mission.target());
                if (before < mission.target() && after >= mission.target()) {
                    completedNow.add(mission);
                    messages().send(player, "mission-done",
                            "desc", mission.description(), "id", mission.id(), "label", "bp");
                }
            }
        }
        return completedNow;
    }

    public Messages messages() {
        return messages;
    }

    public BattlePassConfig.Season season() {
        return season;
    }

    public BattlePassStore store() {
        return store;
    }

    ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    static String shortLabel() {
        return "bp";
    }

    static String normId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
