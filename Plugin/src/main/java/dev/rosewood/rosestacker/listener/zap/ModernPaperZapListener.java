package dev.rosewood.rosestacker.listener.zap;

import com.destroystokyo.paper.event.entity.EntityZapEvent;
import dev.rosewood.rosestacker.listener.EntityListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class ModernPaperZapListener implements Listener {

    private final EntityListener entityListener;

    public ModernPaperZapListener(EntityListener entityListener) {
        this.entityListener = entityListener;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityZap(EntityZapEvent event) {
        this.entityListener.handleEntityTransformation(event);
    }

}
