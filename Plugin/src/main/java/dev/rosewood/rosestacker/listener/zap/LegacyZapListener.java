package dev.rosewood.rosestacker.listener.zap;

import dev.rosewood.rosestacker.listener.EntityListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PigZapEvent;

public class LegacyZapListener implements Listener {

    private final EntityListener entityListener;

    public LegacyZapListener(EntityListener entityListener) {
        this.entityListener = entityListener;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPigZap(PigZapEvent event) {
        this.entityListener.handleEntityTransformation(event);
    }

}
