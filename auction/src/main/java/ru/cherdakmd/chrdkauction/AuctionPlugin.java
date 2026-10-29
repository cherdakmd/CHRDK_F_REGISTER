package ru.cherdakmd.chrdkauction;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import java.util.logging.Level;

public final class AuctionPlugin extends JavaPlugin {
    private ChrdkRegisterApi api;
    private Messages messages;
    private AuctionConfig config;
    private AuctionGui gui;
    private AuctionService service;
    private BukkitTask expiry;
    public void onEnable() {
        try {
            saveDefaultConfig(); reloadAuction();
            api=Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
            if (api==null || ChrdkRegisterApi.class.getField("API_VERSION").getInt(null)<22) throw new IllegalStateException("Нужен CHRDK_Core API 22+; финансовый fallback запрещён");
            ChrdkRegisterApi.class.getMethod("auctionBankAvailable");
            ChrdkRegisterApi.class.getMethod("auctionPaymentStatus", ru.cherdakmd.chrdkregister.api.AuctionPayment.class);
            ChrdkRegisterApi.class.getMethod("importLegacyAuction", java.util.UUID.class, java.util.UUID.class, long.class);
            ChrdkRegisterApi.class.getMethod("commitAuctionPayment", org.bukkit.entity.Player.class,
                    ru.cherdakmd.chrdkregister.api.AuctionPayment.class, org.bukkit.inventory.ItemStack[].class);
            AuctionStore store=new AuctionStore(getDataFolder().toPath(),getLogger()); store.load();
            for (var lot:store.lots()) ItemCodec.decode(lot.item());
            // Validate committed parcels as well, without exposing a mutable ledger.
            store.validateItems(ItemCodec::decode);
            if (store.pending()!=null) {
                ItemCodec.inventory(store.pending().before()); ItemCodec.inventory(store.pending().after());
                if (store.pending().payment()!=null && !AuctionWallet.valid(store.pending())) throw new IllegalStateException("Неверный платёжный план");
            }
            service=new AuctionService(this,store); service.start();
            gui=new AuctionGui(this,service);
            Bukkit.getPluginManager().registerEvents(service,this);
            Bukkit.getPluginManager().registerEvents(gui,this);
            AuctionCommands commands=new AuctionCommands(this,service,gui);
            for (String name:new String[]{"auction","auctionadmin"}) {
                var command=java.util.Objects.requireNonNull(getCommand(name));
                command.setExecutor(commands); command.setTabCompleter(commands);
            }
            expiry=Bukkit.getScheduler().runTaskTimer(this,()->{
                if (store.healthy() && store.pending()==null) try { store.expire(System.currentTimeMillis()); }
                catch (Exception e) { getLogger().log(Level.SEVERE,"Auction expiry failed",e); }
            },1200L,1200L);
            if (store.pending()!=null) getLogger().severe("Auction locked: unfinished transfer for " + store.pending().player());
        } catch (Exception | LinkageError e) {
            getLogger().log(Level.SEVERE,"Auction disabled; existing ledger was NOT reset",e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }
    public void onDisable() { if (expiry!=null) expiry.cancel(); if (service!=null) service.shutdown(); if (gui!=null) gui.closeAll(); }
    void reloadAuction() {
        reloadConfig(); AuctionConfig next=AuctionConfig.parse(getConfig());
        Messages text=Messages.load(this,"ru",getLogger()); config=next; messages=text;
    }
    ChrdkRegisterApi api() { return api; }
    Messages messages() { return messages; }
    AuctionConfig config() { return config; }
}
