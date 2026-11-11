package ru.cherdakmd.chrdkregister;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.api.ChrdkArtifactsApi;
import ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Случайный стартовый комплект новичка: броня, инструменты, смешанные
 * артефакты, 1000 алмазов на счёт /bal и 32 физических алмаза в инвентарь.
 *
 * <p>Выдаётся <b>один раз</b> при первом входе после завершения авторизации
 * ({@link PlayerAuthCompletedEvent}), до этого игрок заморожен системой входа.
 * Игравшие до обновления комплект не получают: это стартовый подарок, а не
 * раздача алмазов всему серверу. Факт выдачи хранится по UUID в
 * {@code starter-kits.json} и переживает рестарт и смену ника.</p>
 *
 * <p>Физические алмазы кладутся напрямую в инвентарь, минуя
 * {@code DiamondDrops}/{@code MoneyLoot}: это не добыча, не событиеSkills и
 * не расход дневного лимита {@code dailyCap}. Зачисление на счёт идёт через
 * обычный {@code BankStore.deposit}, то есть видимо в /bal и в bank.json.</p>
 *
 * <p>Настоящие артефакты берутся из каталога CHRDK_Artifacts через
 * {@link ChrdkArtifactsApi} (ServicesManager, без жёсткой зависимости). Если
 * аддон отсутствует или ид выключен, ванильная часть набора закрывает бросок
 * целиком — плагин от этого не падает.</p>
 */
public final class StarterKitService implements Listener {

    private final RegisterPlugin plugin;
    private final StarterKitStore store;
    private volatile StarterKitSettings settings;
    private final Random random = new Random();

    public StarterKitService(RegisterPlugin plugin, StarterKitStore store,
                             StarterKitSettings settings) {
        this.plugin = plugin;
        this.store = store;
        this.settings = settings == null ? StarterKitSettings.defaults() : settings;
    }

    /** Загрузка памяти и сводка в лог (вызывается из onEnable). */
    public void start() {
        store.load();
        StarterKitSettings current = settings;
        logger().info("[starter-kit] комплект новичка: " + (current.enabled() ? "включён" : "выключен")
                + ", /bal: " + current.bankDiamonds()
                + ", физических алмазов: " + current.inventoryDiamonds()
                + ", уже получили: " + store.givenCount());
    }

    /** /pass reload: новые значения без пересоздания слушателя. */
    public void reload(StarterKitSettings settings) {
        this.settings = settings == null ? StarterKitSettings.defaults() : settings;
    }

    public StarterKitSettings settings() {
        return settings;
    }

    public StarterKitStore store() {
        return store;
    }

    public void shutdown() {
        store.saveNow();
    }

    /* ------------------------------- события ------------------------------- */

    /** Запоминаем новичка даже если он вышел до регистрации. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String key = StarterKitStore.key(player.getUniqueId());
        if (store.isGiven(key) || store.isPending(key)) {
            return;
        }
        if (player.hasPlayedBefore()) {
            store.markGiven(key);
        } else {
            store.markPending(key);
        }
    }

    /** Авторизация завершена — можно выдавать предметы и деньги. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthCompleted(PlayerAuthCompletedEvent event) {
        Player player = event.getPlayer();
        String key = StarterKitStore.key(player.getUniqueId());
        if (!store.isPending(key)) {
            return;
        }
        // Сначала помечаем: если сервер упадёт между записью и выдачей, комплект
        // не появится дважды. Потеря одного подарка безопаснее дубля алмазов.
        store.markGiven(key);
        StarterKitSettings current = settings;
        if (!current.enabled()) {
            logger().info("[starter-kit] комплект отключён в config.yml, выдача пропущена: "
                    + player.getName());
            return;
        }
        grant(player, current);
    }

    /* ------------------------------- выдача ------------------------------- */

    private boolean grant(Player player, StarterKitSettings config) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        StarterKitPlan plan = StarterKitPlan.roll(config, random, artifactIds(config));
        if (plan.bankDiamonds() > 0) {
            long award=plugin.bankStore().diamondRate().scaleMinor(plan.bankDiamonds()*100L);
            long balance = plugin.bankStore().depositMinor(player.getUniqueId(),player.getName(),award,true);
            if (balance < 0) {
                logger().warning("[starter-kit] банк недоступен или счёт переполнен: "
                        + player.getName() + " не получил " + plan.bankDiamonds() + " на /bal");
            }
        }

        List<ItemStack> items = new ArrayList<>();
        for (String material : plan.armor()) {
            add(items, vanilla(material));
        }
        for (String material : plan.tools()) {
            add(items, vanilla(material));
        }
        for (String material : plan.vanityItems()) {
            add(items, vanilla(material));
        }
        ChrdkArtifactsApi api = artifacts();
        for (String id : plan.artifactIds()) {
            ItemStack artifact = null;
            try {
                artifact = api == null ? null : api.create(id, 1);
            } catch (RuntimeException | LinkageError error) {
                logger().warning("[starter-kit] аддон артефактов не отдал " + id + ": " + error);
            }
            add(items, artifact);
        }
        if (plan.inventoryDiamonds() > 0) {
            items.add(new ItemStack(Material.DIAMOND, plan.inventoryDiamonds()));
        }

        Map<Integer, ItemStack> leftover =
                player.getInventory().addItem(items.toArray(ItemStack[]::new));
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
        plugin.messages().send(player, "starter-kit.granted",
                "balance", plan.bankDiamonds(), "diamonds", plan.inventoryDiamonds());
        logger().info("[starter-kit] выдан комплект игроку " + player.getName()
                + ": стаков " + plan.itemStacks() + ", артефактов из каталога "
                + plan.artifactIds().size() + ", /bal +" + plan.bankDiamonds()
                + ", алмазов в инвентарь " + plan.inventoryDiamonds());
        return true;
    }

    private static void add(List<ItemStack> items, ItemStack item) {
        if (item != null && !item.getType().isAir()) {
            items.add(item);
        }
    }

    private static ItemStack vanilla(String materialName) {
        Material material = Material.getMaterial(materialName);
        return material == null || !material.isItem() ? null : new ItemStack(material);
    }

    /** Опциональный сервис: без CHRDK_Artifacts возвращаем пустой список. */
    private ChrdkArtifactsApi artifacts() {
        try {
            return Bukkit.getServicesManager().load(ChrdkArtifactsApi.class);
        } catch (RuntimeException | LinkageError error) {
            logger().warning("[starter-kit] сервис артефактов недоступен: " + error);
            return null;
        }
    }

    private List<String> artifactIds(StarterKitSettings config) {
        ChrdkArtifactsApi api = artifacts();
        if (api == null) {
            return List.of();
        }
        try {
            List<String> ids = config.includeCursedArtifacts() ? api.ids() : api.ids(false);
            return ids == null ? List.of() : ids;
        } catch (RuntimeException | LinkageError error) {
            logger().warning("[starter-kit] каталог артефактов недоступен: " + error);
            return List.of();
        }
    }

    private Logger logger() {
        return plugin.getLogger();
    }
}
