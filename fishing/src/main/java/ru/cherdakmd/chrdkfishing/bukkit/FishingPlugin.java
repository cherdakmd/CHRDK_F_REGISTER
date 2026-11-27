package ru.cherdakmd.chrdkfishing.bukkit;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Random;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkfishing.FishSigningKey;
import ru.cherdakmd.chrdkfishing.FishAlbum;
import ru.cherdakmd.chrdkskills.ChrdkSkillsApi;

/** Prototype bootstrap. It is not yet the complete fishing product. */
public final class FishingPlugin extends JavaPlugin {
    private FishAlbum album;
    private AlbumRewards albumRewards;
    private FishSaleEngine sale;
    private RodUpgradeEngine rods;
    private BaitPurchaseEngine bait;
    @Override public void onEnable() {
        try {
            var core = getServer().getPluginManager().getPlugin("CHRDK_Core");
            if (core == null || !core.isEnabled()) throw new IllegalStateException("CHRDK_Core unavailable");
            Class<?> api = Class.forName("ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi", true, core.getClass().getClassLoader());
            var services = getServer().getServicesManager();
            var auth = new CoreAuthentication(services, api);
            var skills = services.load(ChrdkSkillsApi.class);
            if (!auth.available() || skills == null) throw new IllegalStateException("Core/Skills API unavailable");
            Path folder = getDataFolder().toPath();
            var generation = FishingGeneration.resolve(folder);
            generation.verifyCore(core.getClass().getClassLoader());
            boolean resetBootstrapping = generation.bootstrapping(folder);
            byte[] key = generation.signingKey(folder);
            try {
                saveDefaultConfig();
                ru.cherdakmd.chrdkregister.api.VisualConfigMigration.apply(this);
                FishingSettings settings = FishingSettings.load(getConfig());
                Class.forName("org.sqlite.JDBC", true, getClass().getClassLoader());
                boolean createAlbum = initialiseAlbumMarker(folder);
                album = new FishAlbum(folder.resolve("fishing-album.db"), createAlbum, key);
                albumRewards = AlbumRewardsSetup.open(folder, services, api, core.getClass().getClassLoader(), album, auth::authenticated, resetBootstrapping, generation.horizons);
                sale = FishSaleSetup.open(folder, services, api, core.getClass().getClassLoader(),
                        new FishItemCodec(key), key, auth::authenticated, generation, getConfig().getBoolean("textures.dish-items", false));
                boolean textures = getConfig().getBoolean("textures.enabled", false);
                var rodCodec = new RodItemCodec(key, textures);
                rods = RodUpgradeSetup.open(folder, services, api, core.getClass().getClassLoader(), rodCodec, key, auth::authenticated, generation);
                var baitCodec = new BaitItemCodec(key, getConfig().getBoolean("textures.bait-items", false));
                bait = BaitPurchaseSetup.open(folder, services, api, core.getClass().getClassLoader(), baitCodec, key, auth::authenticated, generation);
                generation.markInitialized(folder);
                var active = FishingActivation.install(this, getServer().getPluginManager(), services,
                        auth::authenticated, auth::available, Bukkit::isPrimaryThread, skills, key, settings.chance(), new Random(),
                        settings.rules(), settings::context, textures);
                java.util.function.Function<org.bukkit.event.player.PlayerFishEvent, Integer> currentRodLevel = event -> {
                    try {
                        var hand = event.getHand();
                        var inventory = event.getPlayer().getInventory();
                        if (hand == org.bukkit.inventory.EquipmentSlot.HAND) return rods.rodLevel(inventory.getItemInMainHand());
                        if (hand == org.bukkit.inventory.EquipmentSlot.OFF_HAND) return rods.rodLevel(inventory.getItemInOffHand());
                    } catch (Exception error) { getLogger().log(java.util.logging.Level.SEVERE, "Не удалось проверить уровень удочки", error); }
                    return 1;
                };
                var baitSession = new BaitCatchSession(this, bait, active.codec());
                active.listener().useEventBait(baitSession::bait);
                active.listener().useEventRodLevel(currentRodLevel);
                active.provider().useRodXp(event -> ru.cherdakmd.chrdkfishing.FishingModel.rod(currentRodLevel.apply(event)).xpBonusPercent());
                var gui = new FishAlbumGui(album);
                var saleGui = new FishSaleGui(this, sale, active.codec());
                var rodGui = new RodUpgradeGui(this, rods, rodCodec);
                var cookingGui = new FishCookingGui(this, sale, active.codec());
                var mainGui = new FishMainGui(this, auth::authenticated, gui::open, saleGui::open, rodGui::open, cookingGui::open);
                gui.setBack(this, mainGui::open);
                gui.setRewards(albumRewards);
                saleGui.setBack(mainGui::open);
                rodGui.setBack(mainGui::open);
                cookingGui.setBack(mainGui::open);
                var baitShop = new BaitShopGui(this, bait, mainGui::open);
                var baitEquip = new BaitEquipGui(this, bait, baitCodec, mainGui::open);
                mainGui.setBaitSections(baitShop::open, baitEquip::open);
                var orderGui = new FishOrdersGui(this, sale, active.codec(), mainGui::open);
                mainGui.setOrders(orderGui::open);
                getServer().getPluginManager().registerEvents(new FishAlbumListener(this, album, active.codec(), auth::authenticated), this);
                getServer().getPluginManager().registerEvents(new FishFindListener(this, sale, active.codec()), this);
                getServer().getPluginManager().registerEvents(mainGui, this);
                getServer().getPluginManager().registerEvents(baitShop, this);
                getServer().getPluginManager().registerEvents(baitEquip, this);
                getServer().getPluginManager().registerEvents(baitSession, this);
                getServer().getPluginManager().registerEvents(orderGui, this);
                getServer().getPluginManager().registerEvents(gui, this);
                getServer().getPluginManager().registerEvents(saleGui, this);
                getServer().getPluginManager().registerEvents(rodGui, this);
                getServer().getPluginManager().registerEvents(cookingGui, this);
                getServer().getPluginManager().registerEvents(new FishVanillaProtection(active.codec(), baitCodec), this);
                var command = getCommand("fishing");
                if (command == null) throw new IllegalStateException("missing fishing command");
                command.setExecutor((sender, cmd, label, args) -> {
                    if (!(sender instanceof org.bukkit.entity.Player player)) { sender.sendMessage("Только игрок может открыть меню рыбалки."); return true; }
                    if (!auth.authenticated(player)) { player.sendMessage("§cСначала авторизуйтесь."); return true; }
                    try {
                        if (args.length == 0) mainGui.open(player);
                        else if (args.length == 2 && args[0].equalsIgnoreCase("bait") && args[1].equalsIgnoreCase("cancel")) {
                            var result = bait.cancelUnpaid(player);
                            player.sendMessage(result == ru.cherdakmd.chrdkfishing.RodUpgradeJournal.Result.DONE
                                    ? "§aНеоплаченная покупка отменена." : "§cНельзя отменить: операция оплачена или состояние неизвестно.");
                        }
                        else if (args.length == 2 && args[0].equalsIgnoreCase("rod") && args[1].equalsIgnoreCase("check")) {
                            // No item details, signatures, raw snapshots or account amounts are exposed.
                            try {
                                var check = rods.diagnose(player); var inv = check.inventory();
                                player.sendMessage("§eПроверка без оплаты и изменения инвентаря:");
                                player.sendMessage("§7Банк: " + checkText(check.bankAvailable()) + "; журнал: " + checkText(check.journalReady()));
                                player.sendMessage("§7Сверка предметов: " + checkText(inv.semanticMatch()) + "; строгая: " + checkText(inv.exactMatch()));
                                player.sendMessage("§7Стабильность снимка: " + checkText(inv.stableSnapshot()) + "; старая сверка банка: " + checkText(inv.bankHashMatch()));
                                player.sendMessage("§7Новая сверка снимка: " + (check.snapshotChargeSupported() ? "доступна" : "недоступна"));
                                player.sendMessage("§7Это проверка текущего состояния, а не гарантия успешной оплаты.");
                            } catch (Exception error) {
                                player.sendMessage("§cДиагностика недоступна. Убедитесь, что нет ожидающих операций, и обратитесь к администрации.");
                                getLogger().warning("Диагностика удочки недоступна; снимки инвентаря и банковские данные не записаны.");
                            }
                        }
                        else if (args.length == 2 && args[0].equalsIgnoreCase("rod") && args[1].equalsIgnoreCase("cancel")) {
                            var result = rods.cancelUnpaid(player);
                            player.sendMessage(result == ru.cherdakmd.chrdkfishing.RodUpgradeJournal.Result.DONE
                                    ? "§aНеоплаченное улучшение отменено." : "§cНельзя отменить: операция оплачена или состояние неизвестно.");
                        } else if (args.length == 2 && args[0].equalsIgnoreCase("sell") && args[1].equalsIgnoreCase("all")) saleGui.openAll(player);
                        else if (args.length == 1 && args[0].equalsIgnoreCase("sell")) saleGui.open(player);
                        else if (args.length == 1 && args[0].equalsIgnoreCase("album")) gui.open(player);
                        else if (args.length == 1 && args[0].equalsIgnoreCase("rod")) rodGui.open(player);
                        else player.sendMessage("§e/fish album — альбом; /fish sell — продажа; /fish rod — улучшение удочки.");
                    } catch (Exception error) {
                        getLogger().log(java.util.logging.Level.SEVERE, "Меню рыбалки недоступно; сохраните данные плагина и банка", error);
                        player.sendMessage("§cМеню недоступно. Обратитесь к администрации.");
                    }
                    return true;
                });
                // Recovery waits for authorization; it never replays removal without its playerdata nonce.
                var warned = new java.util.HashSet<java.util.UUID>();
                getServer().getScheduler().runTaskTimer(this, () -> {
                    for (var player : getServer().getOnlinePlayers()) {
                        if (!sale.available(player)) continue;
                        try {
                            if (!sale.hasPending(player)) { warned.remove(player.getUniqueId()); continue; }
                            var result = sale.recover(player);
                            if (result == ru.cherdakmd.chrdkfishing.FishSaleJournal.Result.DONE) {
                                warned.remove(player.getUniqueId());
                                player.sendMessage("§aНезавершённая операция с рыбой восстановлена.");
                            } else if (warned.add(player.getUniqueId())) {
                                getLogger().warning("Продажа ожидает восстановления или конфликтует: " + player.getUniqueId() + " " + result);
                                player.sendMessage("§cПродажа рыбы ожидает восстановления. Обратитесь к администрации.");
                            }
                        } catch (Exception error) {
                            if (warned.add(player.getUniqueId())) {
                                getLogger().log(java.util.logging.Level.SEVERE, "Ошибка восстановления продажи " + player.getUniqueId(), error);
                                player.sendMessage("§cНе удалось восстановить продажу. Обратитесь к администрации.");
                            }
                        }
                    }
                }, 100L, 100L);
                var rodWarnings = new java.util.HashSet<java.util.UUID>();
                getServer().getScheduler().runTaskTimer(this, () -> {
                    try {
                        var owner = rods.pendingOwner();
                        if (owner == null) { rodWarnings.clear(); return; }
                        var player = Bukkit.getPlayer(owner);
                        if (!rods.available(player)) return;
                        var result = rods.recover(player);
                        if (result == ru.cherdakmd.chrdkfishing.RodUpgradeJournal.Result.DONE) {
                            rodWarnings.remove(owner); player.sendMessage("§aУлучшение удочки восстановлено.");
                        } else if (rodWarnings.add(owner)) {
                            player.sendMessage("§cУлучшение удочки ожидает восстановления. Если оно не оплачено: /fish rod cancel");
                            getLogger().warning("Ожидает восстановление улучшения удочки " + owner + ": " + result);
                        }
                    } catch (Exception error) {
                        getLogger().log(java.util.logging.Level.SEVERE, "Ошибка восстановления улучшения удочки", error);
                    }
                }, 100L, 100L);
                var baitWarnings = new java.util.HashSet<java.util.UUID>();
                getServer().getScheduler().runTaskTimer(this, () -> {
                    try {
                        var owner = bait.pendingOwner();
                        if (owner != null) {
                            var player = Bukkit.getPlayer(owner);
                            if (bait.available(player)) {
                                var result = bait.recover(player);
                                if (result == ru.cherdakmd.chrdkfishing.RodUpgradeJournal.Result.DONE) {
                                    baitWarnings.remove(owner); player.sendMessage("§aПокупка приманки восстановлена.");
                                } else if (baitWarnings.add(owner)) player.sendMessage("§cПокупка ожидает восстановления. Если она не оплачена: /fish bait cancel");
                            }
                        }
                        for (var player : getServer().getOnlinePlayers()) {
                            if (!bait.available(player)) continue;
                            var selected = bait.equipped(player);
                            if (selected == null || !"PREPARED".equals(selected.phase())) continue;
                            var result = bait.recoverEquip(player);
                            if (result == ru.cherdakmd.chrdkfishing.RodUpgradeJournal.Result.DONE)
                                player.sendMessage("§aВыбор приманки восстановлен.");
                            else if (baitWarnings.add(player.getUniqueId())) player.sendMessage("§cВыбор приманки ожидает восстановления. Обратитесь к администрации.");
                        }
                    } catch (Exception error) {
                        getLogger().log(java.util.logging.Level.SEVERE, "Ошибка восстановления приманок", error);
                    }
                }, 100L, 100L);
                var rewardWarnings = new java.util.HashSet<java.util.UUID>();
                getServer().getScheduler().runTaskTimer(this, () -> {
                    for (var player : getServer().getOnlinePlayers()) {
                        if (!auth.authenticated(player)) continue;
                        try {
                            if (!albumRewards.pending(player)) { rewardWarnings.remove(player.getUniqueId()); continue; }
                            var result = albumRewards.recover(player);
                            if (result == ru.cherdakmd.chrdkfishing.AlbumRewardJournal.Result.DONE) {
                                rewardWarnings.remove(player.getUniqueId()); player.sendMessage("§aНаграда альбома восстановлена.");
                            } else if (rewardWarnings.add(player.getUniqueId())) player.sendMessage("§cНаграда альбома ожидает сверки. Обратитесь к администрации.");
                        } catch (Exception error) {
                            if (rewardWarnings.add(player.getUniqueId()))
                                getLogger().log(java.util.logging.Level.SEVERE, "Ошибка восстановления награды альбома " + player.getUniqueId(), error);
                        }
                    }
                }, 100L, 100L);
            } finally { java.util.Arrays.fill(key, (byte) 0); }
            getLogger().warning("CHRDK_Fishing DEV: экспериментальный кастомный улов включён (частота настроена в config.yml). Продажа, готовка, альбом и удочки экспериментальны; цены условные. Не устанавливайте на production.");
        } catch (Exception | LinkageError failure) {
            getLogger().log(java.util.logging.Level.SEVERE, "CHRDK_Fishing остановлен без создания улова; сохраните данные и проверьте Core/Skills и ключ", failure);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    private static String checkText(boolean ok) { return ok ? "OK" : "ОШИБКА"; }

    @Override public void onDisable() {
        if (albumRewards != null) {
            try { albumRewards.close(); }
            catch (Exception error) { getLogger().log(java.util.logging.Level.SEVERE, "Ошибка закрытия журнала наград альбома", error); }
            albumRewards = null;
        }
        if (bait != null) {
            try { bait.close(); }
            catch (Exception error) { getLogger().log(java.util.logging.Level.SEVERE, "Ошибка закрытия журнала приманок", error); }
            bait = null;
        }
        if (rods != null) {
            try { rods.close(); }
            catch (Exception error) { getLogger().log(java.util.logging.Level.SEVERE, "Ошибка закрытия журнала удочек", error); }
            rods = null;
        }
        if (sale != null) {
            try { sale.close(); }
            catch (Exception error) { getLogger().log(java.util.logging.Level.SEVERE, "Ошибка закрытия журнала продаж", error); }
            sale = null;
        }
        if (album != null) {
            try { album.close(); }
            catch (java.sql.SQLException error) { getLogger().log(java.util.logging.Level.SEVERE, "Ошибка закрытия альбома", error); }
            album = null;
        }
    }
    /** A marker allows one migration from the prototype, but never silently recreates a lost database. */
    static boolean initialiseAlbumMarker(Path folder) throws java.io.IOException {
        Path marker = folder.resolve("album.initialized");
        Path database = folder.resolve("fishing-album.db");
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) throw new java.io.IOException("album marker invalid");
            return false;
        }
        if (Files.exists(database, LinkOption.NOFOLLOW_LINKS)) throw new java.io.IOException("album marker missing; manual recovery required");
        // A crash after the marker is created fails closed and requires manual recovery.
        Files.createFile(marker);
        return true;
    }
    /** Only initialise an empty, new folder; never replace a missing key in an established folder. */
    static byte[] signingKey(Path directory) throws java.io.IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new java.io.IOException("fishing directory invalid");
        Files.createDirectories(directory);
        Path key = directory.resolve("fish-signing.key");
        if (Files.exists(key, LinkOption.NOFOLLOW_LINKS)) return FishSigningKey.load(key);
        try (var files = Files.list(directory)) {
            if (files.findAny().isPresent()) throw new java.io.IOException("existing fishing data without key: manual recovery required");
        }
        return FishSigningKey.create(key);
    }
}
