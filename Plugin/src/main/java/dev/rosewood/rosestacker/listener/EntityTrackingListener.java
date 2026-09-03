package dev.rosewood.rosestacker.listener;

import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosestacker.manager.StackManager;
import dev.rosewood.rosestacker.stack.StackedEntity;
import dev.rosewood.rosestacker.stack.StackingThread;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Keeps the per-player nametag bookkeeping on {@link StackedEntity} honest.
 * <p>
 * Stack nametags are sent as per-player metadata packets. When a client stops tracking an entity and
 * later tracks it again, the server sends it vanilla metadata, so whatever tag we last sent is gone
 * from that client. Dropping our record on track/untrack means the next nametag pass resends it, and
 * lets the pass skip every player whose tag has not changed in the meantime.
 */
public class EntityTrackingListener implements Listener {

    private final RosePlugin rosePlugin;

    public EntityTrackingListener(RosePlugin rosePlugin) {
        this.rosePlugin = rosePlugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityTrack(PlayerTrackEntityEvent event) {
        this.forget(event.getEntity(), event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityUntrack(PlayerUntrackEntityEvent event) {
        this.forget(event.getEntity(), event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        StackManager stackManager = this.rosePlugin.getManager(StackManager.class);
        for (StackingThread stackingThread : stackManager.getStackingThreads().values())
            stackingThread.forgetNametagPlayer(playerId);
    }

    private void forget(Entity entity, UUID playerId) {
        if (!(entity instanceof LivingEntity livingEntity))
            return;

        StackedEntity stackedEntity = this.rosePlugin.getManager(StackManager.class).getStackedEntity(livingEntity);
        if (stackedEntity != null)
            stackedEntity.forgetNametagState(playerId);
    }

}
