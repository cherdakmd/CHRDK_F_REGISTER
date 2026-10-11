package ru.cherdakmd.chrdkregister;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Инвентарная клавиатура ввода пароля ({@code /login} без аргументов, при {@code auth.button-login: true}).
 *
 * <p>Каждый клик добавляет символ из слота в буфер и ПЕРЕТАСОВЫВАЕТ раскладку заново: по записи
 * стрима видны лишь позиции кликов, которые между нажатиями ничего не значат, и счётчик длины
 * («Набрано: N») — сам пароль на экран не выводится. Никакие нажатия не доходят до чата, консоли
 * и логов (это inventory-клики, не команды).</p>
 *
 * <p>Подтверждение пробрасывает собранный пароль в обычный {@code handleLoginCommand} — вся
 * защита (троттлинг, эскалационная капча, 2FA, аудит) работает без изменений.</p>
 */
public final class PasswordKeypadService implements Listener {

    private static final int SIZE = 54;
    private static final int LAYOUT_SLOTS = KeypadRules.MAX_LAYOUT_SLOTS;
    private static final int SLOT_BACKSPACE = 45;
    private static final int SLOT_CLEAR = 46;
    private static final int SLOT_PAGE = 47;
    private static final int SLOT_PROGRESS = 49;
    private static final int SLOT_CANCEL = 51;
    private static final int SLOT_CONFIRM = 53;
    private static final ItemStack EMPTY = new ItemStack(Material.AIR);

    private final JavaPlugin plugin;
    private final AccessManager accessManager;
    private final Map<UUID, State> open = new HashMap<>();
    private final Random random = new Random();
    private volatile Messages messages;

    public PasswordKeypadService(JavaPlugin plugin, AccessManager accessManager, Messages messages) {
        this.plugin = plugin;
        this.accessManager = accessManager;
        this.messages = messages;
    }

    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    /**
     * Открывает клавиатуру игроку, если ему нужен парольный вход и функция включена в конфиге.
     *
     * @return true = клавиатура открыта (иначе стоит показать Paper-диалог, как раньше)
     */
    public boolean openIfEligible(Player player) {
        if (!accessManager.config().buttonLogin()) {
            return false;
        }
        try {
            if (accessManager.getStage(player) != AccessManager.PlayerStage.NEEDS_LOGIN) {
                return false;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Не удалось определить стадию входа для " + player.getName(), e);
            return false;
        }
        State state = new State(accessManager.config().maxPasswordLength());
        KeypadHolder holder = new KeypadHolder();
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                messages.get("button-login.title"));
        holder.bind(inventory);
        open.put(player.getUniqueId(), state);
        render(inventory, state, true);
        player.openInventory(inventory);
        messages.send(player, "button-login.opened");
        return true;
    }

    /* ------------------------------- отрисовка ------------------------------- */

    private static final class State {
        KeypadRules.Page page = KeypadRules.Page.LOWER;
        final KeypadRules.Buffer buffer;
        final Character[] layout = new Character[LAYOUT_SLOTS];

        State(int maxPasswordLength) {
            this.buffer = new KeypadRules.Buffer(maxPasswordLength);
        }
    }

    private String symbolsConfig() {
        return accessManager.config().buttonLoginSymbols();
    }

    private void render(Inventory inventory, State state, boolean shuffle) {
        List<Character> chars = KeypadRules.pageChars(state.page, symbolsConfig());
        List<Character> order = shuffle ? KeypadRules.shuffled(chars, random) : chars;
        java.util.Arrays.fill(state.layout, null);
        for (int slot = 0; slot < LAYOUT_SLOTS; slot++) {
            if (slot < order.size()) {
                Character ch = order.get(slot);
                state.layout[slot] = ch;
                inventory.setItem(slot, charItem(ch));
            } else {
                inventory.setItem(slot, EMPTY);
            }
        }
        inventory.setItem(SLOT_BACKSPACE, named(Material.ARROW, messages.get("button-login.backspace")));
        inventory.setItem(SLOT_CLEAR, named(Material.BARRIER, messages.get("button-login.clear")));
        inventory.setItem(SLOT_PAGE, named(Material.COMPASS,
                messages.get("button-login.page-button", "page", pageLabel(state.page))));
        inventory.setItem(SLOT_CANCEL, named(Material.RED_CONCRETE, messages.get("button-login.cancel")));
        inventory.setItem(SLOT_CONFIRM, named(Material.LIME_CONCRETE, messages.get("button-login.confirm")));
        ItemStack progress = new ItemStack(Material.BOOK);
        ItemMeta progressMeta = progress.getItemMeta();
        if (progressMeta != null) {
            progressMeta.displayName(messages.get("button-login.footer-name",
                    "entered", state.buffer.size()));
            progressMeta.lore(messages.getList("button-login.footer-lore"));
            progress.setItemMeta(progressMeta);
        }
        inventory.setItem(SLOT_PROGRESS, progress);
    }

    private ItemStack charItem(char ch) {
        return named(Material.PAPER, messages.get("button-login.char-name", "char", String.valueOf(ch)));
    }

    /** Человекочитаемое имя раскладки (литералы — чтобы MessagesTest видел ключи). */
    private String pageLabel(KeypadRules.Page page) {
        return switch (page) {
            case LOWER -> messages.raw("button-login.page-lower");
            case UPPER -> messages.raw("button-login.page-upper");
            case DIGITS_SYMBOLS -> messages.raw("button-login.page-symbols");
        };
    }

    private static ItemStack named(Material material, net.kyori.adventure.text.Component name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    /* ------------------------------- клики ------------------------------- */

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof KeypadHolder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        State state = open.get(player.getUniqueId());
        if (state == null) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        Inventory inventory = event.getView().getTopInventory();
        if (slot >= 0 && slot < LAYOUT_SLOTS) {
            Character ch = state.layout[slot];
            if (ch != null) {
                if (!state.buffer.append(ch)) {
                    messages.send(player, "button-login.full");
                    return;
                }
                // перетасовка после КАЖДОГО введённого символа — ключ к защите от записи стрима
                render(inventory, state, true);
            }
            return;
        }
        switch (slot) {
            case SLOT_BACKSPACE -> {
                state.buffer.backspace();
                render(inventory, state, false);
            }
            case SLOT_CLEAR -> {
                state.buffer.clear();
                render(inventory, state, false);
            }
            case SLOT_PAGE -> {
                state.page = state.page.next();
                render(inventory, state, true);
            }
            case SLOT_CANCEL -> {
                open.remove(player.getUniqueId());
                player.closeInventory();
                messages.send(player, "button-login.cancelled");
            }
            case SLOT_CONFIRM -> {
                if (state.buffer.isEmpty()) {
                    messages.send(player, "button-login.empty");
                    return;
                }
                String secret = state.buffer.takeSecret();
                open.remove(player.getUniqueId());
                player.closeInventory();
                // пароль уходит туда же, куда и текстовый /login: троттлинг, капча, 2FA, аудит
                Bukkit.getScheduler().runTask(plugin,
                        () -> accessManager.handleLoginCommand(player, secret, null));
            }
            default -> {
                // пустые ячейки управления — ничего
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof KeypadHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof KeypadHolder) {
            open.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        open.remove(event.getPlayer().getUniqueId());
    }

    /** Маркер-holder клавиатуры. */
    private static final class KeypadHolder implements InventoryHolder {

        private Inventory inventory;

        private void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
