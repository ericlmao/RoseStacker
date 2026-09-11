package dev.rosewood.rosestacker.stack;

import dev.rosewood.rosegarden.utils.StringPlaceholders;
import dev.rosewood.rosestacker.RoseStacker;
import dev.rosewood.rosestacker.config.SettingKey;
import dev.rosewood.rosestacker.event.StackGUIOpenEvent;
import dev.rosewood.rosestacker.gui.StackedSpawnerGui;
import dev.rosewood.rosestacker.manager.HologramManager;
import dev.rosewood.rosestacker.manager.LocaleManager;
import dev.rosewood.rosestacker.manager.StackSettingManager;
import dev.rosewood.rosestacker.nms.NMSAdapter;
import dev.rosewood.rosestacker.nms.hologram.Hologram;
import dev.rosewood.rosestacker.nms.spawner.SpawnerType;
import dev.rosewood.rosestacker.nms.spawner.StackedSpawnerTile;
import dev.rosewood.rosestacker.stack.settings.SpawnerStackSettings;
import dev.rosewood.rosestacker.stack.settings.conditions.spawner.ConditionTag;
import dev.rosewood.rosestacker.utils.PersistentDataUtils;
import dev.rosewood.rosestacker.utils.StackerUtils;
import dev.rosewood.rosestacker.utils.ThreadUtils;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Player;

public class StackedSpawner extends Stack<SpawnerStackSettings> {

    /**
     * How many known-good spawn offsets are remembered per spawner. Mob farms are static, so the offsets
     * that produced a valid spawn location last cycle nearly always work again; sized to cover the most
     * spawn locations a single spawn cycle asks for before it has to sample randomly.
     */
    public static final int MAX_CACHED_SPAWN_OFFSETS = 16;

    private int size;
    private StackedSpawnerTile spawnerTile;
    private CreatureSpawner cachedCreatureSpawner;
    private Block block;
    private boolean placedByPlayer;
    private StackedSpawnerGui stackedSpawnerGui;
    private List<Class<? extends ConditionTag>> lastInvalidConditions;
    private SpawnerStackSettings stackSettings;
    private String lastDisplayKey;
    private List<String> lastDisplayStrings;
    private Location hologramLocation;
    private double hologramLocationOffset;
    private long[] cachedSpawnOffsets;
    private int cachedSpawnOffsetCount;

    public StackedSpawner(int size, Block spawner, boolean placedByPlayer, boolean updateDisplay) {
        if (spawner.getType() != Material.SPAWNER)
            throw new IllegalArgumentException("Block must be a spawner");

        this.size = size;
        this.placedByPlayer = placedByPlayer;
        this.stackedSpawnerGui = null;
        this.lastInvalidConditions = new ArrayList<>();

        this.block = spawner;
        this.cachedCreatureSpawner = (CreatureSpawner) this.block.getState();
        this.spawnerTile = NMSAdapter.getHandler().injectStackedSpawnerTile(this);
        this.stackSettings = RoseStacker.getInstance().getManager(StackSettingManager.class).getSpawnerStackSettings(this.spawnerTile.getSpawnerType());

        ThreadUtils.runOnPrimary(() -> this.updateSpawnerProperties(true));
        if (updateDisplay)
            this.updateDisplaySafely();
    }

    public StackedSpawner(int size, Block spawner, boolean placedByPlayer) {
        this(size, spawner, placedByPlayer, true);
    }

    /**
     * This constructor should only be used by the converters and SHOULD NEVER be put into a StackingThread
     *
     * @param size The size of the stack
     * @param location The Location of the stack
     */
    public StackedSpawner(int size, Location location) {
        this.size = size;
        if (location.getWorld() != null)
            this.block = location.getWorld().getBlockAt(location);
    }

    /**
     * @return the StackedSpawnerTile object containing the most up-to-date information about this spawner
     */
    public StackedSpawnerTile getSpawnerTile() {
        return this.spawnerTile;
    }

    /**
     * @return a copy of the spawner's CreatureSpawner state
     * @implNote It is recommended to use {@link #getSpawnerTile} instead as it is updated live, this object is likely to be <i>very</i> stale
     */
    public CreatureSpawner getSpawner() {
        if (this.cachedCreatureSpawner == null && this.block.getType() == Material.SPAWNER)
            this.cachedCreatureSpawner = (CreatureSpawner) this.getBlock().getState();
        return this.cachedCreatureSpawner;
    }

    public Block getBlock() {
        return this.block;
    }

    public void kickOutGuiViewers() {
        if (this.stackedSpawnerGui != null)
            this.stackedSpawnerGui.kickOutViewers();
    }

    public void increaseStackSize(int amount) {
        this.size += amount;
        this.updateSpawnerProperties(false);
        this.updateDisplaySafely();
    }

    public void setStackSize(int size) {
        this.size = size;
        this.updateSpawnerProperties(false);
        this.updateDisplaySafely();
    }

    public void openGui(Player player) {
        StackGUIOpenEvent event = new StackGUIOpenEvent(player, this);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled())
            return;

        if (this.stackedSpawnerGui == null)
            this.stackedSpawnerGui = new StackedSpawnerGui(this);
        this.stackedSpawnerGui.openFor(player);
    }

    public List<Class<? extends ConditionTag>> getLastInvalidConditions() {
        return this.lastInvalidConditions;
    }

    @Override
    public int getStackSize() {
        return this.size;
    }

    @Override
    public Location getLocation() {
        return this.block.getLocation();
    }

    @Override
    public void updateDisplay() {
        if (!SettingKey.SPAWNER_DISPLAY_TAGS.get() || this.stackSettings == null)
            return;

        HologramManager hologramManager = RoseStacker.getInstance().getManager(HologramManager.class);
        LocaleManager localeManager = RoseStacker.getInstance().getManager(LocaleManager.class);

        Location location = this.getHologramLocation();

        int sizeForHologram = SettingKey.SPAWNER_DISPLAY_TAGS_SINGLE.get() ? 0 : 1;
        if (this.size <= sizeForHologram) {
            hologramManager.deleteHologram(location);
            return;
        }

        // If the hologram already exists but nobody is close enough to see it, skip building the
        // display text. The text may go briefly stale for a newly approaching player, but the
        // periodic hologram update task corrects it within one cycle once they become a watcher.
        Hologram hologram = hologramManager.getHologram(location);
        if (hologram != null && hologram.getWatchers().isEmpty())
            return;

        // Rebuilding the text means a locale lookup plus a placeholder and colorify pass per line, so reuse the
        // last result whenever nothing that feeds into it has changed. Only read the values the configured
        // message actually displays: the spawn delay changes every tick for any spawner with a player in
        // range, and including it in the key when the hologram never shows it forced a rebuild every cycle.
        boolean empty = this.spawnerTile.getSpawnerType().isEmpty();
        boolean single = this.size == 1 && !SettingKey.SPAWNER_DISPLAY_TAGS_SINGLE_AMOUNT.get();
        String messageKey = "spawner-hologram-display" + (empty ? "-empty" : "") + (single ? "-single" : "");
        int delay = localeManager.usesPlaceholder(messageKey, "time_remaining", "ticks_remaining") ? this.spawnerTile.getDelay() : 0;
        long totalSpawned = localeManager.usesPlaceholder(messageKey, "total_spawned") ? PersistentDataUtils.getTotalSpawnCount(this.spawnerTile) : 0;
        int maxStackSize = this.stackSettings.getMaxStackSize();
        String displayKey = localeManager.getGeneration() + "|" + this.size + "|" + delay + "|" + totalSpawned
                + "|" + maxStackSize + "|" + messageKey + "|" + this.stackSettings.getDisplayName();

        List<String> displayStrings = this.lastDisplayStrings;
        if (displayStrings == null || !displayKey.equals(this.lastDisplayKey)) {
            displayStrings = localeManager.getLocaleMessages(messageKey, this.getPlaceholders(delay, totalSpawned, maxStackSize));
            this.lastDisplayStrings = displayStrings;
            this.lastDisplayKey = displayKey;
        }

        hologramManager.createOrUpdateHologram(location, displayStrings);
    }

    private StringPlaceholders getPlaceholders(int delay, long totalSpawned, int maxStackSize) {
        return StringPlaceholders.builder("name", this.stackSettings.getDisplayName())
                .add("amount", StackerUtils.formatNumber(this.getStackSize()))
                .add("max_amount", StackerUtils.formatNumber(maxStackSize))
                .add("time_remaining", StackerUtils.formatTicksAsTime(delay))
                .add("ticks_remaining", StackerUtils.formatNumber(delay))
                .add("total_spawned", StackerUtils.formatNumber(totalSpawned))
                .build();
    }

    /**
     * Checks whether {@link #updateDisplay} currently has anything to do. This is cheap enough to call
     * for every loaded spawner from the periodic hologram task, which lets that task avoid scheduling a
     * region task per spawner just to have it discover that nobody can see the hologram.
     *
     * @return true if the display needs to be created, deleted, or refreshed for a watcher
     */
    public boolean needsDisplayUpdate() {
        if (!SettingKey.SPAWNER_DISPLAY_TAGS.get() || this.stackSettings == null)
            return false;

        int sizeForHologram = SettingKey.SPAWNER_DISPLAY_TAGS_SINGLE.get() ? 0 : 1;
        Hologram hologram = RoseStacker.getInstance().getManager(HologramManager.class).getHologram(this.getHologramLocation());
        if (hologram == null)
            return this.size > sizeForHologram; // Needs to be created

        if (this.size <= sizeForHologram)
            return true; // Needs to be deleted

        return !hologram.getWatchers().isEmpty();
    }

    /**
     * @return the Location of this spawner's hologram
     * @implNote The returned Location is shared and must not be mutated by callers. Block#getLocation
     *           allocates, and this is called for every loaded spawner on every hologram cycle from both
     *           {@link #needsDisplayUpdate} and {@link #updateDisplay}. The block never moves, so the only
     *           input that can change is the configured height offset, which is compared against the one
     *           the cached Location was built with so a reload picks the new value up.
     */
    public Location getHologramLocation() {
        double heightOffset = SettingKey.SPAWNER_DISPLAY_TAGS_HEIGHT_OFFSET.get();
        Location hologramLocation = this.hologramLocation;
        if (hologramLocation == null || this.hologramLocationOffset != heightOffset) {
            hologramLocation = this.block.getLocation().add(0.5, heightOffset, 0.5);
            this.hologramLocationOffset = heightOffset;
            this.hologramLocation = hologramLocation;
        }
        return hologramLocation;
    }

    /**
     * @return how many packed spawn offsets are currently cached for this spawner
     */
    public int getCachedSpawnOffsetCount() {
        return this.cachedSpawnOffsetCount;
    }

    /**
     * Gets a cached spawn offset. The value is opaque here; the spawning method owns the packing.
     *
     * @param index The index of the offset, must be less than {@link #getCachedSpawnOffsetCount}
     * @return the packed spawn offset at the index
     */
    public long getCachedSpawnOffset(int index) {
        return this.cachedSpawnOffsets[index];
    }

    /**
     * Replaces the cached spawn offsets with the first entries of a buffer. The buffer is copied, so the
     * caller is free to keep reusing it.
     *
     * @param offsets The buffer of packed offsets to cache
     * @param count How many entries of the buffer to keep, at most {@link #MAX_CACHED_SPAWN_OFFSETS}
     */
    public void setCachedSpawnOffsets(long[] offsets, int count) {
        count = Math.min(count, MAX_CACHED_SPAWN_OFFSETS);
        if (count <= 0) {
            this.cachedSpawnOffsetCount = 0;
            return;
        }

        if (this.cachedSpawnOffsets == null)
            this.cachedSpawnOffsets = new long[MAX_CACHED_SPAWN_OFFSETS];

        System.arraycopy(offsets, 0, this.cachedSpawnOffsets, 0, count);
        this.cachedSpawnOffsetCount = count;
    }

    @Override
    public SpawnerStackSettings getStackSettings() {
        return this.stackSettings;
    }

    public boolean isPlacedByPlayer() {
        return this.placedByPlayer;
    }

    public void updateSpawnerProperties(boolean resetDelay) {
        // Handle the entity type changing
        SpawnerType spawnerType = this.spawnerTile.getSpawnerType();
        this.stackSettings = RoseStacker.getInstance().getManager(StackSettingManager.class).getSpawnerStackSettings(spawnerType);
        if (this.stackSettings == null)
            return;

        if (!spawnerType.isEmpty()) {
            if (this.stackSettings.getSpawnCountStackSizeMultiplier() != -1) this.spawnerTile.setSpawnCount(this.size * this.stackSettings.getSpawnCountStackSizeMultiplier());
            if (this.stackSettings.getMaxSpawnDelay() != -1) this.spawnerTile.setMaxSpawnDelay(this.stackSettings.getMaxSpawnDelay());
            if (this.stackSettings.getMinSpawnDelay() != -1) this.spawnerTile.setMinSpawnDelay(this.stackSettings.getMinSpawnDelay());
            if (this.stackSettings.getPlayerActivationRange() != -1) this.spawnerTile.setRequiredPlayerRange(this.stackSettings.getPlayerActivationRange());
            if (this.stackSettings.getSpawnRange() != -1) this.spawnerTile.setSpawnRange(this.stackSettings.getSpawnRange());

            int delay;
            if (resetDelay) {
                delay = StackerUtils.randomInRange(this.spawnerTile.getMinSpawnDelay(), this.spawnerTile.getMaxSpawnDelay());
            } else {
                delay = this.spawnerTile.getDelay();
            }

            this.spawnerTile.setDelay(delay);
        }

        if (this.block.getState() instanceof CreatureSpawner creatureSpawner)
            this.cachedCreatureSpawner = creatureSpawner;
    }

}
