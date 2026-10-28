package ru.cherdakmd.chrdkclaims;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Покупка/продажа чанков, участники, ежесуточная рента.
 * Единственный личный /bal; предметы добираются только при ручной покупке.
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

    /** Общий лимит игрока: таблица по ступени ранга, без персональных правок (1.4.167). */
    public int baseLimitOf(String playerName) {
        int level = plugin.coreApi() == null ? 0 : plugin.coreApi().rankLevel(playerName);
        java.util.List<Integer> table = plugin.config().rankChunks;
        if (!table.isEmpty()) {
            return table.get(Math.min(Math.max(0, level), table.size() - 1));
        }
        return ClaimMath.effectiveLimit(plugin.config().baseChunks,
                plugin.config().passBonusChunks, level > 0);
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

    /** Личный /bal + обычные алмазы в storage (с защитой от переполнения int). */
    public int available(Player player) {
        long sum = (long) plugin.store().treasuryOf(player.getName())
                + DiamondItems.count(player.getInventory());
        return (int) Math.min(Integer.MAX_VALUE, sum);
    }

    /**
     * Списывает сумму: сначала /bal, недостающее — обычными предметами (сдача на /bal).
     *
     * @return true, если сумма набралась и списана
     */
    public boolean charge(Player player, int amount) {
        return amount >= 0 && plugin.store().transferFunds(Map.of(player.getName(), -(long) amount), player.getName());
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
        if (plugin.store().foreignNearby(chunk.getWorld().getName(), chunk.getX(), chunk.getZ(), player.getName(), List.of())) return "foreign-buffer";
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
            return "finance.not-enough";
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
        synchronized (plugin.store()) {
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
                    case "foreign-buffer" -> plugin.messages().send(player, "foreign-buffer");
                    case "limit-reached" -> sendLimit(player, player.getName());
                    default -> plugin.messages().send(player, "finance.not-enough",
                            "need", String.valueOf(price),
                            "have", String.valueOf(available(player)));
                }
                return;
            }
            if (!charge(player, price)) {
                plugin.messages().send(player, "finance.not-enough",
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
    }

    private void sendLimit(Player player, String name) {
        plugin.messages().send(player, "limit-reached",
                "limit", String.valueOf(limitOf(name)),
                "pass-hint", hasNoPass(name) ? plugin.messages().mini("pass-hint") : "");
    }

    private boolean hasNoPass(String name) {
        if (plugin.store().limitOverride(name) != null) {
            return false; // лимит выдан вручную — подсказка про ранг не нужна
        }
        return plugin.coreApi() == null || plugin.coreApi().rankLevel(name) <= 0;
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
        if (rentalLocked(player, List.of(claim))) return;
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
        if (rentalLocked(player, claims)) return;
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
        synchronized (plugin.store()) {
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
                plugin.store().territory(claim).forEach(c -> c.greeting = "");
                plugin.store().markDirty();
                audit("greeting-clear", player.getName() + " " + claim.where());
                plugin.messages().send(player, "greeting-cleared");
                return;
            }
            if (normalized.equals(claim.greeting)) {
                plugin.messages().send(player, "greeting-same");
                return;
            }
            plugin.store().territory(claim).forEach(c -> c.greeting = normalized);
            plugin.store().markDirty();
            audit("greeting-set", player.getName() + " " + claim.where() + " text=" + normalized);
            plugin.messages().send(player, "greeting-set", "text", normalized);
        }
    }

    /** Мгновенный обратный выкуп сервером за sellbackPercent от цены. */
    public void sellback(Player player) {
        if (plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player) || player.getGameMode() != org.bukkit.GameMode.SURVIVAL) {
            plugin.messages().send(player,"finance.unavailable"); return;
        }
        Claim claim = ownClaimHere(player, "sell-not-yours");
        if (claim == null) {
            return;
        }
        if (rentalLocked(player, List.of(claim))) return;
        int reward = ClaimMath.sellbackReward(
                plugin.config().priceIn(claim.world), plugin.config().sellbackPercent);
        if (!plugin.store().transferFunds(Map.of(player.getName(), (long) reward), null)) {
            plugin.messages().send(player, "finance.unavailable"); return;
        }
        plugin.store().remove(claim);
        audit("sellback", player.getName() + " " + claim.where() + " reward=" + reward);
        plugin.messages().send(player, "finance.sellback-done", "reward", String.valueOf(reward));

    }

    // ------------------------------------------------------------ p2p sale --

    /** Выставить/снять продажу. price 0 = снять с продажи. */
    public void setSale(Player player, int price) {
        synchronized (plugin.store()) {
            Claim claim = ownClaimHere(player, "sell-not-yours");
            if (claim == null) {
                return;
            }
            List<Claim> region = plugin.store().territory(claim);
            if (price > 0 && (rentalLocked(player, region) || region.stream().anyMatch(c -> c.rentOffer > 0))) {
                plugin.messages().send(player, "rent-sale");
                return;
            }
            if (price > 0 && region.stream().anyMatch(c -> c.debtDays > 0)) {
                // Покупатель не должен унаследовать чужие долги: сначала гасим их.
                plugin.messages().send(player, "finance.sell-debt",
                        "days", String.valueOf(region.stream().mapToInt(c -> c.debtDays).max().orElse(0)));
                return;
            }
            region.forEach(c -> c.salePrice = Math.max(0, price));
            plugin.store().markDirty();
            audit(price > 0 ? "sale-set" : "sale-clear",
                    player.getName() + " " + claim.where() + " price=" + claim.salePrice);
            if (price > 0) {
                plugin.messages().send(player, "sell-set", "price", String.valueOf(price));
            } else {
                plugin.messages().send(player, "sell-cleared");
            }
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
        synchronized (plugin.store()) {
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
            List<Claim> region = plugin.store().territory(claim);
            if (region.stream().anyMatch(c -> c.salePrice != claim.salePrice || c.debtDays > 0 || c.rentOffer > 0)
                    || rentalLocked(player, region)) {
                plugin.messages().send(player, "rent-sale");
                return;
            }
            if (!spacing(player, region, player.getName())) return;
            int price = claim.salePrice;
            if (available(player) < price) {
                plugin.messages().send(player, "finance.not-enough",
                        "need", String.valueOf(price), "have", String.valueOf(available(player)));
                return;
            }
            if ((long) plugin.store().count(player.getName()) + region.size() > limitOf(player.getName())) {
                sendLimit(player, player.getName());
                return;
            }
            String seller = claim.owner;
            int tax = ClaimMath.saleTax(price, plugin.config().saleTaxPercent);
            int net = Math.max(0, price - tax);
            var changes = new LinkedHashMap<String, Long>();
            changes.put(player.getName(), -(long) price); changes.merge(seller, (long) net, Long::sum);
            if (!plugin.store().transferFunds(changes, player.getName())) {
                plugin.messages().send(player, "finance.unavailable"); return;
            }
            plugin.store().addTaxBurned(tax);

            List<String> formerMembers = region.stream().flatMap(c -> c.members.stream()).distinct().toList();
            for (Claim part : region) {
                part.owner = player.getName(); part.clearMembers(); part.salePrice = 0;
                part.debtDays = 0; part.rentOffer = 0; part.clearRental();
            }
            plugin.store().mergeAcquired(region);
            plugin.store().markDirty();
            audit("sale", player.getName() + " <- " + seller + " " + claim.where()
                    + " price=" + price + " tax=" + tax);

            plugin.messages().send(player, "buy-done",
                    "seller", seller, "price", String.valueOf(price), "tax", String.valueOf(tax));
            String sellerText = plugin.messages().plain("finance.buy-sold-notice",
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
    }

    // ------------------------------------------------------------ members --

    public boolean addMember(Player player, String name, boolean everywhere, Claim.Role role) {
        synchronized (plugin.store()) {
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
    }

    public boolean removeMember(Player player, String name, boolean everywhere) {
        synchronized (plugin.store()) {
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
        return plugin.store().territory(claim);
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
        if (plugin.store().foreignNearby(chunk.getWorld().getName(), chunk.getX(), chunk.getZ(), targetName, List.of())) {
            plugin.messages().send(admin, "foreign-buffer"); return;
        }
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
        synchronized (plugin.store()) {
            if (targetName == null || targetName.isBlank() || !knownPlayer(targetName)) {
                plugin.messages().send(actor, "transfer-unknown", "name",
                        targetName == null ? "?" : targetName);
                return false;
            }
            if (claim.isOwner(targetName)) {
                plugin.messages().send(actor, "transfer-self");
                return false;
            }
            List<Claim> region = plugin.store().territory(claim);
            if (rentalLocked(actor, region) || !spacing(actor, region, targetName)) return false;
            int limit = limitOf(targetName);
            if ((long) plugin.store().count(targetName) + region.size() > limit) {
                plugin.messages().send(actor, "transfer-limit",
                        "name", targetName, "limit", String.valueOf(limit));
                return false;
            }
            String oldOwner = claim.owner;
            int x = claim.x;
            int z = claim.z;
            List<String> formerMembers = region.stream().flatMap(c -> c.members.stream()).distinct().toList();
            for (Claim part : region) {
                part.owner = targetName; part.clearMembers(); part.salePrice = 0;
                part.rentOffer = 0; part.clearRental();
            }
            plugin.store().mergeAcquired(region);
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
    }

    // ------------------------------------------------------------ treasury --

    public void deposit(Player player, int amount) { plugin.messages().send(player, "finance.bank-commands"); }
    public void withdraw(Player player, int amount) { plugin.messages().send(player, "finance.bank-commands"); }

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
            if (!plugin.store().fundsReady(claim.owner)) continue; // Недоступность банка/миграции — не долг и не удаление.
            if (!cfg.worldEnabled(claim.world)) {
                continue;
            }
            if (claim.rentedAt(now)) continue; // Серверная рента этого оплаченного срока внесена заранее.
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
            boolean unavailable = false;
            for (long day = 0; day < overdue; day++) {
                ClaimMath.RentStep step = ClaimUpkeep.day(plugin.store(), claim, rent, cfg.graceDays);
                if (step == null) { unavailable = true; break; }
                chargedForClaim += step.charged();
                if (step.released()) {
                    released = true;
                    break;
                }
            }
            if (clamped && !unavailable) {
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
            if (unavailable) continue;
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
                    plugin.messages().send(online, "finance.rent-paid-line",
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
            plugin.messages().send(online, "finance.debt-warning",
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
        plugin.messages().send(player, "finance.rent-warning",
                "balance", String.valueOf(balance),
                "need", String.valueOf(need),
                "date", formatDate(earliest == Long.MAX_VALUE ? now : earliest));
    }

    public boolean rentalLocked(org.bukkit.command.CommandSender actor, List<Claim> claims) {
        if (claims.stream().noneMatch(c -> c.rentedAt(System.currentTimeMillis()))) return false;
        plugin.messages().send(actor, "rent-locked");
        return true;
    }

    private boolean spacing(org.bukkit.command.CommandSender actor, List<Claim> moving, String owner) {
        java.util.Set<Claim> ignored = new java.util.HashSet<>(moving);
        if (moving.stream().noneMatch(c -> plugin.store().foreignNearby(c.world, c.x, c.z, owner, ignored))) return true;
        plugin.messages().send(actor, "foreign-buffer");
        return false;
    }

    public void rental(Player player, String action, int price) {
        if (!isAllowed(player)) { plugin.messages().send(player, "need-auth"); return; }
        Claim claim = claimHere(player);
        if (claim == null || !plugin.config().worldEnabled(claim.world)) {
            plugin.messages().send(player, "unclaim-none"); return;
        }
        long now = System.currentTimeMillis();
        String refusal;
        switch (action) {
            case "offer" -> refusal = ClaimRentals.offer(plugin.store(), claim, player.getName(), price, now);
            case "take" -> refusal = ClaimRentals.take(plugin.store(), claim, player.getName(), price, now, serverRent(claim));
            case "stop" -> refusal = ClaimRentals.stop(plugin.store(), claim, player.getName());
            default -> { plugin.messages().send(player, "finance.rent-usage"); return; }
        }
        if (refusal != null) { plugin.messages().send(player, java.util.Set.of("rent-funds", "rent-owner-funds", "rent-usage").contains(refusal) ? "finance." + refusal : refusal); return; }
        audit("rent-" + action, player.getName() + " " + claim.where() + " price=" + claim.rentPrice
                + " offer=" + claim.rentOffer + " until=" + claim.rentUntil);
        plugin.messages().send(player, "rent-updated");
        rentalInfo(player, claim);
    }

    public void rentalInfo(Player player, Claim claim) {
        plugin.messages().send(player, "info-territory", "chunks", plugin.store().territory(claim).size());
        plugin.messages().send(player, "finance.rent-info", "offer", claim.rentOffer,
                "tenant", claim.rentedAt(System.currentTimeMillis()) ? claim.tenant : "—",
                "price", claim.rentPrice, "until", claim.rentedAt(System.currentTimeMillis()) ? formatDate(claim.rentUntil) : "—",
                "renew", claim.rentRenew ? "да" : "нет");
    }

    private int serverRent(Claim claim) {
        return plugin.config().upkeepPerDay <= 0 ? 0 : plugin.config().upkeepIn(claim.world);
    }

    public void processRentals(long now) {
        for (Claim claim : plugin.store().all()) {
            // Отключённый мир: оплаченный срок сохраняется, новых списаний нет.
            int result = ClaimRentals.settle(plugin.store(), claim, now,
                    plugin.config().worldEnabled(claim.world) ? serverRent(claim) : -1);
            if (result != 0) audit(result > 0 ? "rent-renew" : "rent-end", claim.where());
        }
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
