package dev.rosewood.rosestacker.listener;

import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosestacker.manager.StackManager;
import dev.rosewood.rosestacker.stack.StackedEntity;
import dev.rosewood.rosestacker.stack.StackedItem;
import dev.rosewood.rosestacker.stack.StackingThread;
import dev.rosewood.rosestacker.utils.ItemUtils;
import dev.rosewood.rosestacker.utils.ThreadUtils;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * Keeps the per-player bookkeeping the display passes rely on honest.
 * <p>
 * Stack nametags are sent as per-player metadata packets. When a client stops tracking an entity and
 * later tracks it again, the server sends it vanilla metadata, so whatever tag we last sent is gone
 * from that client. Dropping our record on track/untrack means the next nametag pass resends it, and
 * lets the pass skip every player whose tag has not changed in the meantime.
 * <p>
 * The same events also maintain the set of players each stack is tracked by, which is what lets the
 * nametag pass run entirely off the main thread: without it the pass has to schedule a task per stack
 * just to call {@link Entity#getTrackedBy()} on the right thread. The stacking tool flag below is
 * maintained for the same reason, so the pass does not need a task per player to read their held item.
 */
public class EntityTrackingListener implements Listener {

    private final RosePlugin rosePlugin;

    public EntityTrackingListener(RosePlugin rosePlugin) {
        this.rosePlugin = rosePlugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityTrack(PlayerTrackEntityEvent event) {
        this.updateTracking(event.getEntity(), event.getPlayer().getUniqueId(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityUntrack(PlayerUntrackEntityEvent event) {
        this.updateTracking(event.getEntity(), event.getPlayer().getUniqueId(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        this.refreshStackingTool(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        ItemUtils.forgetStackingToolHolder(playerId);

        StackManager stackManager = this.rosePlugin.getManager(StackManager.class);
        for (StackingThread stackingThread : stackManager.getStackingThreads().values())
            stackingThread.forgetNametagPlayer(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemUtils.setHoldingStackingTool(player.getUniqueId(), ItemUtils.isStackingTool(player.getInventory().getItem(event.getNewSlot())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        ItemUtils.setHoldingStackingTool(event.getPlayer().getUniqueId(), ItemUtils.isStackingTool(event.getMainHandItem()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player)
            this.refreshStackingToolDelayed(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player)
            this.refreshStackingTool(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDrop(PlayerDropItemEvent event) {
        this.refreshStackingToolDelayed(event.getPlayer());
    }

    /**
     * Commands can hand a player the stacking tool without any inventory event firing, so re-read the
     * held item after any command the player runs.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        this.refreshStackingToolDelayed(event.getPlayer());
    }

    private void refreshStackingTool(Player player) {
        ItemUtils.setHoldingStackingTool(player.getUniqueId(), ItemUtils.isStackingTool(player.getInventory().getItemInMainHand()));
    }

    private void refreshStackingToolDelayed(Player player) {
        // The held item is only correct after the event has been applied
        ThreadUtils.runSyncDelayed(() -> {
            if (player.isOnline())
                this.refreshStackingTool(player);
        }, 1L);
    }

    private void updateTracking(Entity entity, UUID playerId, boolean tracking) {
        StackManager stackManager = this.rosePlugin.getManager(StackManager.class);
        if (entity instanceof LivingEntity livingEntity) {
            StackedEntity stackedEntity = stackManager.getStackedEntity(livingEntity);
            if (stackedEntity == null)
                return;

            stackedEntity.forgetNametagState(playerId);
            if (tracking) {
                stackedEntity.addTrackingPlayer(playerId);
            } else {
                stackedEntity.removeTrackingPlayer(playerId);
            }
        } else if (entity instanceof Item item) {
            StackedItem stackedItem = stackManager.getStackedItem(item);
            if (stackedItem == null)
                return;

            if (tracking) {
                stackedItem.addTrackingPlayer(playerId);
            } else {
                stackedItem.removeTrackingPlayer(playerId);
            }
        }
    }

}
