package ru.cherdakmd.chrdkclaims;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Бизнес-логика приватов: покупка/продажа чанков, казна, участники, ежесуточная рента.
 * Деньги — только предметы алмазов: казна (виртуальный баланс) + добор из инвентаря.
 */
public final class ClaimsService {

    private final ClaimsPlugin plugin;

    public ClaimsService(ClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Итог прогона ренты: списано, освобождено чанков, сколько чанков с долгом. */
    public record UpkeepResult(int charged, int released, int debtClaims) {
    }

    // ------------------------------------------------------------ helpers --

    public boolean isAllowed(Player player) {
        return !plugin.config().requireAuth
                || (plugin.coreApi() != null && plugin.coreApi().isAuthenticated(player));
    }

    /** Онлайн-игрок полностью авторизован (или ядро без API — тогда не мешаем уведомлениям). */
    private boolean authenticated(Player player) {
        return plugin.coreApi() == null || plugin.coreApi().isAuthenticated(player);
    }

    /** Общий лимит игрока: база + бонус активной проходки, без персональных правок. */
    public int baseLimitOf(String playerName) {
        boolean hasPass = plugin.coreApi() != null && plugin.coreApi().hasActivePass(playerName);
        return ClaimMath.effectiveLimit(plugin.config().baseChunks,
                plugin.config().passBonusChunks, hasPass);
    }

    /** Фактический лимит: персональный лимит администратора главнее общего. */
    public int limitOf(String playerName) {
        Integer override = plugin.store().limitOverride(playerName);
        return override != null ? override : baseLimitOf(playerName);
    }

    /** Короткая запись в журнал операций (действие + подробности). */
    private void audit(String action, String details) {
        plugin.audit(action, details);
    }

    public String formatDate(long millis) {
        return plugin.config().formatDate(millis);
    }

    /** Игрок когда-либо заходил на сервер (защита от опечаток в /claim add и продажах). */
    public boolean knownPlayer(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return Bukkit.getPlayerExact(name) != null || Bukkit.getOfflinePlayerIfCached(name) != null;
    }

    /** Текущая казна + алмазы в инвентаре (с защитой от переполнения int). */
    public int available(Player player) {
        long sum = (long) plugin.store().treasuryOf(player.getName())
                + DiamondItems.count(player.getInventory());
        return (int) Math.min(Integer.MAX_VALUE, sum);
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
        if ((long) treasury + items < amount) {
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

    /** Проверка возможности купить именно этот чанк (статус-отказ в out-сообщение). */
    public String checkBuyable(Player player, Chunk chunk) {
        ClaimsConfig cfg = plugin.config();
        if (!cfg.worldEnabled(chunk.getWorld().getName())) {
            return "world-disabled";
        }
        Claim existing = plugin.store().claimAt(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        if (existing != null) {
            if (existing.isOwner(player.getName())) {
                return "already-yours";
            }
            return existing.salePrice > 0 ? "for-sale" : "already-claimed";
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
        if (available(player) < cfg.priceIn(chunk.getWorld().getName())) {
            return "not-enough";
        }
        return null;
    }

    /** Покупка текущего чанка. Обмен сообщениями — здесь. */
    public void buy(Player player) {
        Chunk chunk = player.getLocation().getChunk();
        buy(player, chunk);
    }

    /** Покупка свободного чанка по координатам текущего мира. */
    public void buyAt(Player player, int chunkX, int chunkZ) {
        buy(player, player.getWorld().getChunkAt(chunkX, chunkZ));
    }

    private void buy(Player player, Chunk chunk) {
        ClaimsConfig cfg = plugin.config();
        String world = chunk.getWorld().getName();
        int price = cfg.priceIn(world);
        int upkeep = cfg.upkeepIn(world);
        Claim existing = plugin.store().claimAt(world, chunk.getX(), chunk.getZ());
        String refusal = checkBuyable(player, chunk);
        if (refusal != null) {
            switch (refusal) {
                case "already-yours" -> plugin.messages().send(player, "already-yours");
                case "for-sale" -> plugin.messages().send(player, "already-for-sale",
                        "owner", existing.owner, "price", String.valueOf(existing.salePrice));
                case "already-claimed" -> plugin.messages().send(player, "already-claimed",
                        "owner", existing.owner);
                case "world-disabled" -> plugin.messages().send(player, "world-disabled",
                        "world", chunk.getWorld().getName());
                case "spawn-margin" -> plugin.messages().send(player, "spawn-margin",
                        "margin", String.valueOf(cfg.spawnMargin));
                case "limit-reached" -> sendLimit(player, player.getName());
                default -> plugin.messages().send(player, "not-enough",
                        "need", String.valueOf(price),
                        "have", String.valueOf(available(player)));
            }
            return;
        }
        if (!charge(player, price)) {
            plugin.messages().send(player, "not-enough",
                    "need", String.valueOf(price),
                    "have", String.valueOf(available(player)));
            return;
        }
        Claim claim = new Claim(chunk.getWorld().getName(), chunk.getX(), chunk.getZ(),
                player.getName(), System.currentTimeMillis() + ClaimMath.DAY_MILLIS);
        plugin.store().add(claim);
        plugin.store().addDiamondsSpent(price);
        audit("buy", player.getName() + " " + claim.where() + " price=" + price);
        plugin.messages().send(player, "bought",
                "price", String.valueOf(price),
                "upkeep", String.valueOf(upkeep),
                "paid", formatDate(claim.paidUntil));
    }

    private void sendLimit(Player player, String name) {
        plugin.messages().send(player, "limit-reached",
                "limit", String.valueOf(limitOf(name)),
                "pass-hint", hasNoPass(name) ? plugin.messages().raw("pass-hint") : "");
    }

    private boolean hasNoPass(String name) {
        if (plugin.store().limitOverride(name) != null) {
            return false; // лимит выдан вручную — подсказка про проходку не нужна
        }
        return plugin.coreApi() == null || !plugin.coreApi().hasActivePass(name);
    }

    /** Ключ сообщения с названием роли (для подстановки %role%). */
    public String roleKey(Claim.Role role) {
        return switch (role) {
            case ACCESS -> "role-access";
            case CONTAINER -> "role-container";
            default -> "role-build";
        };
    }

    /** Название роли без разметки — для сообщений и журнала. */
    public String roleName(Claim.Role role) {
        return plugin.messages().plain(roleKey(role));
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
        audit("unclaim", player.getName() + " " + claim.where());
        plugin.messages().send(player, "unclaimed");
    }

    /** Снимает все приваты игрока (после подтверждения /claim unclaim all confirm). */
    public void unclaimAll(Player player) {
        List<Claim> claims = plugin.store().ofOwner(player.getName());
        if (claims.isEmpty()) {
            plugin.messages().send(player, "unclaim-all-none");
            return;
        }
        for (Claim claim : claims) {
            plugin.store().remove(claim);
        }
        plugin.store().markDirty();
        audit("unclaim-all", player.getName() + " count=" + claims.size());
        plugin.messages().send(player, "unclaim-all-done",
                "count", String.valueOf(claims.size()));
    }

    /**
     * Ставит приветствие текущему привату владельца; пустая строка снимает его.
     * Текст проходит через {@link Claim#normalizeGreeting} — без переводов строк
     * и не длиннее {@link Claim#MAX_GREETING}.
     */
    public void setGreeting(Player player, String text) {
        Claim claim = claimHere(player);
        if (claim == null) {
            plugin.messages().send(player, "unclaim-none");
            return;
        }
        if (!claim.isOwner(player.getName())) {
            plugin.messages().send(player, "greeting-not-yours");
            return;
        }
        String normalized = Claim.normalizeGreeting(text);
        if (normalized.isEmpty()) {
            if (!claim.hasGreeting()) {
                plugin.messages().send(player, "greeting-none");
                return;
            }
            claim.greeting = "";
            plugin.store().markDirty();
            audit("greeting-clear", player.getName() + " " + claim.where());
            plugin.messages().send(player, "greeting-cleared");
            return;
        }
        if (normalized.equals(claim.greeting)) {
            plugin.messages().send(player, "greeting-same");
            return;
        }
        claim.greeting = normalized;
        plugin.store().markDirty();
        audit("greeting-set", player.getName() + " " + claim.where() + " text=" + normalized);
        plugin.messages().send(player, "greeting-set", "text", normalized);
    }

    /** Мгновенный обратный выкуп сервером за sellbackPercent от цены. */
    public void sellback(Player player) {
        Claim claim = ownClaimHere(player, "sell-not-yours");
        if (claim == null) {
            return;
        }
        int reward = ClaimMath.sellbackReward(
                plugin.config().priceIn(claim.world), plugin.config().sellbackPercent);
        plugin.store().remove(claim);
        audit("sellback", player.getName() + " " + claim.where() + " reward=" + reward);
        int dropped = reward > 0 ? DiamondItems.give(player, reward) : 0;
        plugin.messages().send(player, "sellback-done", "reward", String.valueOf(reward));
        if (dropped > 0) {
            plugin.messages().send(player, "withdraw-dropped", "amount", String.valueOf(dropped));
        }
    }

    // ------------------------------------------------------------ p2p sale --

    /** Выставить/снять продажу. price 0 = снять с продажи. */
    public void setSale(Player player, int price) {
        Claim claim = ownClaimHere(player, "sell-not-yours");
        if (claim == null) {
            return;
        }
        if (price > 0 && claim.debtDays > 0) {
            // Покупатель не должен унаследовать чужие долги: сначала гасим их.
            plugin.messages().send(player, "sell-debt",
                    "days", String.valueOf(claim.debtDays));
            return;
        }
        claim.salePrice = Math.max(0, price);
        plugin.store().markDirty();
        audit(price > 0 ? "sale-set" : "sale-clear",
                player.getName() + " " + claim.where() + " price=" + claim.salePrice);
        if (price > 0) {
            plugin.messages().send(player, "sell-set", "price", String.valueOf(price));
        } else {
            plugin.messages().send(player, "sell-cleared");
        }
    }

    /** Покупка чужого чанка, выставленного на продажу (в точке, где стоит покупатель). */
    public void buyFromPlayer(Player player) {
        Chunk chunk = player.getLocation().getChunk();
        buyFromPlayer(player, chunk.getX(), chunk.getZ());
    }

    /**
     * Покупка чанка, выставленного игроком, по координатам в текущем мире:
     * витриной `/claim forsale` можно пользоваться, не бегая по карте.
     */
    public void buyFromPlayer(Player player, int chunkX, int chunkZ) {
        String world = player.getWorld().getName();
        if (!plugin.config().worldEnabled(world)) {
            plugin.messages().send(player, "world-disabled", "world", world);
            return;
        }
        Claim claim = plugin.store().claimAt(world, chunkX, chunkZ);
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
        if (plugin.store().count(player.getName()) >= limitOf(player.getName())) {
            sendLimit(player, player.getName());
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

        List<String> formerMembers = new ArrayList<>(claim.members);
        claim.owner = player.getName();
        claim.clearMembers();
        claim.salePrice = 0;
        claim.debtDays = 0;
        plugin.store().markDirty();
        audit("sale", player.getName() + " <- " + seller + " " + claim.where()
                + " price=" + price + " tax=" + tax);

        plugin.messages().send(player, "buy-done",
                "seller", seller, "price", String.valueOf(price), "tax", String.valueOf(tax));
        String sellerText = plugin.messages().plain("buy-sold-notice",
                "x", String.valueOf(claim.x), "z", String.valueOf(claim.z),
                "price", String.valueOf(price), "buyer", player.getName(),
                "net", String.valueOf(net), "tax", String.valueOf(tax));
        Player sellerOnline = Bukkit.getPlayerExact(seller);
        if (sellerOnline != null && authenticated(sellerOnline)) {
            plugin.messages().send(sellerOnline, "buy-sold",
                    "x", String.valueOf(claim.x), "z", String.valueOf(claim.z),
                    "price", String.valueOf(price), "buyer", player.getName(),
                    "net", String.valueOf(net), "tax", String.valueOf(tax));
        } else {
            plugin.store().addNotice(seller, sellerText);
        }
        for (String member : formerMembers) {
            String text = plugin.messages().plain("notice-sold-member",
                    "where", claim.where(), "seller", seller, "buyer", player.getName());
            Player memberOnline = Bukkit.getPlayerExact(member);
            if (memberOnline != null && authenticated(memberOnline)) {
                plugin.messages().send(memberOnline, "notice-sold-member",
                        "where", claim.where(), "seller", seller, "buyer", player.getName());
            } else {
                plugin.store().addNotice(member, text);
            }
        }
    }

    // ------------------------------------------------------------ members --

    public boolean addMember(Player player, String name, boolean everywhere, Claim.Role role) {
        List<Claim> targets = targets(player, everywhere);
        if (targets == null) {
            return false;
        }
        Player target = Bukkit.getPlayerExact(name);
        if (target == null && !knownPlayer(name)) {
            plugin.messages().send(player, "add-unknown", "name", name);
            return false;
        }
        if (role == null) {
            role = Claim.Role.BUILD;
        }
        String roleText = plugin.messages().plain(roleKey(role));
        int changed = 0;
        int updated = 0;
        for (Claim claim : targets) {
            if (claim.isOwner(name)) {
                continue;
            }
            if (claim.isMember(name)) {
                if (claim.roleOf(name) != role) {
                    claim.setRole(name, role);
                    updated++;
                }
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
            claim.setRole(name, role);
            changed++;
        }
        plugin.store().markDirty();
        if (changed > 0) {
            audit("member-add", player.getName() + " -> " + name
                    + " role=" + role.name() + " count=" + changed);
        }
        if (updated > 0) {
            audit("member-role", player.getName() + " -> " + name
                    + " role=" + role.name() + " count=" + updated);
        }
        if (changed == 0 && updated == 0) {
            plugin.messages().send(player, "add-already", "name", name);
            return true;
        }
        if (updated > 0 && changed == 0) {
            plugin.messages().send(player, "role-updated", "name", name, "role", roleText);
            return true;
        }
        if (everywhere) {
            plugin.messages().send(player, "add-all-done", "name", name,
                    "count", String.valueOf(changed), "role", roleText);
        } else {
            plugin.messages().send(player, "add-done", "name", name, "role", roleText);
        }
        Claim first = targets.get(0);
        if (target != null) {
            if (everywhere) {
                plugin.messages().send(target, "member-added-all",
                        "owner", player.getName(), "count", String.valueOf(changed));
            } else {
                plugin.messages().send(target, "member-added",
                        "owner", player.getName(),
                        "x", String.valueOf(first.x), "z", String.valueOf(first.z));
            }
        } else if (everywhere) {
            plugin.store().addNotice(name, plugin.messages().plain("member-added-all",
                    "owner", player.getName(), "count", String.valueOf(changed)));
        } else {
            plugin.store().addNotice(name, plugin.messages().plain("member-added",
                    "owner", player.getName(),
                    "x", String.valueOf(first.x), "z", String.valueOf(first.z)));
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
            changed += claim.removeMember(name) ? 1 : 0;
        }
        plugin.store().markDirty();
        if (changed > 0) {
            audit("member-remove", player.getName() + " -> " + name + " count=" + changed);
        }
        if (changed == 0) {
            plugin.messages().send(player, "remove-not-member", "name", name);
            return true;
        }
        if (everywhere) {
            plugin.messages().send(player, "remove-all-done", "name", name, "count", String.valueOf(changed));
        } else {
            plugin.messages().send(player, "remove-done", "name", name);
        }
        Player target = Bukkit.getPlayerExact(name);
        if (target != null) {
            Claim first = targets.get(0);
            if (everywhere) {
                plugin.messages().send(target, "member-removed-all",
                        "owner", player.getName(), "count", String.valueOf(changed));
            } else {
                plugin.messages().send(target, "member-removed",
                        "owner", player.getName(),
                        "x", String.valueOf(first.x), "z", String.valueOf(first.z));
            }
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

    // ------------------------------------------------------------ transfer --

    /** Передать текущий чанк другому игроку (только владелец). */
    public void transferHere(Player player, String targetName) {
        Claim claim = claimHere(player);
        if (claim == null) {
            plugin.messages().send(player, "unclaim-none");
            return;
        }
        if (!claim.isOwner(player.getName())) {
            plugin.messages().send(player, "sell-not-yours");
            return;
        }
        transfer(claim, player, targetName, false);
    }

    /** Принудительная передача текущего чанка администратором (без проверки владения). */
    public void adminTransferHere(Player admin, String targetName) {
        Claim claim = claimHere(admin);
        if (claim == null) {
            plugin.messages().send(admin, "admin-no-claim");
            return;
        }
        transfer(claim, admin, targetName, true);
    }

    /** Выдать текущий чанк игроку от администратора: свободный — создать, занятый — передать. */
    public void giveHere(Player admin, String targetName) {
        if (!plugin.config().worldEnabled(admin.getLocation().getWorld().getName())) {
            plugin.messages().send(admin, "world-disabled",
                    "world", admin.getLocation().getWorld().getName());
            return;
        }
        Claim claim = claimHere(admin);
        if (claim != null) {
            transfer(claim, admin, targetName, true);
            return;
        }
        if (!knownPlayer(targetName)) {
            plugin.messages().send(admin, "admin-target-unknown", "name", targetName);
            return;
        }
        int limit = limitOf(targetName);
        if (plugin.store().count(targetName) >= limit) {
            plugin.messages().send(admin, "admin-target-limit",
                    "name", targetName, "limit", String.valueOf(limit));
            return;
        }
        Chunk chunk = admin.getLocation().getChunk();
        Claim created = new Claim(chunk.getWorld().getName(), chunk.getX(), chunk.getZ(),
                targetName, System.currentTimeMillis() + ClaimMath.DAY_MILLIS);
        plugin.store().add(created);
        audit("admin-give", admin.getName() + " -> " + targetName + " " + created.where());
        plugin.messages().send(admin, "admin-give-done", "name", targetName);
        Player target = Bukkit.getPlayerExact(targetName);
        if (target != null && authenticated(target)) {
            plugin.messages().send(target, "admin-give-received",
                    "x", String.valueOf(chunk.getX()), "z", String.valueOf(chunk.getZ()));
        } else {
            plugin.store().addNotice(targetName, plugin.messages().plain("admin-give-received",
                    "x", String.valueOf(chunk.getX()), "z", String.valueOf(chunk.getZ())));
        }
    }

    private boolean transfer(Claim claim, Player actor, String targetName, boolean admin) {
        if (targetName == null || targetName.isBlank() || !knownPlayer(targetName)) {
            plugin.messages().send(actor, "transfer-unknown", "name",
                    targetName == null ? "?" : targetName);
            return false;
        }
        if (claim.isOwner(targetName)) {
            plugin.messages().send(actor, "transfer-self");
            return false;
        }
        int limit = limitOf(targetName);
        if (plugin.store().count(targetName) >= limit) {
            plugin.messages().send(actor, "transfer-limit",
                    "name", targetName, "limit", String.valueOf(limit));
            return false;
        }
        String oldOwner = claim.owner;
        int x = claim.x;
        int z = claim.z;
        List<String> formerMembers = new ArrayList<>(claim.members);
        claim.owner = targetName;
        claim.clearMembers();
        claim.salePrice = 0;
        plugin.store().markDirty();
        audit(admin ? "admin-transfer" : "transfer",
                actor.getName() + " " + oldOwner + " -> " + targetName + " " + claim.where());

        if (admin) {
            plugin.messages().send(actor, "admin-transfer-done", "name", targetName, "old", oldOwner);
        } else {
            plugin.messages().send(actor, "transfer-done", "name", targetName);
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target != null && authenticated(target)) {
            plugin.messages().send(target, "transfer-received",
                    "from", actor.getName(), "x", String.valueOf(x), "z", String.valueOf(z));
        } else {
            plugin.store().addNotice(targetName, plugin.messages().plain("transfer-received",
                    "from", actor.getName(), "x", String.valueOf(x), "z", String.valueOf(z)));
        }
        Player oldOnline = Bukkit.getPlayerExact(oldOwner);
        if (oldOnline != null && !oldOwner.equalsIgnoreCase(actor.getName())
                && authenticated(oldOnline)) {
            plugin.messages().send(oldOnline, "transfer-given",
                    "name", targetName, "x", String.valueOf(x), "z", String.valueOf(z));
        }
        for (String member : formerMembers) {
            String text = plugin.messages().plain("member-removed",
                    "owner", oldOwner, "x", String.valueOf(x), "z", String.valueOf(z));
            Player memberOnline = Bukkit.getPlayerExact(member);
            if (memberOnline != null && authenticated(memberOnline)) {
                plugin.messages().send(memberOnline, "member-removed",
                        "owner", oldOwner, "x", String.valueOf(x), "z", String.valueOf(z));
            } else {
                plugin.store().addNotice(member, text);
            }
        }
        return true;
    }

    // ------------------------------------------------------------ treasury --

    public void deposit(Player player, int amount) {
        int have = DiamondItems.count(player.getInventory());
        if (amount <= 0 || have <= 0) {
            plugin.messages().send(player, "deposit-none");
            return;
        }
        if (have < amount) {
            plugin.messages().send(player, "deposit-not-enough",
                    "need", String.valueOf(amount), "have", String.valueOf(have));
            return;
        }
        int collected = DiamondItems.collect(player.getInventory(), amount);
        if (collected <= 0) {
            plugin.messages().send(player, "deposit-none");
            return;
        }
        int balance = plugin.store().addTreasury(player.getName(), collected);
        audit("deposit", player.getName() + " amount=" + collected + " balance=" + balance);
        plugin.messages().send(player, "deposit-done",
                "amount", String.valueOf(collected), "balance", String.valueOf(balance));
    }

    public void withdraw(Player player, int amount) {
        if (!plugin.store().takeTreasury(player.getName(), amount)) {
            plugin.messages().send(player, "withdraw-not-enough",
                    "balance", String.valueOf(plugin.store().treasuryOf(player.getName())));
            return;
        }
        audit("withdraw", player.getName() + " amount=" + amount);
        int dropped = DiamondItems.give(player, amount);
        plugin.messages().send(player, "withdraw-done",
                "amount", String.valueOf(amount),
                "balance", String.valueOf(plugin.store().treasuryOf(player.getName())));
        if (dropped > 0) {
            plugin.messages().send(player, "withdraw-dropped", "amount", String.valueOf(dropped));
        }
    }

    // ------------------------------------------------------------- upkeep --

    /**
     * Ежесуточная рента: добегает пропущенные дни (сервер мог простаивать),
     * сначала закрывает старый долг, затем текущие сутки; по исчерпании льготы
     * чанк освобождается. Вызывается периодически (из плагина, каждый час и при старте).
     */
    public UpkeepResult processUpkeep(long now) {
        ClaimsConfig cfg = plugin.config();
        if (cfg.upkeepPerDay <= 0) {
            return new UpkeepResult(0, 0, 0);
        }
        int chargedTotal = 0;
        int releasedTotal = 0;
        int debtClaims = 0;
        Map<String, Integer> chargedByOwner = new LinkedHashMap<>();

        for (Claim claim : plugin.store().all()) {
            if (!cfg.worldEnabled(claim.world)) {
                continue;
            }
            int rent = cfg.upkeepIn(claim.world);
            if (rent <= 0) {
                continue; // рента в этом мире отключена
            }
            long overdue = ClaimMath.overdueDays(claim.paidUntil, now);
            if (overdue <= 0) {
                // Долг мог остаться с прошлых суток: как только казна пополнена,
                // закрываем его, не дожидаясь следующего дня.
                if (claim.debtDays > 0) {
                    int charged = 0;
                    while (claim.debtDays > 0
                            && plugin.store().takeTreasury(claim.owner, rent)) {
                        claim.debtDays--;
                        charged += rent;
                    }
                    if (charged > 0) {
                        chargedTotal += charged;
                        chargedByOwner.merge(claim.owner, charged, Integer::sum);
                    }
                    if (claim.debtDays > 0) {
                        debtClaims++;
                    }
                }
                continue;
            }
            boolean clamped = overdue > ClaimMath.MAX_CATCHUP_DAYS;
            if (clamped) {
                overdue = ClaimMath.MAX_CATCHUP_DAYS;
            }
            int debtBefore = claim.debtDays;
            int chargedForClaim = 0;
            boolean released = false;
            for (long day = 0; day < overdue; day++) {
                ClaimMath.RentStep step = ClaimMath.settleRentDay(
                        plugin.store().treasuryOf(claim.owner), rent,
                        claim.debtDays, cfg.graceDays);
                if (step.charged() > 0) {
                    plugin.store().takeTreasury(claim.owner, step.charged());
                    chargedForClaim += step.charged();
                }
                claim.paidUntil += ClaimMath.DAY_MILLIS;
                claim.debtDays = step.debtDays();
                if (step.released()) {
                    released = true;
                    break;
                }
            }
            if (clamped) {
                // Не растягиваем «догон» на много часов вперёд, если дата была испорчена.
                plugin.getLogger().warning("[CHRDK_Claims] " + claim.where()
                        + ": просрочка больше " + ClaimMath.MAX_CATCHUP_DAYS
                        + " сут., остаток списан без детализации.");
                claim.paidUntil = Math.max(claim.paidUntil, now);
            }
            if (chargedForClaim > 0) {
                chargedTotal += chargedForClaim;
                chargedByOwner.merge(claim.owner, chargedForClaim, Integer::sum);
            }
            if (released) {
                plugin.store().remove(claim);
                releasedTotal++;
                notifyLoss(claim);
                continue;
            }
            if (claim.debtDays > 0) {
                debtClaims++;
                if (claim.debtDays > debtBefore) {
                    notifyDebt(claim, cfg);
                }
            }
        }
        plugin.store().markDirty();

        if (cfg.notifyRent) {
            chargedByOwner.forEach((owner, amount) -> {
                Player online = Bukkit.getPlayerExact(owner);
                if (online != null && authenticated(online)) {
                    plugin.messages().send(online, "rent-paid-line",
                            "amount", String.valueOf(amount),
                            "balance", String.valueOf(plugin.store().treasuryOf(owner)));
                }
            });
        }
        if (chargedTotal > 0 || releasedTotal > 0) {
            audit("rent", "charged=" + chargedTotal + " released=" + releasedTotal
                    + " debt=" + debtClaims);
            plugin.getLogger().info("[CHRDK_Claims] Рента: списано " + chargedTotal
                    + " ⛁, освобождено чанков " + releasedTotal + ".");
        }
        return new UpkeepResult(chargedTotal, releasedTotal, debtClaims);
    }

    private void notifyLoss(Claim claim) {
        Player online = Bukkit.getPlayerExact(claim.owner);
        if (online != null && authenticated(online)) {
            plugin.messages().send(online, "claim-lost", "where", claim.where());
        } else {
            plugin.store().addNotice(claim.owner,
                    plugin.messages().plain("notice-lost", "where", claim.where()));
        }
    }

    private void notifyDebt(Claim claim, ClaimsConfig cfg) {
        Player online = Bukkit.getPlayerExact(claim.owner);
        if (online != null && authenticated(online)) {
            plugin.messages().send(online, "debt-warning",
                    "where", claim.where(),
                    "days", String.valueOf(claim.debtDays),
                    "grace", String.valueOf(cfg.graceDays));
        } else {
            plugin.store().addNotice(claim.owner, plugin.messages().plain("notice-debt",
                    "where", claim.where(),
                    "days", String.valueOf(claim.debtDays),
                    "grace", String.valueOf(cfg.graceDays)));
        }
    }

    /** Предупреждение после входа: казны не хватает на ближайшую ренту. */
    public void sendRentReminder(Player player) {
        ClaimsConfig cfg = plugin.config();
        if (!cfg.notifyRent) {
            return;
        }
        List<Claim> claims = plugin.store().ofOwner(player.getName());
        if (claims.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long earliest = Long.MAX_VALUE;
        int chunks = 0;
        long needTotal = 0;
        for (Claim claim : claims) {
            if (!cfg.worldEnabled(claim.world)) {
                continue;
            }
            int rent = cfg.upkeepIn(claim.world);
            if (rent <= 0) {
                continue;
            }
            chunks++;
            needTotal += (long) rent * (1L + Math.max(0, claim.debtDays));
            earliest = Math.min(earliest, claim.paidUntil);
        }
        if (chunks == 0) {
            return;
        }
        int balance = plugin.store().treasuryOf(player.getName());
        int need = (int) Math.min(Integer.MAX_VALUE, needTotal);
        if (balance >= need) {
            return;
        }
        plugin.messages().send(player, "rent-warning",
                "balance", String.valueOf(balance),
                "need", String.valueOf(need),
                "date", formatDate(earliest == Long.MAX_VALUE ? now : earliest));
    }

    // ------------------------------------------------------------- others --

    /** Приваты игрока в стабильном порядке (мир, x, z) — чтобы страницы списка не «прыгали». */
    public List<Claim> ownedSorted(String playerName) {
        List<Claim> claims = plugin.store().ofOwner(playerName);
        claims.sort((left, right) -> {
            int byWorld = left.world.compareTo(right.world);
            if (byWorld != 0) {
                return byWorld;
            }
            int byX = Integer.compare(left.x, right.x);
            if (byX != 0) {
                return byX;
            }
            return Integer.compare(left.z, right.z);
        });
        return claims;
    }

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
