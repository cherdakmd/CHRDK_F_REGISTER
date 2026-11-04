package ru.cherdakmd.chrdkregister;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Особые звания за выслугу лет: Bukkit-слой над {@link HonorLedger}.
 *
 * <p>Главное правило: звание <b>не подделывает покупку</b>. Оно не пишет в
 * {@code data.json}, не меняет сроки ранга и историю платежей, не проходит
 * через магазин. Но при проверке привилегий ядро отдаёт
 * {@code max(оплаченный ранг, grantLevel звания)} через
 * {@link ChrdkRegisterApiImpl}, поэтому аддоны (дома, приваты, аукцион,
 * множители, {@code /hat}) видят ту ступень, которую владелец назначил
 * званию в редакторе.</p>
 *
 * <p>Каждая выдача и правка объяснима: публичная причина, дата и автор видны
 * в {@code /honor list} и дублируются в {@code audit/*.jsonl}. Это ответ на
 * риск «возмущений среди других» — решение не тайное и не выглядит как
 * бесплатный донат из магазина.</p>
 */
public final class HonorService {

    public static final String PERMISSION_ADMIN = "chrdkregister.honors.admin";

    private final RegisterPlugin plugin;
    private final HonorStore store;
    private volatile HonorLedger ledger;
    private volatile HonorConfig config;

    public HonorService(RegisterPlugin plugin, HonorLedger ledger,
                        HonorStore store, HonorConfig config) {
        this.plugin = plugin;
        this.ledger = ledger == null ? new HonorLedger() : ledger;
        this.store = store;
        this.config = config == null ? HonorConfig.DEFAULT : config;
    }

    public HonorLedger ledger() {
        return ledger;
    }

    public HonorConfig config() {
        return config;
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** Перечитать только настройки config.yml; состояние на диске не трогаем. */
    public void reload(FileConfiguration file) {
        this.config = HonorConfig.fromConfig(file);
    }

    /**
     * Стартовые выдачи из {@code honors.initial-grants} тоже публичны: их
     * появление дублируется в аудит, иначе первая благодарность была бы
     * единственной выдачей без следа в {@code audit/*.jsonl}.
     */
    public void auditInitialGrants(List<HonorGrant> grants) {
        AuditLog log = plugin.auditLog();
        if (log == null || grants == null) return;
        for (HonorGrant grant : grants) {
            HonorDefinition def = ledger.definition(grant.honorId());
            Map<String, Object> all = new LinkedHashMap<>();
            all.put("honor", grant.honorId());
            all.put("level", def == null ? 0 : def.grantLevel());
            all.put("reason", grant.reason());
            all.put("source", "honors.initial-grants");
            all.put("actor", grant.grantedBy());
            log.log(AuditLog.HONOR_GRANT, grant.grantedBy(), grant.player(), "", all);
        }
    }

    /** Перечитать honors.json с диска (явная команда администратора). */
    public void reloadStore() {
        this.ledger = store.load(config);
    }

    public boolean save() {
        return store.save(ledger);
    }

    /**
     * Эффективная ступень привилегий: купленный ранг или звание, что выше.
     * Используется и ядром ({@code /hat}, метки), и публичным API аддонов.
     */
    public int effectiveLevel(String playerName, int paidLevel) {
        if (!enabled() || playerName == null) return Math.max(0, paidLevel);
        return Math.max(Math.max(0, paidLevel), ledger.grantLevel(playerName));
    }

    /** Активное звание игрока для оформления ника; {@code null} — нет звания. */
    public HonorDefinition active(String playerName) {
        return enabled() ? ledger.active(playerName) : null;
    }

    /** Строка входа звания, если задана; иначе пусто (тогда работает ранг). */
    public String joinLine(String playerName) {
        HonorDefinition def = active(playerName);
        return def == null ? "" : def.joinLine();
    }

    /** Публичный реестр: никто не должен догадываться, «почему ему дали». */
    public List<HonorGrant> publicRegistry() {
        return ledger.publicGrants();
    }

    /** Редактировать звания могут только администраторы и op. */
    public boolean canEdit(CommandSender sender) {
        return sender != null && (sender.hasPermission(PERMISSION_ADMIN) || sender.isOp());
    }

    /** Смотреть GUI реестра может staff админ-панели; менять — только canEdit. */
    public boolean canViewGui(CommandSender sender) {
        if (canEdit(sender)) return true;
        ModerationService moderation = plugin.moderation();
        return moderation != null && moderation.isStaff(sender);
    }

    /* ------------------------------- редактор ------------------------------ */

    /** Создать звание с оформлением по умолчанию; дальше поля правятся в GUI/командах. */
    public boolean create(CommandSender actor, String rawId, String rawName) {
        if (!guard(actor)) return false;
        String id = HonorDefinition.normalizeId(rawId);
        if (!HonorDefinition.validId(id)) {
            plugin.messages().send(actor, "honoradmin.bad-id");
            return false;
        }
        if (ledger.definition(id) != null) {
            plugin.messages().send(actor, "honoradmin.exists", "id", id);
            return false;
        }
        String name = rawName == null || rawName.isBlank() ? id : rawName.trim();
        // Описание звания — не публичная причина выдачи: не подставляем сюда
        // default-reason, чтобы в /honor info не появлялась чужая формулировка.
        HonorDefinition def = new HonorDefinition(id, name, HonorDefinition.DEFAULT_FORMAT,
                "✦", 0, "", "", true);
        if (!ledger.define(def)) {
            plugin.messages().send(actor, "honoradmin.limit");
            return false;
        }
        commit(actor, AuditLog.HONOR_DEFINE, id, Map.of("name", name));
        plugin.messages().send(actor, "honoradmin.created", "id", id, "name", name);
        return true;
    }

    /** Изменить одно поле: name, format, badge, level, description, join, listed. */
    public boolean edit(CommandSender actor, String rawId, String field, String value) {
        if (!guard(actor)) return false;
        HonorDefinition current = ledger.definition(rawId);
        if (current == null) {
            notFound(actor, rawId);
            return false;
        }
        String key = field == null ? "" : field.trim().toLowerCase(java.util.Locale.ROOT);
        if (!List.of("name", "format", "gradient", "badge", "level", "grant-level",
                "description", "join", "join-line", "listed", "visible").contains(key)) {
            plugin.messages().send(actor, "honoradmin.bad-field", "field", field);
            return false;
        }
        if ((key.equals("level") || key.equals("grant-level"))
                && HonorDefinition.parseLevel(value) < 0) {
            plugin.messages().send(actor, "honoradmin.bad-level");
            return false;
        }
        HonorDefinition next = current.with(key, value);
        if (next.equals(current)) {
            plugin.messages().send(actor, "honoradmin.bad-field", "field", field);
            return false;
        }
        ledger.replace(next);
        commit(actor, AuditLog.HONOR_EDIT, next.id(), Map.of("field", key, "value", next.field(key)));
        plugin.messages().send(actor, "honoradmin.updated", "id", next.id(), "field", key);
        return true;
    }

    /**
     * Удалить звание вместе со всеми выдачами. Каждое неявное снятие пишется
     * отдельным {@code honor.revoke}: удаление не должно обходить аудит выдач.
     */
    public boolean remove(CommandSender actor, String rawId) {
        if (!guard(actor)) return false;
        String id = HonorDefinition.normalizeId(rawId);
        List<HonorGrant> revoked = ledger.grantsOfHonor(id);
        if (!ledger.remove(id)) {
            notFound(actor, rawId);
            return false;
        }
        persist(actor);
        audit(actor, AuditLog.HONOR_REMOVE, id, Map.of(
                "revoked", revoked.size(),
                "players", String.join(", ", revoked.stream().map(HonorGrant::player).toList())));
        for (HonorGrant grant : revoked) {
            audit(actor, AuditLog.HONOR_REVOKE, grant.player(),
                    Map.of("honor", id, "source", "honor.remove"));
        }
        plugin.messages().send(actor, "honoradmin.deleted", "id", id);
        return true;
    }

    /* -------------------------------- выдача ------------------------------- */

    /** Выдать звание игроку с публичной причиной; прежнее звание заменяется. */
    public boolean grant(CommandSender actor, String rawPlayer, String rawId, String rawReason) {
        if (!guard(actor)) return false;
        String player = rawPlayer == null ? "" : rawPlayer.trim();
        if (!AccessManager.isValidMinecraftName(player)) {
            plugin.messages().send(actor, "honoradmin.bad-player", "player", player);
            return false;
        }
        String id = HonorDefinition.normalizeId(rawId);
        HonorDefinition def = ledger.definition(id);
        if (def == null) {
            notFound(actor, rawId);
            return false;
        }
        String reason = reason(rawReason);
        if (reason.isEmpty()) {
            plugin.messages().send(actor, "honoradmin.bad-reason");
            return false;
        }
        if (ledger.grantOf(player) == null && ledger.allGrants().size() >= HonorLedger.MAX_GRANTS) {
            plugin.messages().send(actor, "honoradmin.limit");
            return false;
        }
        Player online = Bukkit.getPlayerExact(player);
        String uuid = online == null ? "" : online.getUniqueId().toString();
        HonorGrant previous = ledger.grant(new HonorGrant(player, uuid, id,
                actor.getName(), System.currentTimeMillis(), reason));
        commit(actor, AuditLog.HONOR_GRANT, player,
                Map.of("honor", id, "level", def.grantLevel(), "reason", reason,
                        "replaced", previous == null ? "" : previous.honorId()));
        plugin.messages().send(actor, "honoradmin.granted",
                "player", player, "honor", def.name(), "reason", reason);
        if (online != null) {
            plugin.messages().send(online, "honor.granted-self",
                    "honor", def.name(), "reason", reason);
        }
        if (config.announceGrant()) {
            Bukkit.getServer().broadcast(plugin.messages().get("honor.announce",
                    "player", player, "honor", def.name(), "reason", reason));
        }
        return true;
    }

    /** Изменить публичную причину уже выданного звания, сохранив дату и автора. */
    public boolean editGrantReason(CommandSender actor, String rawPlayer, String rawReason) {
        if (!guard(actor)) return false;
        String player = rawPlayer == null ? "" : rawPlayer.trim();
        HonorGrant current = ledger.grantOf(player);
        if (current == null) {
            plugin.messages().send(actor, "honoradmin.no-grant", "player", player);
            return false;
        }
        String reason = reason(rawReason);
        if (reason.isEmpty() || reason.equals(current.reason())) {
            plugin.messages().send(actor, "honoradmin.bad-field", "field", "reason");
            return false;
        }
        ledger.grant(current.withReason(reason));
        commit(actor, AuditLog.HONOR_EDIT, player,
                Map.of("field", "reason", "value", reason, "honor", current.honorId()));
        plugin.messages().send(actor, "honoradmin.reason-updated",
                "player", current.player(), "reason", reason);
        return true;
    }

    /** Снять звание; пустой id снимает любое. */
    public boolean revoke(CommandSender actor, String rawPlayer, String rawId) {
        if (!guard(actor)) return false;
        String player = rawPlayer == null ? "" : rawPlayer.trim();
        HonorGrant removed = ledger.revoke(player, rawId);
        if (removed == null) {
            plugin.messages().send(actor, "honoradmin.no-grant", "player", player);
            return false;
        }
        commit(actor, AuditLog.HONOR_REVOKE, player, Map.of("honor", removed.honorId()));
        plugin.messages().send(actor, "honoradmin.revoked",
                "player", removed.player(), "honor", ledger.display(removed.honorId()));
        Player online = Bukkit.getPlayerExact(removed.player());
        if (online != null) {
            plugin.messages().send(online, "honor.revoked-self",
                    "honor", ledger.display(removed.honorId()));
        }
        return true;
    }

    /* -------------------------------- утилиты ------------------------------ */

    private boolean guard(CommandSender actor) {
        if (!enabled()) {
            plugin.messages().send(actor, "honor.disabled");
            return false;
        }
        if (!canEdit(actor)) {
            plugin.messages().send(actor, "honoradmin.no-permission");
            return false;
        }
        return true;
    }

    private void notFound(CommandSender actor, String id) {
        plugin.messages().send(actor, "honoradmin.not-found", "id", HonorDefinition.normalizeId(id));
    }

    private String reason(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) value = config.defaultReason();
        int limit = config.maxReasonLength();
        return value.length() > limit ? value.substring(0, limit).trim() : value;
    }

    private void commit(CommandSender actor, String event, String target, Map<String, Object> details) {
        persist(actor);
        audit(actor, event, target, details);
    }

    private void persist(CommandSender actor) {
        if (!save()) {
            plugin.messages().send(actor, "honoradmin.save-failed");
            plugin.getLogger().log(Level.WARNING, "[CHRDK_Core] honors.json не записан после правки");
        }
    }

    private void audit(CommandSender actor, String event, String target, Map<String, Object> details) {
        AuditLog log = plugin.auditLog();
        if (log == null) return;
        String ip = "";
        if (actor instanceof Player player && player.getAddress() != null
                && player.getAddress().getAddress() != null) {
            ip = player.getAddress().getAddress().getHostAddress();
        }
        Map<String, Object> all = new LinkedHashMap<>(details);
        all.put("actor", actor.getName());
        log.log(event, actor.getName(), target, ip, all);
    }
}
