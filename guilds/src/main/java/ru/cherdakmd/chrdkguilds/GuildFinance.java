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

    /** Доменные тарифы выражены в базовых алмазах, банк и frozen WAL — в minor. */
    static long baseAffordable(long minor, int rateBps) {
        if (minor < 0 || rateBps < 5_000 || rateBps > 20_000) return -1;
        if (minor >= Guild.MAX_TREASURY * (long) rateBps) return Guild.MAX_TREASURY;
        return minor * 100L / rateBps;
    }

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
    GuildResult join(Player player, GuildJoinTerms terms, long expires, long now) {
        return execute(player, (service, quote) -> confirmedJoin(service,quote,player.getUniqueId(),terms,expires,now), true);
    }
    /** Та же повторная проверка согласия в production и тесте, внутри detached preview. */
    static GuildResult confirmedJoin(GuildService service, Quote quote, java.util.UUID player,
                                     GuildJoinTerms terms, long expires, long now) {
        if (terms == null || now >= expires || !terms.matches(service.store(), player, now))
            return GuildResult.fail("recruit-changed");
        return terms.invitation() == null ? service.joinOpen(quote,player,terms.guild(),now)
                : service.accept(quote,player,now);
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
                long spendable=baseAffordable(plugin.coreApi().spendableBankBalanceMinor(player.getUniqueId()),
                        plugin.coreApi().diamondRate().rateBps());
                if(spendable<0)return GuildResult.fail("finance-paused");
                long minorCost=plugin.coreApi().diamondRate().scaleMinor(preview.debit()*100L);
                if(spendable<preview.debit())return GuildResult.fail("dues-bank","cost",ru.cherdakmd.chrdkregister.api.DiamondMoney.format(minorCost));
                return payments.putGuildBankValue(player.getUniqueId(),Math.toIntExact(preview.debit()),GuildStore.FINANCE_KEY,preview.domain(),false)
                        ?preview.result():GuildResult.fail("finance-paused");
            }
            boolean confirmed = preview.credit() > 0
                    ? payments.creditValue(player,GuildStore.FINANCE_KEY,preview.domain(),plugin.coreApi().diamondRate().scaleMinor(preview.credit()*100L))
                    : payments.putValue(player,(int)preview.debit(),GuildStore.FINANCE_KEY,preview.domain());
            return confirmed ? preview.result() : GuildResult.fail("finance-paused");
        } catch (RuntimeException error) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,"Операция гильдии не подтверждена; данные не сброшены",error);
            return GuildResult.fail("finance-paused");
        }
    }

    long available(Player player) {
        if (!plugin.authenticated(player) || payments.paused()) return 0;
        long minor = Math.clamp(plugin.coreApi().auctionBankBalanceMinor(player.getUniqueId()),0L,Guild.MAX_TREASURY*20_000L);
        for (var item : player.getInventory().getStorageContents()) minor=Math.addExact(minor,plugin.coreApi().diamondRate().creditMinor(ChargeInventory.cashValue(item)));
        return baseAffordable(minor,plugin.coreApi().diamondRate().rateBps());
    }
}
