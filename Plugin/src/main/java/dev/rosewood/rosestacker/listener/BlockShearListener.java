package dev.rosewood.rosestacker.listener;

import dev.rosewood.rosestacker.RoseStacker;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Dispenser;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockShearEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

public class BlockShearListener implements Listener {

    private RoseStacker roseStacker;

    public BlockShearListener(RoseStacker roseStacker) {
        this.roseStacker = roseStacker;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockShearSheep(BlockShearEntityEvent event) {
        if (event.getBlock().getType() != Material.DISPENSER)
            return;

        ItemStack tool = event.getTool();
        if (!EntityListener.handleSheepShear(this.roseStacker, tool, event.getEntity()))
            return;

        event.setCancelled(true);

        Dispenser dispenser = (Dispenser) event.getBlock().getState();
        Inventory inventory = dispenser.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() != Material.SHEARS)
                continue;

            if (item.isSimilar(event.getTool())) {
                int fSlot = slot;
                Bukkit.getScheduler().runTask(this.roseStacker, () -> inventory.setItem(fSlot, tool));
                break;
            }
        }
    }

}