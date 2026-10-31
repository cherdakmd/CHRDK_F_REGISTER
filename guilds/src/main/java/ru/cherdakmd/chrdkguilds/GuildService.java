package ru.cherdakmd.chrdkguilds;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Правила гильдий (1.4.163).
 *
 * <p>Здесь всё, что можно проверить без сервера: состав, ранги, казна, уровни и
 * войны. Сервис не трогает Bukkit — кошелёк приходит интерфейсом
 * {@link GuildPurse}, а сообщения возвращаются ключами. Поэтому «казна не уходит
 * в минус» и «ставка не списывается дважды» проверяются тестами, а не на живом
 * сервере с игроками.</p>
 */
public final class GuildService {

    private final GuildConfig config;
    private final GuildStore store;

    public GuildService(GuildConfig config, GuildStore store) {
        this.config = config == null ? GuildConfig.defaults() : config;
        this.store = store;
    }

    public GuildConfig config() {
        return config;
    }

    public GuildStore store() {
        return store;
    }

    /* -------------------------------- запросы --------------------------------- */

    /** Гильдия игрока или {@code null}. */
    public Guild guildOf(UUID player) {
        return store.of(player);
    }

    /** Гильдия по иду, названию или тегу. */
    public Guild find(String query) {
        return store.find(query);
    }

    public List<Guild> top(int limit) {
        return store.top(limit);
    }

    public int count() {
        return store.count();
    }

    /** Текущая война гильдии (включая ожидание согласия). */
    public GuildWar warOf(String guildId) {
        return store.warOf(guildId);
    }

    public List<GuildWar> wars() {
        return store.wars();
    }

    /* --------------------------------- создание -------------------------------- */

    /** Проверяет название и тег до списания алмазов. */
    public GuildResult checkCreate(UUID leader, String name, String tag) {
        if (!config.enabled()) {
            return GuildResult.fail("disabled");
        }
        if (leader == null) {
            return GuildResult.fail("player-only");
        }
        if (store.of(leader) != null) {
            return GuildResult.fail("already-in-guild");
        }
        if (store.count() >= config.maxGuilds()) {
            return GuildResult.fail("max-guilds", "max", String.valueOf(config.maxGuilds()));
        }
        // Длину проверяем по введённому тексту: иначе модель молча обрежет
        // длинное название до лимита, и игрок не поймёт, почему стало короче.
        String rawName = name == null ? "" : name.trim();
        String rawTag = tag == null ? "" : tag.trim();
        if (rawName.length() < config.minName()) {
            return GuildResult.fail("name-short", "min", String.valueOf(config.minName()));
        }
        if (rawName.length() > config.maxName()) {
            return GuildResult.fail("name-long", "max", String.valueOf(config.maxName()));
        }
        if (rawTag.length() < config.minTag()) {
            return GuildResult.fail("tag-short", "min", String.valueOf(config.minTag()));
        }
        if (rawTag.length() > config.maxTag()) {
            return GuildResult.fail("tag-long", "max", String.valueOf(config.maxTag()));
        }
        String cleanName = Guild.cleanName(rawName);
        String cleanTag = Guild.cleanTag(rawTag);
        if (cleanTag.length() < config.minTag()) return GuildResult.fail("tag-short", "min", String.valueOf(config.minTag()));
        if (store.byName(cleanName) != null) {
            return GuildResult.fail("name-taken", "name", cleanName);
        }
        if (store.byTag(cleanTag) != null) {
            return GuildResult.fail("tag-taken", "tag", cleanTag);
        }
        return GuildResult.ok("create-ready", "cost", GuildMath.diamonds(config.creationCost()));
    }

    /**
     * Создаёт гильдию и списывает взнос.
     *
     * <p>Алмазы берутся только после успешной проверки, а если списание не
     * удалось — гильдия не появляется. Остаток от «сдачи» возвращается сразу.</p>
     */
    public GuildResult create(GuildPurse purse, UUID leader, String name, String tag, long now) {
        if (store.financed()) return GuildResult.fail("finance-paused");
        GuildResult check = checkCreate(leader, name, tag);
        if (!check.ok()) {
            return check;
        }
        long cost = Math.addExact(config.creationCost(),10);
        if (cost > 0) {
            if (purse == null) {
                return GuildResult.fail("create-no-economy");
            }
            long taken = purse.take(leader, cost);
            if (taken < cost) {
                if (taken > 0) {
                    purse.give(leader, taken);
                }
                return GuildResult.fail("create-no-diamonds",
                        "cost", GuildMath.diamonds(cost));
            }
            if (taken > cost) {
                purse.give(leader, taken - cost);
            }
        }
        Guild guild = new Guild(UUID.randomUUID().toString(), Guild.cleanName(name),
                Guild.cleanTag(tag), leader, now);
        guild.creditDues(10);
        store.add(guild);
        return GuildResult.ok("create-ok", "name", guild.name(), "tag", guild.tag());
    }

    /** Распускает гильдию; казна сгорает вместе с ней — так решил лидер. */
    public GuildResult disband(UUID actor) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).disband(actor), GuildResult.fail("finance-paused"));
        Guild guild = store.of(actor);
        if (guild == null) {
            return GuildResult.fail("not-in-guild");
        }
        if (!guild.leader().equals(actor)) {
            return GuildResult.fail("not-leader");
        }
        if(guild.memberIds().stream().anyMatch(store::combatBlocked))return GuildResult.fail("peace-combat");
        String warKey = cancelWarsOnDisband(guild);
        store.remove(guild);
        return GuildResult.ok("disband-ok", "name", guild.name(), "treasury",
                GuildMath.diamonds(guild.treasury()), "members",
                String.valueOf(guild.size()), "war", warKey, "returns", GuildMath.diamonds(Math.addExact(guild.warReturns(),guild.duesReserve())));
    }

    String cancelWarsOnDisband(Guild guild) {
        GuildWar war = store.warOf(guild.id());
        if (war == null) {
            return "disband-war-none";
        }
        if (war.pending()) {
            Guild other = store.guild(war.opponentOf(guild.id()));
            if (other != null && other.id().equals(war.challengerId())) {
                other.returnWarFunds(war.wager());
            }
        } else {
            Guild other = store.guild(war.opponentOf(guild.id()));
            if (other != null) {
                other.returnWarFunds(war.wager() * 2);
                other.addGlory(GuildMath.GLORY_PER_WIN);
                other.recordWarWin();
            }
        }
        war.cancel(System.currentTimeMillis());
        store.closeWar(war, null);
        return "disband-war-cancelled";
    }

    /* ------------------------------- приглашения ------------------------------- */

    /** Приглашает игрока; повторное приглашение заменяет прежнее. */
    public GuildResult invite(UUID inviter, UUID target, long now) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).invite(inviter, target, now), GuildResult.fail("finance-paused"));
        Guild guild = store.of(inviter);
        if (guild == null) {
            return GuildResult.fail("not-in-guild");
        }
        GuildRank rank = guild.rankOf(inviter);
        if (rank == null || !rank.canInvite()) {
            return GuildResult.fail("invite-no-rank");
        }
        if (target == null) {
            return GuildResult.fail("target-not-found");
        }
        if (target.equals(inviter)) {
            return GuildResult.fail("target-self");
        }
        if (store.of(target) != null) {
            return GuildResult.fail("target-in-guild");
        }
        if (guild.size() >= GuildMath.memberCap(guild.level())) {
            return GuildResult.fail("guild-full", "max",
                    String.valueOf(GuildMath.memberCap(guild.level())));
        }
        long expires = now + config.inviteSeconds() * 1_000L;
        store.addInvite(new GuildInvite(guild.id(), guild.name(), target, inviter, now, expires));
        return GuildResult.ok("invite-sent", "name", guild.label(), "seconds",
                String.valueOf(config.inviteSeconds()));
    }

    /** Принимает приглашение и вступает в гильдию. */
    public GuildResult accept(UUID player, long now) { return accept(null,player,now); }
    GuildResult accept(GuildPurse purse, UUID player, long now) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).accept(player, now), GuildResult.fail("finance-paused"));
        if (store.of(player) != null) {
            return GuildResult.fail("already-in-guild");
        }
        GuildInvite invite = store.inviteOf(player);
        if (invite == null) {
            return GuildResult.fail("invite-none");
        }
        if (invite.expired(now)) {
            store.removeInvite(player);
            return GuildResult.fail("invite-expired");
        }
        Guild guild = store.guild(invite.guildId());
        if (guild == null) {
            store.removeInvite(player);
            return GuildResult.fail("invite-guild-gone");
        }
        if (guild.size() >= GuildMath.memberCap(guild.level())) {
            return GuildResult.fail("guild-full", "max",
                    String.valueOf(GuildMath.memberCap(guild.level())));
        }
        if(guild.peaceful()) {
            if(store.combatBlocked(player))return GuildResult.fail("peace-combat");
            if(store.sanction(player).debt()>0)return GuildResult.fail("peace-debt-mode");

        }
        long fee=guild.duesRate()+(guild.peaceful()?GuildPeaceService.DAILY:0);
        if(purse==null)return GuildResult.fail("dues-join-required");
        long taken=purse.take(player,fee);
        if(taken!=fee){if(taken>0)purse.give(player,taken);return GuildResult.fail("dues-bank","cost",fee);}
        guild.addMember(player, GuildRank.MEMBER);
        guild.duesTerm(player,GuildDuesTerm.start(now,guild.duesRate()));guild.creditDues(guild.duesRate());
        if(guild.peaceful()) {
            long active=Math.addExact(now,GuildPeaceService.WARMUP);
            guild.peaceTerm(player,new GuildPeaceTerm(Math.addExact(active,GuildPeaceTerm.DAY),0,active));
        }
        store.reindex(guild);
        store.removeInvite(player);
        return GuildResult.ok("join-ok", "name", guild.label(), "tag", guild.tag(),
                "members", String.valueOf(guild.size()));
    }

    public GuildResult decline(UUID player) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).decline(player), GuildResult.fail("finance-paused"));
        if (store.removeInvite(player)) {
            return GuildResult.ok("invite-declined");
        }
        return GuildResult.fail("invite-none");
    }

    /* ---------------------------------- состав -------------------------------- */

    /** Выход из гильдии: лидер сначала передаёт её или распускает. */
    public GuildResult leave(UUID player) {return leave(player,System.currentTimeMillis());}
    GuildResult leave(UUID player,long now) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).leave(player,now), GuildResult.fail("finance-paused"));
        Guild guild = store.of(player);
        if (guild == null) {
            return GuildResult.fail("not-in-guild");
        }
        if (guild.leader().equals(player)) {
            return GuildResult.fail("leave-leader");
        }
        if(store.combatBlocked(player))return GuildResult.fail("peace-combat");
        if(GuildDuesService.overdue(guild,player,now))return GuildResult.fail("dues-settle-first");
        guild.removeMember(player);
        store.reindex(guild);
        return GuildResult.ok("leave-ok", "name", guild.name());
    }

    public GuildResult kick(UUID actor, UUID target) {return kick(actor, target,System.currentTimeMillis());}
    GuildResult kick(UUID actor, UUID target,long now) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).kick(actor, target,now), GuildResult.fail("finance-paused"));
        Guild guild = store.of(actor);
        if (guild == null) {
            return GuildResult.fail("not-in-guild");
        }
        if (!guild.isMember(target)) {
            return GuildResult.fail("target-not-in-guild");
        }
        if(store.combatBlocked(actor)||store.combatBlocked(target))return GuildResult.fail("peace-combat");
        GuildRank actorRank = guild.rankOf(actor);
        GuildRank targetRank = guild.rankOf(target);
        if (actorRank == null || !actorRank.canKick(targetRank)) {
            return GuildResult.fail("kick-no-rank");
        }
        if(GuildDuesService.overdue(guild,actor,now)||GuildDuesService.overdue(guild,target,now))return GuildResult.fail("dues-settle-first");
        guild.removeMember(target);
        store.reindex(guild);
        return GuildResult.ok("kick-ok", "name", guild.name(), "members",
                String.valueOf(guild.size()));
    }

    public GuildResult promote(UUID actor, UUID target) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).promote(actor, target), GuildResult.fail("finance-paused"));
        Guild guild = store.of(actor);
        if (guild == null) {
            return GuildResult.fail("not-in-guild");
        }
        if (!guild.isMember(target)) {
            return GuildResult.fail("target-not-in-guild");
        }
        if(store.combatBlocked(actor)||store.combatBlocked(target))return GuildResult.fail("peace-combat");
        GuildRank actorRank = guild.rankOf(actor);
        GuildRank targetRank = guild.rankOf(target);
        if (actorRank == null || actorRank != GuildRank.LEADER) {
            return GuildResult.fail("rank-no-rights");
        }
        if (targetRank == null || targetRank != GuildRank.MEMBER) {
            return GuildResult.fail("rank-max");
        }
        guild.setRank(target, GuildRank.OFFICER);
        return GuildResult.ok("promote-ok", "name", guild.name(), "rank",
                GuildRank.OFFICER.id());
    }

    public GuildResult demote(UUID actor, UUID target) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).demote(actor, target), GuildResult.fail("finance-paused"));
        Guild guild = store.of(actor);
        if (guild == null) {
            return GuildResult.fail("not-in-guild");
        }
        if (!guild.isMember(target)) {
            return GuildResult.fail("target-not-in-guild");
        }
        if(store.combatBlocked(actor)||store.combatBlocked(target))return GuildResult.fail("peace-combat");
        GuildRank actorRank = guild.rankOf(actor);
        GuildRank targetRank = guild.rankOf(target);
        if (actorRank == null || actorRank != GuildRank.LEADER) {
            return GuildResult.fail("rank-no-rights");
        }
        if (targetRank == null || targetRank != GuildRank.OFFICER) {
            return GuildResult.fail("rank-min");
        }
        guild.setRank(target, GuildRank.MEMBER);
        return GuildResult.ok("demote-ok", "name", guild.name());
    }

    public GuildResult transfer(UUID actor, UUID target) {return transfer(actor, target,System.currentTimeMillis());}
    GuildResult transfer(UUID actor, UUID target,long now) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).transfer(actor, target,now), GuildResult.fail("finance-paused"));
        Guild guild = store.of(actor);
        if (guild == null) {
            return GuildResult.fail("not-in-guild");
        }
        if (!guild.leader().equals(actor)) {
            return GuildResult.fail("not-leader");
        }
        if (!guild.isMember(target) || target.equals(actor)) {
            return GuildResult.fail("transfer-no-target");
        }
        if(store.combatBlocked(actor)||store.combatBlocked(target))return GuildResult.fail("peace-combat");
        if(GuildDuesService.overdue(guild,actor,now)||GuildDuesService.overdue(guild,target,now))return GuildResult.fail("dues-settle-first");
        guild.transferLeader(target);
        store.reindex(guild);
        return GuildResult.ok("transfer-ok", "name", guild.name());
    }

    /** Переименование: только лидер и только свободное название. */
    public GuildResult rename(UUID actor, String name) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).rename(actor, name), GuildResult.fail("finance-paused"));
        Guild guild = store.of(actor);
        if (guild == null) {
            return GuildResult.fail("not-in-guild");
        }
        if (!guild.leader().equals(actor)) {
            return GuildResult.fail("not-leader");
        }
        String raw = name == null ? "" : name.trim();
        String clean = Guild.cleanName(raw);
        if (raw.length() < config.minName()) {
            return GuildResult.fail("name-short", "min", String.valueOf(config.minName()));
        }
        if (raw.length() > config.maxName()) {
            return GuildResult.fail("name-long", "max", String.valueOf(config.maxName()));
        }
        Guild existing = store.byName(clean);
        if (existing != null && !existing.id().equals(guild.id())) {
            return GuildResult.fail("name-taken", "name", clean);
        }
        guild.rename(clean);
        store.reindex(guild);
        return GuildResult.ok("rename-ok", "name", guild.name());
    }

    /** Новый тег: только лидер и только свободный. */
    public GuildResult retag(UUID actor, String tag) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).retag(actor, tag), GuildResult.fail("finance-paused"));
        Guild guild = store.of(actor);
        if (guild == null) {
            return GuildResult.fail("not-in-guild");
        }
        if (!guild.leader().equals(actor)) {
            return GuildResult.fail("not-leader");
        }
        String raw = tag == null ? "" : tag.trim();
        String clean = Guild.cleanTag(raw);
        if (raw.length() < config.minTag()) {
            return GuildResult.fail("tag-short", "min", String.valueOf(config.minTag()));
        }
        if (raw.length() > config.maxTag()) {
            return GuildResult.fail("tag-long", "max", String.valueOf(config.maxTag()));
        }
        if (clean.length() < config.minTag()) return GuildResult.fail("tag-short", "min", String.valueOf(config.minTag()));
        Guild existing = store.byTag(clean);
        if (existing != null && !existing.id().equals(guild.id())) {
            return GuildResult.fail("tag-taken", "tag", clean);
        }
        guild.retag(clean);
        store.reindex(guild);
        return GuildResult.ok("retag-ok", "tag", guild.tag());
    }

    /* ---------------------------------- казна --------------------------------- */

    /** Кладёт алмазы в казну: сначала берём у игрока, лишнее возвращаем. */
    public GuildResult deposit(GuildPurse purse, UUID player, long amount) {
        if (store.financed()) return GuildResult.fail("finance-paused");
        Guild guild = store.of(player);
        if (guild == null) {
            return GuildResult.fail("not-in-guild");
        }
        if (amount <= 0) {
            return GuildResult.fail("deposit-empty");
        }
        long room = Guild.MAX_TREASURY - guild.treasury();
        if (room <= 0) {
            return GuildResult.fail("treasury-full", "max",
                    GuildMath.diamonds(Guild.MAX_TREASURY));
        }
        long want = Math.min(amount, room);
        if (purse == null) {
            return GuildResult.fail("deposit-no-economy");
        }
        long taken = purse.take(player, want);
        if (taken < want) {
            // Частичный взнос не берём: игрок просил сумму, а не «сколько получится».
            if (taken > 0) {
                purse.give(player, taken);
            }
            return GuildResult.fail("deposit-no-diamonds", "amount", GuildMath.diamonds(want));
        }
        long accepted = guild.deposit(taken);
        if (accepted < taken) {
            purse.give(player, taken - accepted);
        }
        return GuildResult.ok("deposit-ok", "amount", GuildMath.diamonds(accepted),
                "total", GuildMath.diamonds(guild.treasury()), "name", guild.label());
    }

    /** Снимает алмазы из казны: только лидер, чтобы казна не растащилась. */
    public GuildResult withdraw(GuildPurse purse, UUID player, long amount) {
        if (store.financed()) return GuildResult.fail("finance-paused");
        Guild guild = store.of(player);
        if (guild == null) {
            return GuildResult.fail("not-in-guild");
        }
        if (!guild.leader().equals(player)) {
            return GuildResult.fail("withdraw-leader-only");
        }
        if (amount <= 0) {
            return GuildResult.fail("withdraw-empty");
        }
        if (guild.treasury() <= 0) {
            return GuildResult.fail("treasury-empty");
        }
        long taken = guild.withdraw(amount);
        if (taken <= 0) {
            return GuildResult.fail("treasury-empty");
        }
        long dropped = purse == null ? 0L : purse.give(player, taken);
        return GuildResult.ok("withdraw-ok", "amount", GuildMath.diamonds(taken - dropped),
                "total", GuildMath.diamonds(guild.treasury()), "dropped",
                GuildMath.diamonds(dropped));
    }

    /* --------------------------------- уровень -------------------------------- */

    /** Поднимает уровень из казны: цена растёт, потолок состава тоже. */
    public GuildResult upgrade(UUID actor) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).upgrade(actor), GuildResult.fail("finance-paused"));
        Guild guild = store.of(actor);
        if (guild == null) {
            return GuildResult.fail("not-in-guild");
        }
        if (!guild.leader().equals(actor)) {
            return GuildResult.fail("not-leader");
        }
        if (!GuildMath.canUpgrade(guild.level())) {
            return GuildResult.fail("upgrade-max", "level", GuildMath.roman(guild.level()));
        }
        int cost = GuildMath.upgradeCost(guild.level());
        if (guild.treasury() < cost) {
            return GuildResult.fail("upgrade-no-money", "cost", GuildMath.diamonds(cost),
                    "have", GuildMath.diamonds(guild.treasury()));
        }
        guild.withdraw(cost);
        guild.raiseLevel(GuildMath.MAX_LEVEL);
        return GuildResult.ok("upgrade-ok", "level", GuildMath.roman(guild.level()),
                "cap", String.valueOf(GuildMath.memberCap(guild.level())),
                "cost", GuildMath.diamonds(cost));
    }

    /* ---------------------------------- войны --------------------------------- */

    /**
     * Объявляет войну: ставка сразу уходит из казны в банк войны.
     *
     * <p>Вторая сторона должна согласиться в течение {@code accept-seconds};
     * иначе ставка возвращается, а объявивший получает кулдаун.</p>
     */
    public GuildResult declareWar(UUID actor, String targetQuery, long wager, long now) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).declareWar(actor, targetQuery, wager, now), GuildResult.fail("finance-paused"));
        if (!config.warsEnabled()) {
            return GuildResult.fail("war-disabled");
        }
        Guild challenger = store.of(actor);
        if (challenger == null) {
            return GuildResult.fail("not-in-guild");
        }
        if (!challenger.leader().equals(actor)) {
            return GuildResult.fail("not-leader");
        }
        Guild target = store.find(targetQuery);
        if (target == null || target.id().equals(challenger.id())) {
            return GuildResult.fail("war-no-target");
        }
        if(challenger.peaceful()||target.peaceful())return GuildResult.fail("peace-war-disabled");
        if (challenger.warCooldownUntil() > now) {
            long seconds = (challenger.warCooldownUntil() - now + 999) / 1_000L;
            return GuildResult.fail("war-cooldown", "time", GuildMath.clock(seconds));
        }
        if (store.warOf(challenger.id()) != null) {
            return GuildResult.fail("war-busy");
        }
        if (store.warOf(target.id()) != null) {
            return GuildResult.fail("war-target-busy", "name", target.name());
        }
        if (store.pendingWars() + store.activeWars() >= config.maxWars()) {
            return GuildResult.fail("war-too-many", "max", String.valueOf(config.maxWars()));
        }
        long stake = Math.clamp(wager, config.minWager(), config.maxWager());
        if (stake <= 0) {
            return GuildResult.fail("war-wager-min", "min",
                    GuildMath.diamonds(config.minWager()));
        }
        if (wager > config.maxWager()) {
            return GuildResult.fail("war-wager-max", "max",
                    GuildMath.diamonds(config.maxWager()));
        }
        if (challenger.treasury() < stake) {
            return GuildResult.fail("war-no-money", "cost", GuildMath.diamonds(stake),
                    "have", GuildMath.diamonds(challenger.treasury()));
        }
        challenger.withdraw(stake);
        GuildWar war = new GuildWar(UUID.randomUUID().toString(), challenger.id(), target.id(),
                stake, now);
        store.addWar(war);
        return GuildResult.ok("war-declared", "challenger", challenger.label(),
                "target", target.label(), "wager", GuildMath.diamonds(stake),
                "seconds", String.valueOf(config.warAcceptSeconds()));
    }

    /** Вторая сторона соглашается — война начинается. */
    public GuildResult acceptWar(UUID actor, long now) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).acceptWar(actor, now), GuildResult.fail("finance-paused"));
        Guild guild = store.of(actor);
        if (guild == null) {
            return GuildResult.fail("not-in-guild");
        }
        if (!guild.leader().equals(actor)) {
            return GuildResult.fail("not-leader");
        }
        GuildWar war = store.warOf(guild.id());
        if (war == null || !war.pending()) {
            return GuildResult.fail("war-accept-none");
        }
        if (!guild.id().equals(war.targetId())) {
            return GuildResult.fail("war-accept-target-only");
        }
        if (guild.treasury() < war.wager()) {
            return GuildResult.fail("war-no-money", "cost", GuildMath.diamonds(war.wager()),
                    "have", GuildMath.diamonds(guild.treasury()));
        }
        guild.withdraw(war.wager());
        war.activate(now, config.warDurationSeconds());
        Guild challenger = store.guild(war.challengerId());
        return GuildResult.ok("war-start", "challenger",
                challenger == null ? "?" : challenger.label(),
                "target", guild.label(),
                "wager", GuildMath.diamonds(war.wager()),
                "time", GuildMath.clock(config.warDurationSeconds()));
    }

    /** Отказ: ставка объявившего возвращается, у него остаётся кулдаун. */
    public GuildResult declineWar(UUID actor, long now) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).declineWar(actor, now), GuildResult.fail("finance-paused"));
        Guild guild = store.of(actor);
        if (guild == null) {
            return GuildResult.fail("not-in-guild");
        }
        if (!guild.leader().equals(actor)) {
            return GuildResult.fail("not-leader");
        }
        GuildWar war = store.warOf(guild.id());
        if (war == null || !war.pending() || !guild.id().equals(war.targetId())) {
            return GuildResult.fail("war-accept-none");
        }
        Guild challenger = store.guild(war.challengerId());
        if (challenger != null) {
            challenger.returnWarFunds(war.wager());
            challenger.setWarCooldownUntil(now + config.warCooldownSeconds() * 1_000L);
        }
        war.cancel(now);
        store.closeWar(war, null);
        return GuildResult.ok("war-declined", "name", guild.label(),
                "challenger", challenger == null ? "?" : challenger.label());
    }

    /** Капитуляция: победа достаётся противнику сразу, без ожидания таймера. */
    public GuildResult surrenderWar(UUID actor, long now) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).surrenderWar(actor, now), GuildResult.fail("finance-paused"));
        Guild guild = store.of(actor);
        if (guild == null) {
            return GuildResult.fail("not-in-guild");
        }
        if (!guild.leader().equals(actor)) {
            return GuildResult.fail("not-leader");
        }
        GuildWar war = store.warOf(guild.id());
        if (war == null || !war.active()) {
            return GuildResult.fail("war-not-active");
        }
        String opponentId = war.opponentOf(guild.id());
        Guild winner = store.guild(opponentId);
        if (war.challengerId().equals(guild.id())) {
            war.overridePoints(0, Math.max(1, war.targetPoints()));
        } else {
            war.overridePoints(Math.max(1, war.challengerPoints()), 0);
        }
        GuildResult settled = settle(war, winner, now);
        return GuildResult.ok("war-surrender", "name", guild.label(),
                "winner", winner == null ? "?" : winner.label(), "key", settled.key());
    }

    /** Один такт: закрывает просроченные приглашения и войны. */
    public List<GuildResult> tick(long now) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).tick(now), List.of());
        List<GuildResult> notices = new ArrayList<>();
        store.pruneInvites(now);
        for (GuildWar war : store.wars()) {
            if (war.pending() && now - war.createdAt() >= config.warAcceptSeconds() * 1_000L) {
                Guild challenger = store.guild(war.challengerId());
                Guild target = store.guild(war.targetId());
                if (challenger != null) {
                    challenger.returnWarFunds(war.wager());
                    challenger.setWarCooldownUntil(now
                            + Math.min(config.warCooldownSeconds(), 600) * 1_000L);
                }
                war.cancel(now);
                store.closeWar(war, null);
                notices.add(GuildResult.ok("war-expired",
                        "challenger", challenger == null ? "?" : challenger.label(),
                        "target", target == null ? "?" : target.label()));
                continue;
            }
            if (war.active() && now >= war.endsAt()) {
                String leader = war.leader();
                Guild winner = leader == null ? null : store.guild(leader);
                notices.add(settle(war, winner, now));
            }
        }
        return notices;
    }

    /** Выдаёт награду за войну и закрывает её. */
    private GuildResult settle(GuildWar war, Guild winner, long now) {
        Guild challenger = store.guild(war.challengerId());
        Guild target = store.guild(war.targetId());
        GuildWar.Result result = war.finish();
        String winnerName = winner == null ? "" : winner.name();
        if (result == GuildWar.Result.DRAW) {
            if (challenger != null) {
                challenger.returnWarFunds(war.wager());
                challenger.addGlory(GuildMath.GLORY_PER_LOSS);
            }
            if (target != null) {
                target.returnWarFunds(war.wager());
                target.addGlory(GuildMath.GLORY_PER_LOSS);
            }
        } else if (winner != null) {
            winner.returnWarFunds(war.wager() * 2);
            winner.addGlory(GuildMath.GLORY_PER_WIN);
            winner.recordWarWin();
            Guild loser = winner.id().equals(war.challengerId()) ? target : challenger;
            if (loser != null) {
                loser.addGlory(GuildMath.GLORY_PER_LOSS);
                loser.recordWarLoss();
                loser.setWarCooldownUntil(now + config.warCooldownSeconds() * 1_000L);
            }
            winner.setWarCooldownUntil(now + config.warCooldownSeconds() * 1_000L);
        }
        store.closeWar(war, winnerName);
        String type = result == GuildWar.Result.DRAW ? "war-finished-draw" : "war-finished-win";
        return GuildResult.ok(type,
                "challenger", challenger == null ? "?" : challenger.label(),
                "target", target == null ? "?" : target.label(),
                "winner", winnerName.isEmpty() ? "?" : winnerName,
                "points", war.challengerPoints() + ":" + war.targetPoints(),
                "wager", GuildMath.diamonds(war.wager() * 2));
    }

    /**
     * Учитывает убийство между участниками вражеских гильдий.
     *
     * @param claimed погиб ли игрок на приватах (вес боя выше)
     */
    public GuildResult kill(UUID killer, UUID victim, boolean claimed, long now) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).kill(killer, victim, claimed, now), GuildResult.fail("finance-paused"));
        if (!config.warsEnabled() || killer == null || victim == null || killer.equals(victim)) {
            return GuildResult.fail("");
        }
        Guild killerGuild = store.of(killer);
        Guild victimGuild = store.of(victim);
        if (killerGuild == null || victimGuild == null
                || killerGuild.id().equals(victimGuild.id())) {
            return GuildResult.fail("");
        }
        GuildWar war = store.warBetween(killerGuild.id(), victimGuild.id());
        if (war == null || !war.active()) {
            return GuildResult.fail("");
        }
        boolean bonus = claimed && config.claimedBonus();
        int points = war.addKill(killerGuild.id(), killer, victim, bonus, now,
                config.killWindowSeconds() * 1_000L);
        if (points <= 0) {
            return GuildResult.fail("");
        }
        return GuildResult.ok("war-point", "points", String.valueOf(points),
                "name", killerGuild.label(), "score", war.pointsOf(killerGuild.id())
                        + ":" + war.pointsOf(victimGuild.id()));
    }

    /* --------------------------------+ служебное -------------------------------- */

    /** Принудительно завершает войну гильдии — админская команда. */
    public GuildResult forceEndWar(String query, long now) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).forceEndWar(query, now), GuildResult.fail("finance-paused"));
        Guild guild = store.find(query);
        if (guild == null) {
            return GuildResult.fail("guild-not-found", "name", query == null ? "?" : query);
        }
        GuildWar war = store.warOf(guild.id());
        if (war == null) {
            return GuildResult.fail("war-not-active");
        }
        String leader = war.leader();
        Guild winner = leader == null ? null : store.guild(leader);
        return settle(war, winner, now);
    }

    /** Убирает гильдию админской командой без проверки лидера. */
    public GuildResult forceDisband(String query) {
        if (store.financed()) return store.transaction(copy -> new GuildService(config, copy).forceDisband(query), GuildResult.fail("finance-paused"));
        Guild guild = store.find(query);
        if (guild == null) {
            return GuildResult.fail("guild-not-found", "name", query == null ? "?" : query);
        }
        if(guild.memberIds().stream().anyMatch(store::combatBlocked))return GuildResult.fail("peace-combat");
        String warKey = cancelWarsOnDisband(guild);
        store.remove(guild);
        return GuildResult.ok("admin-disbanded", "name", guild.name(),
                "treasury", GuildMath.diamonds(guild.treasury()), "war", warKey, "returns", GuildMath.diamonds(Math.addExact(guild.warReturns(),guild.duesReserve())));
    }

    /** Снимает кулдаун войны — после ручного разбора ситуации. */
    public boolean clearCooldown(Guild guild) {
        if (store.financed()) return guild != null && store.transaction(copy -> new GuildService(config, copy).clearCooldown(copy.guild(guild.id())), false);
        if (guild == null || guild.warCooldownUntil() == 0) {
            return false;
        }
        guild.setWarCooldownUntil(0);
        return true;
    }
}
