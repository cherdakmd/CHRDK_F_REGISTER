package ru.cherdakmd.chrdkguilds;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.api.ChargeInventory;
import ru.cherdakmd.chrdkregister.api.PaidActions;

import java.util.function.BiFunction;

/** Личные операции: чистый preview домена → банковский WAL → публикация всего домена.
 * Казна и военные резервы никогда не зачисляются лидеру без явного снятия. */
final class GuildFinance {
    private final GuildsPlugin plugin;
    private final PaidActions payments;

    GuildFinance(GuildsPlugin plugin, PaidActions payments) {
        this.plugin = plugin;
        this.payments = payments;
    }

    /** Только считает планируемые взнос/выплату, не касается банка или инвентаря. */
    static final class Quote implements GuildPurse {
        long debit, credit;
        @Override public long take(java.util.UUID player,long amount) { debit = Math.addExact(debit,amount); return amount; }
        @Override public long give(java.util.UUID player,long amount) { credit = Math.addExact(credit,amount); return 0; }
    }

    record Preview(GuildResult result, String domain, long debit, long credit) { }

    /** Свободный остаток пополняется военными возвратами при withdraw: учитываем оба коллективных обязательства. */
    static long treasurySpent(Guild before, Guild after) {
        return Math.subtractExact(Math.addExact(Math.addExact(before.treasury(),before.warReturns()),before.duesReserve()),
                Math.addExact(Math.addExact(after.treasury(),after.warReturns()),after.duesReserve()));
    }

    /** Тот же production-путь preview проверяется без Bukkit-сервера. */
    static Preview preview(GuildStore live, GuildConfig config, BiFunction<GuildService,Quote,GuildResult> operation) {
        GuildStore copy = live.copy();
        Quote quote = new Quote();
        GuildResult result = operation.apply(new GuildService(config,copy),quote);
        String after = copy.snapshotJson();
        copy.detached(after); // полный строгий домен до списания денег
        if (quote.debit < 0 || quote.debit > Guild.MAX_TREASURY || quote.credit < 0 || quote.credit > Guild.MAX_TREASURY
                || quote.debit > 0 && quote.credit > 0) throw new IllegalStateException("Invalid guild quote");
        return new Preview(result,after,quote.debit,quote.credit);
    }

    GuildResult create(Player player,String name,String tag,long now) {
        return execute(player,(service,quote) -> service.create(quote,player.getUniqueId(),name,tag,now),true);
    }
    GuildResult mode(Player player,GuildMode mode,long now) {
        return execute(player,(service,quote)->new GuildPeaceService(service.store()).mode(quote,player.getUniqueId(),mode,now));
    }
    GuildResult join(Player player,long now) {
        return execute(player,(service,quote)->service.accept(quote,player.getUniqueId(),now),true);
    }
    GuildResult deposit(Player player,long amount) {
        return execute(player,(service,quote) -> service.deposit(quote,player.getUniqueId(),amount));
    }
    GuildResult withdraw(Player player,long amount) {
        return execute(player,(service,quote) -> service.withdraw(quote,player.getUniqueId(),amount));
    }

    private GuildResult execute(Player player,BiFunction<GuildService,Quote,GuildResult> operation) {
        return execute(player,operation,false);
    }
    private GuildResult execute(Player player,BiFunction<GuildService,Quote,GuildResult> operation,boolean bankOnly) {
        if (!plugin.authenticated(player) || !player.isOnline() || player.isDead() || player.hasMetadata("NPC")
                || !player.hasPermission("chrdkguilds.use") || !plugin.config().enabled()
                || !(player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)) return GuildResult.fail("finance-mode");
        if (payments.occupied(player.getUniqueId())) return GuildResult.fail("finance-paused");
        try {
            Preview preview = preview(plugin.store(),plugin.config(),operation);
            if (!preview.result().ok()) return preview.result();
            if(bankOnly){
                long spendable=plugin.coreApi().spendableBankBalance(player.getUniqueId());
                if(spendable<0)return GuildResult.fail("finance-paused");
                if(spendable<preview.debit())return GuildResult.fail("dues-bank","cost",preview.debit());
                return payments.putGuildBankValue(player.getUniqueId(),Math.toIntExact(preview.debit()),GuildStore.FINANCE_KEY,preview.domain(),false)
                        ?preview.result():GuildResult.fail("finance-paused");
            }
            boolean confirmed = preview.credit() > 0
                    ? payments.creditValue(player,GuildStore.FINANCE_KEY,preview.domain(),preview.credit())
                    : payments.putValue(player,(int)preview.debit(),GuildStore.FINANCE_KEY,preview.domain());
            return confirmed ? preview.result() : GuildResult.fail("finance-paused");
        } catch (RuntimeException error) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,"Операция гильдии не подтверждена; данные не сброшены",error);
            return GuildResult.fail("finance-paused");
        }
    }

    long available(Player player) {
        if (!plugin.authenticated(player) || payments.paused()) return 0;
        long bank = Math.clamp(plugin.coreApi().auctionBankBalance(player.getUniqueId()),0L,Guild.MAX_TREASURY);
        for (var item : player.getInventory().getStorageContents()) bank += ChargeInventory.cashValue(item);
        return Math.min(bank,Guild.MAX_TREASURY);
    }
}
