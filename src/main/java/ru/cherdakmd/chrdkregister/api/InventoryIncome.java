package ru.cherdakmd.chrdkregister.api;

import java.io.IOException;
import java.util.UUID;

/** API 21: подтверждение предметной сдачи перед публикацией денежного обязательства.
 * Intent сначала durable в source. Witness сохраняется ВМЕСТЕ с inventory в playerdata.
 * Изменившийся inventory без witness не считается успешным списанием.
 */
public final class InventoryIncome {
    public record Intent(UUID transaction, UUID owner, String beforeInventory, String afterInventory) {
        public Intent {
            if (transaction == null || owner == null || beforeInventory == null || afterInventory == null
                    || beforeInventory.equals(afterInventory) || beforeInventory.length() > 4*1024*1024
                    || afterInventory.length() > 4*1024*1024) throw new IllegalArgumentException("inventory income intent");
        }
    }
    public enum Decision { WAIT, CONSUMED, CONFLICT }
    public interface Inventory {
        UUID owner();
        boolean available();
        String witness();
        boolean matches(String snapshot);
        /** Установить storage и witness и синхронно сохранить один playerdata. */
        void consume(String afterInventory, String witness) throws IOException;
    }
    public static Decision recover(Intent intent, Inventory inventory) throws IOException {
        if (intent == null) throw new IllegalArgumentException("missing income intent");
        if (inventory == null || !intent.owner().equals(inventory.owner()) || !inventory.available()) return Decision.WAIT;
        String token=intent.transaction().toString();
        // Witness сильнее текущего inventory: после saveData игрок мог легитимно переместить вещи.
        if (token.equals(inventory.witness())) return Decision.CONSUMED;
        if (!inventory.matches(intent.beforeInventory())) return Decision.CONFLICT;
        inventory.consume(intent.afterInventory(),token);
        return token.equals(inventory.witness()) && inventory.matches(intent.afterInventory()) ? Decision.CONSUMED : Decision.CONFLICT;
    }
    private InventoryIncome() { }
}
