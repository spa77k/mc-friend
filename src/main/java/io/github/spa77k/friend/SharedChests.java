package io.github.spa77k.friend;

import io.github.spa77k.friend.storage.FriendStore;
import io.github.spa77k.friend.storage.Pair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * フレンド1組ごとの共有チェスト。開いている間は1組につき1つの Inventory を2人で使い回し、
 * 同時に開いてもアイテムが増えないようにする。中身は操作の次の tick と、閉じたときに保存する。
 */
public final class SharedChests implements Listener {

    /** 共有チェストの画面を見分ける印。 */
    public static final class Holder implements InventoryHolder {
        private final Pair pair;
        private Inventory inventory;

        private Holder(Pair pair) {
            this.pair = pair;
        }

        public Pair pair() {
            return pair;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final JavaPlugin plugin;
    private final FriendStore store;
    private final int size;
    private final Map<Pair, Inventory> open = new HashMap<>();
    private final Set<Pair> pendingSave = new HashSet<>();

    public SharedChests(JavaPlugin plugin, FriendStore store, int rows) {
        this.plugin = plugin;
        this.store = store;
        this.size = Math.max(1, Math.min(6, rows)) * 9;
    }

    /** 読み込み済みならそれを、なければ保存先から読み込んで返す。読めなければ null。 */
    public Inventory inventory(Pair pair, String title) {
        Inventory inventory = open.get(pair);
        if (inventory != null) {
            return inventory;
        }
        ItemStack[] contents;
        try {
            contents = store.loadChest(pair, size);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load the shared chest of " + pair, e);
            return null;
        }
        Holder holder = new Holder(pair);
        inventory = Bukkit.createInventory(holder, size, title);
        holder.inventory = inventory;
        inventory.setContents(contents);
        open.put(pair, inventory);
        return inventory;
    }

    public boolean isViewed(Pair pair) {
        Inventory inventory = open.get(pair);
        return inventory != null && !inventory.getViewers().isEmpty();
    }

    /** 中身があるか。読み込めないときは、解消で中身を消さないよう「ある」とみなす。 */
    public boolean hasItems(Pair pair) {
        Inventory inventory = open.get(pair);
        if (inventory != null) {
            return !inventory.isEmpty();
        }
        try {
            for (ItemStack item : store.loadChest(pair, size)) {
                if (item != null) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load the shared chest of " + pair, e);
            return true;
        }
    }

    /** フレンドの解消後、読み込み済みのチェストを捨てる。 */
    public void forget(Pair pair) {
        open.remove(pair);
        pendingSave.remove(pair);
    }

    public boolean save(Pair pair) {
        Inventory inventory = open.get(pair);
        if (inventory == null) {
            return true;
        }
        try {
            store.saveChest(pair, inventory.getContents());
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save the shared chest of " + pair, e);
            return false;
        }
    }

    /** 開いている人を閉じさせ、すべて保存する。プラグインを止めるときに使う。 */
    public void closeAll() {
        for (Map.Entry<Pair, Inventory> entry : new ArrayList<>(open.entrySet())) {
            for (HumanEntity viewer : new ArrayList<>(entry.getValue().getViewers())) {
                viewer.closeInventory();
            }
            save(entry.getKey());
        }
        open.clear();
        pendingSave.clear();
    }

    private void saveSoon(Pair pair) {
        if (pendingSave.add(pair)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (pendingSave.remove(pair)) {
                    save(pair);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder holder) {
            saveSoon(holder.pair());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder holder) {
            saveSoon(holder.pair());
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        Pair pair = holder.pair();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Inventory inventory = open.get(pair);
            if (inventory == null || inventory.getHolder() != holder) {
                return;
            }
            pendingSave.remove(pair);
            if (save(pair) && inventory.getViewers().isEmpty()) {
                open.remove(pair);
            }
        });
    }
}
