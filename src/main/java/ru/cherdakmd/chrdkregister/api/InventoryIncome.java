package ru.cherdakmd.chrdkregister.api;

import java.io.IOException;
import java.util.UUID;

/** API 21: подтверждение предметной сдачи перед публикацией денежного обязательства.
 * Intent сначала durable в source. Witness сохраняется ВМЕСТЕ с inventory в playerdata.
 * Изменившийся inventory без witness не считается успешным списанием.
 * API 22: при повторном witness обязателен checkpoint текущего playerdata.
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
    public interface Inventory extends InventoryTransfer.Inventory {
        UUID owner();
        boolean available();
        String witness();
        boolean matches(String snapshot);
        /** Установить storage и witness и синхронно сохранить один playerdata. */
        void consume(String afterInventory, String witness) throws IOException;
        @Override default void apply(String after,String witness) throws IOException { consume(after,witness); }
    }
    public static Decision recover(Intent intent, Inventory inventory) throws IOException {
        if(intent==null) throw new IllegalArgumentException("missing income intent");
        return switch(InventoryTransfer.recover(new InventoryTransfer.Intent(intent.transaction(),intent.owner(),intent.beforeInventory(),intent.afterInventory()),inventory)) {
            case WAIT -> Decision.WAIT;
            case CONFLICT -> Decision.CONFLICT;
            case APPLIED -> Decision.CONSUMED;
        };
    }
    private InventoryIncome() { }
}
