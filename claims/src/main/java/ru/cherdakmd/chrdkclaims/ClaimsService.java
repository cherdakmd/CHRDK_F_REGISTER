package ru.cherdakmd.chrdkclaims;

import org.bukkit.Chunk;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Бизнес-логика приватов: покупка/продажа чанков, казна, участники, ежесуточная рента.
 * Деньги — только предметы алмазов: казна (виртуальный баланс) + добор из инвентаря.
 */
public final class ClaimsService {

    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd.MM.yyyy HH:mm");

    private final ClaimsPlugin plugin;

    public ClaimsService(ClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ helpers --

    public boolean isAllowed(Player player) {
        return !plugin.config().requireAuth
                || (plugin.coreApi() != null && plugin.coreApi().isAuthenticated(player));
    }

    public int limitOf(String playerName) {
        boolean hasPass = plugin.coreApi() != null && plugin.coreApi().hasActivePass(playerName);
        return ClaimMath.effectiveLimit(plugin.config().baseChunks,
                plugin.config().passBonusChunks, hasPass);
    }

    public static String formatDate(long millis) {
        return DATE.format(new Date(millis));
    }

    /**
     * Максимальная доступная сумма: казна + алмазные предметы инвентаря.
     */
    public int available(Player player) {
        return plugin.store().treasuryOf(player.getName()) + DiamondItems.count(player.getInventory());
    }

    /**
     * Списывает сумму: сначала казна, недостающее — предметами (со сдачей в казну).
     *
     * @return true, если сумма набралась и списана
     */
    public boolean charge(Player player, int amount) {
        if (amount <= 0) {
            return true;
        }
        int treasury = plugin.store().treasuryOf(player.getName());
        int items = DiamondItems.count(player.getInventory());
        if (treasury + items < amount) {
            return false;
        }
        int fromItems = Math.max(0, amount - treasury);
        int collected = 0;
        if (fromItems > 0) {
            collected = DiamondItems.collect(player.getInventory(), fromItems);
            if (collected < fromItems) {
                return false; // не должно случаться после проверки, но подстрахуемся
            }
        }
        plugin.store().takeTreasury(player.getName(), Math.min(treasury, amount));
        if (collected > fromItems) {
            plugin.store().addTreasury(player.getName(), collected - fromItems);
            plugin.messages().send(player, "change-to-treasury",
                    "change", String.valueOf(collected - fromItems));
        }
        plugin.store().markDirty();
        return true;
    }

    // ------------------------------------------------------------ purchase --

    /** Проверка возможности купить именно этот чанк (статус броски в out-сообщение). */
    public String checkBuyable(Player player, Chunk chunk) {
        ClaimsConfig cfg = plugin.config();
        Claim existing = plugin.store().claimAt(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        if (existing != null) {
            return existing.isOwner(player.getName()) ? "already-yours" : "already-claimed";
        }
        if (cfg.spawnMargin > 0) {
            var spawn = chunk.getWorld().getSpawnLocation();
            int dx = (chunk.getX() << 4) + 8 - spawn.getBlockX();
            int dz = (chunk.getZ() << 4) + 8 - spawn.getBlockZ();
            if (dx * dx + dz * dz <= (long) cfg.spawnMargin * cfg.spawnMargin) {
                return "spawn-margin";
            }
        }
        int limit = limitOf(player.getName());
        if (plugin.store().count(player.getName()) >= limit) {
            return "limit-reached";
        }
        if (available(player) < cfg.chunkPrice) {
            return "not-enough";
        }
        return null;
    }

    /** Покупка текущего чанка. Обмен сообщениями — здесь. */
    public void buy(Player player) {
        ClaimsConfig cfg = plugin.config();
        Chunk chunk = player.getLocation().getChunk();
        Claim existing = plugin.store().claimAt(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        String refusal = checkBuyable(player, chunk);
        if (refusal != null) {
            switch (refusal) {
                case "already-yours" -> plugin.messages().send(player, "already-yours");
                case "already-claimed" -> plugin.messages().send(player, "already-claimed",
                        "owner", existing.owner);
                case "spawn-margin" -> plugin.messages().send(player, "spawn-margin",
                        "margin", String.valueOf(cfg.spawnMargin));
                case "limit-reached" -> plugin.messages().send(player, "limit-reached",
                        "limit", String.valueOf(limitOf(player.getName())),
                        "pass-hint", hasNoPass(player) ? plugin.messages().raw("pass-hint") : "");
                default -> plugin.messages().send(player, "not-enough",
                        "need", String.valueOf(cfg.chunkPrice),
                        "have", String.valueOf(available(player)));
            }
            return;
        }
        if (!charge(player, cfg.chunkPrice)) {
            plugin.messages().send(player, "not-enough",
                    "need", String.valueOf(cfg.chunkPrice),
                    "have", String.valueOf(available(player)));
            return;
        }
        Claim claim = new Claim(chunk.getWorld().getName(), chunk.getX(), chunk.getZ(),
                player.getName(), System.currentTimeMillis() + ClaimMath.DAY_MILLIS);
        plugin.store().add(claim);
        plugin.store().addDiamondsSpent(cfg.chunkPrice);
        plugin.store().markDirty();
        plugin.messages().send(player, "bought",
                "price", String.valueOf(cfg.chunkPrice),
                "upkeep", String.valueOf(cfg.upkeepPerDay),
                "paid", formatDate(claim.paidUntil));
    }

    private boolean hasNoPass(Player player) {
        return plugin.coreApi() == null || !plugin.coreApi().hasActivePass(player.getName());
    }

    /** Отказ от привата без компенсации. */
    public void unclaim(Player player) {
        Claim claim = claimHere(player);
        if (claim == null) {
            plugin.messages().send(player, "unclaim-none");
            return;
        }
        if (!claim.isOwner(player.getName())) {
            plugin.messages().send(player, "unclaim-not-yours");
            return;
        }
        plugin.store().remove(claim);
        plugin.messages().send(player, "unclaimed");
    }

    /** Мгновенный обратный выкуп сервером за sellbackPercent от цены. */
    public void sellback(Player player) {
        Claim claim = ownClaimHere(player, "sell-not-yours");
        if (claim == null) {
            return;
        }
        int reward = ClaimMath.sellbackReward(plugin.config().chunkPrice, plugin.config().sellbackPercent);
        plugin.store().remove(claim);
        if (reward > 0) {
            DiamondItems.give(player, reward);
        }
        plugin.messages().send(player, "sellback-done", "reward", String.valueOf(reward));
    }

    // ------------------------------------------------------------ p2p sale --

    /** Выставить/снять продажу. price 0 = снять с продажи. */
    public void setSale(Player player, int price) {
        Claim claim = ownClaimHere(player, "sell-not-yours");
        if (claim == null) {
            return;
        }
        claim.salePrice = price;
        plugin.store().markDirty();
        if (price > 0) {
            plugin.messages().send(player, "sell-set", "price", String.valueOf(price));
        } else {
            plugin.messages().send(player, "sell-cleared");
        }
    }

    /** Покупка чужого чанка, выставленного на продажу (в точке, где стоит покупатель). */
    public void buyFromPlayer(Player player) {
        Chunk chunk = player.getLocation().getChunk();
        Claim claim = plugin.store().claimAt(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        if (claim == null || claim.salePrice <= 0) {
            plugin.messages().send(player, "buy-not-for-sale");
            return;
        }
        if (claim.isOwner(player.getName())) {
            plugin.messages().send(player, "already-yours");
            return;
        }
        int price = claim.salePrice;
        if (available(player) < price) {
            plugin.messages().send(player, "not-enough",
                    "need", String.valueOf(price), "have", String.valueOf(available(player)));
            return;
        }
        int limit = limitOf(player.getName());
        if (plugin.store().count(player.getName()) >= limit) {
            plugin.messages().send(player, "limit-reached",
                    "limit", String.valueOf(limit),
                    "pass-hint", hasNoPass(player) ? plugin.messages().raw("pass-hint") : "");
            return;
        }
        if (!charge(player, price)) {
            plugin.messages().send(player, "not-enough",
                    "need", String.valueOf(price), "have", String.valueOf(available(player)));
            return;
        }
        String seller = claim.owner;
        int tax = ClaimMath.saleTax(price, plugin.config().saleTaxPercent);
        int net = Math.max(0, price - tax);
        plugin.store().addTreasury(seller, net);
        plugin.store().addTaxBurned(tax);

        claim.owner = player.getName();
        claim.members.clear();
        claim.salePrice = 0;
        claim.debtDays = 0;
        plugin.store().markDirty();

        plugin.messages().send(player, "buy-done",
                "seller", seller, "price", String.valueOf(price), "tax", String.valueOf(tax));
        plugin.store().addNotice(seller, plugin.messages().plain("buy-sold-notice",
                "x", String.valueOf(claim.x), "z", String.valueOf(claim.z),
                "price", String.valueOf(price), "buyer", player.getName(),
                "net", String.valueOf(net), "tax", String.valueOf(tax)));
        Player sellerOnline = org.bukkit.Bukkit.getPlayerExact(seller);
        if (sellerOnline != null) {
            plugin.messages().send(sellerOnline, "buy-sold",
                    "x", String.valueOf(claim.x), "z", String.valueOf(claim.z),
                    "price", String.valueOf(price), "buyer", player.getName(),
                    "net", String.valueOf(net), "tax", String.valueOf(tax));
        }
    }

    // ------------------------------------------------------------ members --

    public boolean addMember(Player player, String name, boolean everywhere) {
        List<Claim> targets = targets(player, everywhere);
        if (targets == null) {
            return false;
        }
        int changed = 0;
        for (Claim claim : targets) {
            if (claim.members.stream().anyMatch(m -> m.equalsIgnoreCase(name))
                    || claim.isOwner(name)) {
                continue;
            }
            if (claim.members.size() >= plugin.config().membersPerChunk) {
                plugin.messages().send(player, "add-full",
                        "max", String.valueOf(plugin.config().membersPerChunk));
                if (!everywhere) {
                    return true;
                }
                continue;
            }
            claim.members.add(name);
            changed++;
        }
        plugin.store().markDirty();
        if (everywhere) {
            plugin.messages().send(player, "add-all-done", "name", name, "count", String.valueOf(changed));
        } else if (changed > 0) {
            plugin.messages().send(player, "add-done", "name", name);
        } else {
            plugin.messages().send(player, "add-already", "name", name);
        }
        return true;
    }

    public boolean removeMember(Player player, String name, boolean everywhere) {
        List<Claim> targets = targets(player, everywhere);
        if (targets == null) {
            return false;
        }
        int changed = 0;
        for (Claim claim : targets) {
            changed += claim.members.removeIf(m -> m.equalsIgnoreCase(name)) ? 1 : 0;
        }
        plugin.store().markDirty();
        if (everywhere) {
            plugin.messages().send(player, "remove-all-done", "name", name, "count", String.valueOf(changed));
        } else if (changed > 0) {
            plugin.messages().send(player, "remove-done", "name", name);
        } else {
            plugin.messages().send(player, "remove-not-member", "name", name);
        }
        return true;
    }

    /** Цели для add/remove: текущий чанк (с проверкой владения) или все приваты игрока. */
    private List<Claim> targets(Player player, boolean everywhere) {
        if (everywhere) {
            List<Claim> all = plugin.store().ofOwner(player.getName());
            if (all.isEmpty()) {
                plugin.messages().send(player, "unclaim-none");
                return null;
            }
            return all;
        }
        Claim claim = claimHere(player);
        if (claim == null) {
            plugin.messages().send(player, "unclaim-none");
            return null;
        }
        if (!claim.isOwner(player.getName())) {
            plugin.messages().send(player, "remove-not-owner");
            return null;
        }
        return List.of(claim);
    }

    // ------------------------------------------------------------ treasury --

    public void deposit(Player player, int amount) {
        if (DiamondItems.count(player.getInventory()) < amount) {
            plugin.messages().send(player, "deposit-none");
            return;
        }
        int collected = DiamondItems.collect(player.getInventory(), amount);
        int balance = plugin.store().addTreasury(player.getName(), collected);
        plugin.messages().send(player, "deposit-done",
                "amount", String.valueOf(collected), "balance", String.valueOf(balance));
    }

    public void withdraw(Player player, int amount) {
        if (!plugin.store().takeTreasury(player.getName(), amount)) {
            plugin.messages().send(player, "withdraw-not-enough",
                    "balance", String.valueOf(plugin.store().treasuryOf(player.getName())));
            return;
        }
        DiamondItems.give(player, amount);
        plugin.messages().send(player, "withdraw-done",
                "amount", String.valueOf(amount),
                "balance", String.valueOf(plugin.store().treasuryOf(player.getName())));
    }

    // ------------------------------------------------------------- upkeep --

    /**
     * Ежесуточная рента: добегает пропущенные дни (сервер мог простаивать),
     * списывает из казны владельца; по исчерпании льготы чанк освобождается.
     * Вызывается периодически (из плагина, каждый час и при старте).
     */
    public void processUpkeep(long now) {
        ClaimsConfig cfg = plugin.config();
        if (cfg.upkeepPerDay <= 0) {
            return;
        }
        int totalCharged = 0;
        for (Claim claim : plugin.store().all()) {
            long overdue = ClaimMath.overdueDays(claim.paidUntil, now);
            for (long i = 0; i < overdue; i++) {
                if (plugin.store().takeTreasury(claim.owner, cfg.upkeepPerDay)) {
                    claim.paidUntil += ClaimMath.DAY_MILLIS;
                    claim.debtDays = 0;
                    totalCharged += cfg.upkeepPerDay;
                } else {
                    claim.paidUntil += ClaimMath.DAY_MILLIS;
                    claim.debtDays++;
                    if (claim.debtDays > cfg.graceDays) {
                        plugin.store().addNotice(claim.owner, plugin.messages().plain("notice-lost",
                                "where", claim.where()));
                        plugin.store().remove(claim);
                        break;
                    }
                    plugin.store().addNotice(claim.owner, plugin.messages().plain("notice-debt",
                            "where", claim.where(),
                            "days", String.valueOf(claim.debtDays),
                            "grace", String.valueOf(cfg.graceDays)));
                }
            }
            plugin.store().markDirty();
        }
        if (totalCharged > 0) {
            plugin.getLogger().info("[CHRDK_Claims] Рента: списано " + totalCharged + " алмазов.");
        }
    }

    // ------------------------------------------------------------- others --

    public Claim claimHere(Player player) {
        Chunk chunk = player.getLocation().getChunk();
        return plugin.store().claimAt(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
    }

    private Claim ownClaimHere(Player player, String refusalKey) {
        Claim claim = claimHere(player);
        if (claim == null) {
            plugin.messages().send(player, "unclaim-none");
            return null;
        }
        if (!claim.isOwner(player.getName())) {
            plugin.messages().send(player, refusalKey);
            return null;
        }
        return claim;
    }
}
