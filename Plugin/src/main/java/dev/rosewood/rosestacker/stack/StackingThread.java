package dev.rosewood.rosestacker.stack;

import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosegarden.compatibility.CompatibilityAdapter;
import dev.rosewood.rosegarden.scheduler.task.ScheduledTask;
import dev.rosewood.rosestacker.RoseStacker;
import dev.rosewood.rosestacker.config.SettingKey;
import dev.rosewood.rosestacker.event.EntityStackClearEvent;
import dev.rosewood.rosestacker.event.EntityStackEvent;
import dev.rosewood.rosestacker.event.EntityUnstackEvent;
import dev.rosewood.rosestacker.event.ItemStackClearEvent;
import dev.rosewood.rosestacker.event.ItemStackEvent;
import dev.rosewood.rosestacker.event.PreDropStackedItemsEvent;
import dev.rosewood.rosestacker.hook.WorldGuardHook;
import dev.rosewood.rosestacker.manager.EntityCacheManager;
import dev.rosewood.rosestacker.manager.HologramManager;
import dev.rosewood.rosestacker.manager.StackManager;
import dev.rosewood.rosestacker.manager.StackSettingManager;
import dev.rosewood.rosestacker.nms.NMSAdapter;
import dev.rosewood.rosestacker.nms.NMSHandler;
import dev.rosewood.rosestacker.nms.storage.EntityDataEntry;
import dev.rosewood.rosestacker.nms.storage.StackedEntityDataStorage;
import dev.rosewood.rosestacker.stack.settings.EntityStackSettings;
import dev.rosewood.rosestacker.stack.settings.ItemStackSettings;
import dev.rosewood.rosestacker.utils.BatchedMainThreadExecutor;
import dev.rosewood.rosestacker.utils.DataUtils;
import dev.rosewood.rosestacker.utils.EntityUtils;
import dev.rosewood.rosestacker.utils.ItemUtils;
import dev.rosewood.rosestacker.utils.PersistentDataUtils;
import dev.rosewood.rosestacker.utils.StackerUtils;
import dev.rosewood.rosestacker.utils.ThreadUtils;
import dev.rosewood.rosestacker.utils.VersionUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle.DustOptions;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

public class StackingThread implements StackingLogic, AutoCloseable {

    /**
     * How long a removed entity is remembered for, so a stack whose entity was removed on another thread is
     * not resurrected by a pass that is already holding a reference to it.
     */
    private final static long REMOVED_ENTITY_MEMORY_MS = 5000L;

    /**
     * How many stacks one batched main-thread job handles. Small enough that the executor's per-tick budget
     * can take effect between jobs, large enough that the per-job overhead is amortized away.
     */
    private final static int MAIN_THREAD_BATCH_SIZE = 64;

    private final static Predicate<Entity> ITEM_PREDICATE = x -> x.getType() == VersionUtils.ITEM;

    /**
     * Paper exposes whether an entity is inside the tick range of a player. Looked up once so the unstack
     * pass can skip entities that cannot have changed, while still running on Spigot without it.
     */
    private final static boolean IS_TICKING_SUPPORTED = isTickingSupported();

    private static boolean isTickingSupported() {
        try {
            Entity.class.getMethod("isTicking");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private final RosePlugin rosePlugin;
    private final StackManager stackManager;
    private final EntityCacheManager entityCacheManager;
    private final HologramManager hologramManager;
    private final World targetWorld;
    private final boolean disabled;

    private ScheduledTask entityStackTask, itemStackTask, nametagTask, hologramTask;
    private ScheduledTask entityUnstackTask, entityCleanupTask, entityTtlTask;

    // Replaces a Guava cache that read System.nanoTime() on every access, including from the per-entity
    // culls that run thousands of times a cycle. Pruned by the passes below.
    private final Map<UUID, Long> removedEntities;

    private final Map<UUID, StackedEntity> stackedEntities;
    private final Map<UUID, StackedItem> stackedItems;
    private final Map<Chunk, StackChunkData> stackChunkData;
    private final Set<UUID> pendingEntityUnstackChecks;
    private final Map<UUID, PlayerNametagSnapshot> nametagPlayerSnapshots;

    private final boolean dynamicEntityTags, dynamicItemTags;
    private final double entityDynamicViewRangeSqrd, itemDynamicViewRangeSqrd;
    private final boolean entityDynamicWallDetection, itemDynamicWallDetection;

    private volatile boolean stackingEntities;
    private volatile boolean stackingItems;
    private volatile boolean updatingNametags;
    private volatile boolean updatingHolograms;
    private volatile boolean unstackingEntities;

    public StackingThread(RosePlugin rosePlugin, StackManager stackManager, World targetWorld) {
        this.rosePlugin = rosePlugin;
        this.stackManager = stackManager;
        this.entityCacheManager = this.rosePlugin.getManager(EntityCacheManager.class);
        this.hologramManager = this.rosePlugin.getManager(HologramManager.class);
        this.targetWorld = targetWorld;
        this.disabled = this.stackManager.isWorldDisabled(targetWorld);

        if (!this.disabled) {
            this.entityStackTask = rosePlugin.getScheduler().runTaskTimerAsync(this::stackEntities, 5L, SettingKey.STACK_FREQUENCY.get());
            this.itemStackTask = rosePlugin.getScheduler().runTaskTimerAsync(this::stackItems, 5L, SettingKey.ITEM_STACK_FREQUENCY.get());
            this.nametagTask = rosePlugin.getScheduler().runTaskTimerAsync(this::processNametags, 5L, SettingKey.NAMETAG_UPDATE_FREQUENCY.get());
            this.hologramTask = rosePlugin.getScheduler().runTaskTimerAsync(this::updateHolograms, 5L, SettingKey.HOLOGRAM_UPDATE_FREQUENCY.get());

            long unstackFrequency = SettingKey.UNSTACK_FREQUENCY.get();
            if (unstackFrequency > 0)
                this.entityUnstackTask = rosePlugin.getScheduler().runTaskTimerAsync(this::unstackEntities, 5L, unstackFrequency);

            long cleanupFrequency = SettingKey.ENTITY_RESCAN_FREQUENCY.get();
            if (cleanupFrequency > 0)
                this.entityCleanupTask = rosePlugin.getScheduler().runTaskTimer(this::cleanupOrphanedEntities, 5L, cleanupFrequency);

            long entityStackTtl = SettingKey.ENTITY_UNMODIFIED_STACK_TTL.get();
            if (entityStackTtl > 0)
                this.entityTtlTask = rosePlugin.getScheduler().runTaskTimer(this::cleanupExpiredEntityStacks, 5L, Math.min(entityStackTtl, 20L));
        }

        this.removedEntities = new ConcurrentHashMap<>();
        this.stackedEntities = new ConcurrentHashMap<>();
        this.stackedItems = new ConcurrentHashMap<>();
        this.stackChunkData = new ConcurrentHashMap<>();
        this.pendingEntityUnstackChecks = ConcurrentHashMap.newKeySet();
        this.nametagPlayerSnapshots = new ConcurrentHashMap<>();

        this.dynamicEntityTags = SettingKey.ENTITY_DISPLAY_TAGS.get() && SettingKey.ENTITY_DYNAMIC_TAG_VIEW_RANGE_ENABLED.get();
        this.dynamicItemTags = SettingKey.ITEM_DISPLAY_TAGS.get() && SettingKey.ITEM_DYNAMIC_TAG_VIEW_RANGE_ENABLED.get();

        double entityDynamicViewRange = SettingKey.ENTITY_DYNAMIC_TAG_VIEW_RANGE.get();
        double itemDynamicViewRange = SettingKey.ITEM_DYNAMIC_TAG_VIEW_RANGE.get();

        this.entityDynamicViewRangeSqrd = entityDynamicViewRange * entityDynamicViewRange;
        this.itemDynamicViewRangeSqrd = itemDynamicViewRange * itemDynamicViewRange;

        this.entityDynamicWallDetection = SettingKey.ENTITY_DYNAMIC_TAG_VIEW_RANGE_WALL_DETECTION_ENABLED.get();
        this.itemDynamicWallDetection = SettingKey.ITEM_DYNAMIC_TAG_VIEW_RANGE_WALL_DETECTION_ENABLED.get();

        if (this.disabled)
            return;

        NMSAdapter.getHandler().hijackRandomSource(targetWorld);

        // Load chunk data for all stacks in the world
        for (Chunk chunk : this.targetWorld.getLoadedChunks()) {
            this.loadChunkEntities(Arrays.asList(chunk.getEntities()));
            this.loadChunkBlocks(chunk);
        }

        // Disable AI for all existing stacks in the target world
        this.targetWorld.getLivingEntities().forEach(PersistentDataUtils::applyDisabledAi);
    }

    private void stackEntities() {
        if (this.stackingEntities)
            return;

        boolean entityStackingEnabled = this.stackManager.isEntityStackingEnabled();
        if (!entityStackingEnabled || this.stackManager.isEntityStackingTemporarilyDisabled())
            return;

        this.stackingEntities = true;
        try {
            this.pruneRemovedEntities();

            boolean needsEntityThread = SettingKey.ENTITY_REQUIRE_LINE_OF_SIGHT.get() || SettingKey.ENTITY_DONT_STACK_IF_IN_WATER.get();
            List<StackedEntity> pending = null;
            for (StackedEntity stackedEntity : this.stackedEntities.values()) {
                if (this.isRemoved(stackedEntity)) {
                    this.removeEntityStack(stackedEntity);
                    continue;
                }

                // Every cheap cull runs here, off-thread. The overwhelming majority of stacks fail one of
                // them - AI-disabled farm mobs never move, so hasMoved() alone rejects nearly all of them -
                // and scheduling a main-thread task per stack only to return immediately was a burst of
                // thousands of no-op tasks landing in a single tick every cycle.
                if (!this.passesStackPreChecks(stackedEntity))
                    continue;

                if (!needsEntityThread) {
                    this.tryStackEntityChecked(stackedEntity);
                    continue;
                }

                if (pending == null)
                    pending = new ArrayList<>();
                pending.add(stackedEntity);
            }

            if (pending != null)
                this.submitStackBatches(pending);
        } finally {
            this.stackingEntities = false;
        }
    }

    /**
     * Submits the stacks that survived the async culls to the main thread in batches, so the pass costs one
     * budgeted job per {@value #MAIN_THREAD_BATCH_SIZE} stacks instead of one Bukkit task per stack.
     */
    private void submitStackBatches(List<StackedEntity> pending) {
        BatchedMainThreadExecutor executor = BatchedMainThreadExecutor.getInstance();
        if (executor.isPerRegion()) {
            // Folia owns entities per region, so they cannot be batched into one job
            for (StackedEntity stackedEntity : pending)
                executor.submit(stackedEntity.getEntity(), () -> this.tryStackEntityChecked(stackedEntity));
            return;
        }

        for (int i = 0; i < pending.size(); i += MAIN_THREAD_BATCH_SIZE) {
            List<StackedEntity> batch = pending.subList(i, Math.min(i + MAIN_THREAD_BATCH_SIZE, pending.size()));
            executor.submit(() -> {
                for (StackedEntity stackedEntity : batch)
                    runGuarded(() -> this.tryStackEntityChecked(stackedEntity));
            });
        }
    }

    private void unstackEntities() {
        if (this.unstackingEntities)
            return;

        boolean entityStackingEnabled = this.stackManager.isEntityStackingEnabled();
        if (!entityStackingEnabled || this.stackManager.isEntityUnstackingTemporarilyDisabled())
            return;

        this.unstackingEntities = true;
        try {
            List<PendingUnstackCheck> pending = null;
            for (StackedEntity stackedEntity : this.stackedEntities.values()) {
                LivingEntity entity = stackedEntity.getEntity();
                if (entity == null || stackedEntity.getStackSize() <= 1 || !entity.isValid())
                    continue;

                // An entity outside of any player's tick range is not being simulated, so nothing about it
                // can have changed since the last pass
                if (IS_TICKING_SUPPORTED && !entity.isTicking())
                    continue;

                // Claim the stack before consuming its dirty state, so a check that is already in flight
                // cannot swallow a change that happened after it started
                UUID entityId = entity.getUniqueId();
                if (!this.pendingEntityUnstackChecks.add(entityId))
                    continue;

                if (!stackedEntity.needsUnstackCheck()) {
                    this.pendingEntityUnstackChecks.remove(entityId);
                    continue;
                }

                if (pending == null)
                    pending = new ArrayList<>();
                pending.add(new PendingUnstackCheck(stackedEntity, entityId));
            }

            if (pending != null)
                this.submitUnstackBatches(pending);
        } finally {
            this.unstackingEntities = false;
        }
    }

    /**
     * Submits the stacks that are actually due for an unstack check to the main thread in batches.
     */
    private void submitUnstackBatches(List<PendingUnstackCheck> pending) {
        BatchedMainThreadExecutor executor = BatchedMainThreadExecutor.getInstance();
        if (executor.isPerRegion()) {
            for (PendingUnstackCheck check : pending)
                executor.submit(check.stackedEntity().getEntity(), () -> this.checkUnstackEntity(check.stackedEntity(), check.entityId()));
            return;
        }

        for (int i = 0; i < pending.size(); i += MAIN_THREAD_BATCH_SIZE) {
            List<PendingUnstackCheck> batch = pending.subList(i, Math.min(i + MAIN_THREAD_BATCH_SIZE, pending.size()));
            executor.submit(() -> {
                for (PendingUnstackCheck check : batch)
                    runGuarded(() -> this.checkUnstackEntity(check.stackedEntity(), check.entityId()));
            });
        }
    }

    /**
     * A stack claimed in {@link #pendingEntityUnstackChecks}, along with the id it was claimed under; the
     * stack's own entity can be replaced by an unstack before the check runs.
     */
    private record PendingUnstackCheck(StackedEntity stackedEntity, UUID entityId) { }

    @Override
    public void tryUnstackEntity(StackedEntity stackedEntity) {
        LivingEntity entity = stackedEntity.getEntity();
        if (entity == null || stackedEntity.getStackSize() <= 1 || !entity.isValid())
            return;

        UUID entityId = entity.getUniqueId();
        if (!this.pendingEntityUnstackChecks.add(entityId))
            return;

        // Event-driven callers want this to happen now, not on the next budgeted drain
        ThreadUtils.runOnEntity(entity, () -> this.checkUnstackEntity(stackedEntity, entityId));
    }

    /**
     * Runs the unstack check for a stack that has already been claimed in the pending set. Must run on the
     * entity's thread.
     *
     * @param stackedEntity The stack to check
     * @param entityId The id the stack was claimed under, which is released again when the check finishes
     */
    private void checkUnstackEntity(StackedEntity stackedEntity, UUID entityId) {
        try {
            LivingEntity entity = stackedEntity.getEntity();
            if (entity == null || stackedEntity.getStackSize() <= 1 || !entity.isValid())
                return;

            if (!stackedEntity.shouldStayStacked()) {
                if (stackedEntity.getStackSize() > 1)
                    this.splitEntityStack(stackedEntity);
            } else if (SettingKey.ENTITY_MIN_SPLIT_IF_LOWER.get() && stackedEntity.getStackSize() < stackedEntity.getStackSettings().getMinStackSize()) {
                this.splitEntireStack(stackedEntity);
            }
        } finally {
            this.pendingEntityUnstackChecks.remove(entityId);
        }
    }

    private void splitEntireStack(StackedEntity stackedEntity) {
        LivingEntity entity = stackedEntity.getEntity();
        NMSHandler nmsHandler = NMSAdapter.getHandler();
        StackedEntityDataStorage nbt = stackedEntity.getDataStorage();
        stackedEntity.setDataStorage(nmsHandler.createEntityDataStorage(entity, this.stackManager.getEntityDataStorageType(entity.getType())));
        for (EntityDataEntry entityDataEntry : nbt.getAll())
            entityDataEntry.createEntity(stackedEntity.getLocation(), true, entity.getType());
    }

    private void cleanupOrphanedEntities() {
        this.pruneRemovedEntities();

        // Iterating the level's entity list is only safe on the tick thread, so the scan stays here; what
        // moves out is the work it finds. On a settled world that is nothing at all, and the scan itself is
        // now a validity check and a map lookup per entity instead of a Bukkit metadata string build.
        List<Runnable> orphans = null;
        for (Entity entity : NMSAdapter.getHandler().getEntities(this.targetWorld)) {
            if (this.isRemoved(entity))
                continue;

            if (entity instanceof LivingEntity livingEntity && entity.getType() != EntityType.ARMOR_STAND && entity.getType() != EntityType.PLAYER && !this.isEntityStacked(livingEntity)) {
                if (this.stackManager.isAreaDisabled(entity.getLocation()))
                    continue;

                if (orphans == null)
                    orphans = new ArrayList<>();
                orphans.add(() -> this.createEntityStack(livingEntity, false));
            } else if (entity.getType() == VersionUtils.ITEM) {
                Item item = (Item) entity;
                if (this.isItemStacked(item) || this.stackManager.isAreaDisabled(entity.getLocation()))
                    continue;

                if (orphans == null)
                    orphans = new ArrayList<>();
                orphans.add(() -> this.createItemStack(item, false));
            }
        }

        if (orphans == null)
            return;

        List<Runnable> toCreate = orphans;
        BatchedMainThreadExecutor executor = BatchedMainThreadExecutor.getInstance();
        for (int i = 0; i < toCreate.size(); i += MAIN_THREAD_BATCH_SIZE) {
            List<Runnable> batch = toCreate.subList(i, Math.min(i + MAIN_THREAD_BATCH_SIZE, toCreate.size()));
            executor.submit(() -> batch.forEach(StackingThread::runGuarded));
        }
    }

    private void cleanupExpiredEntityStacks() {
        long entityStackTtl = SettingKey.ENTITY_UNMODIFIED_STACK_TTL.get();
        if (entityStackTtl <= 0)
            return;

        List<StackedEntity> toRemove = this.stackedEntities.values().stream()
                .filter(stackedEntity -> !this.isRemoved(stackedEntity)
                        && stackedEntity.getStackSize() > 1
                        && stackedEntity.getEntity().getTicksLived() - stackedEntity.getLastModifiedTicks() >= entityStackTtl)
                .toList();

        if (toRemove.isEmpty())
            return;

        EntityStackClearEvent entityStackClearEvent = new EntityStackClearEvent(this.targetWorld, toRemove);
        Bukkit.getPluginManager().callEvent(entityStackClearEvent);
        if (entityStackClearEvent.isCancelled())
            return;

        toRemove.stream().map(StackedEntity::getEntity).forEach(this::setRemoved);
        toRemove.stream().map(StackedEntity::getEntity).forEach(LivingEntity::remove);
        this.stackedEntities.values().removeIf(toRemove::contains);
    }

    private void stackItems() {
        if (this.stackingItems)
            return;

        boolean itemStackingEnabled = this.stackManager.isItemStackingEnabled();
        if (!itemStackingEnabled)
            return;

        boolean updateItemNametags = SettingKey.ITEM_DISPLAY_DESPAWN_TIMER_PLACEHOLDER.get();

        this.stackingItems = true;
        try {
            this.pruneRemovedEntities();

            for (StackedItem stackedItem : this.stackedItems.values()) {
                if (this.isRemoved(stackedItem)) {
                    this.removeItemStack(stackedItem);
                    continue;
                }

                if (updateItemNametags)
                    stackedItem.updateDisplaySafely();

                this.tryStackItem(stackedItem);
            }
        } finally {
            this.stackingItems = false;
        }
    }

    public void processNametags() {
        if (this.updatingNametags)
            return;

        if (!this.dynamicEntityTags && !this.dynamicItemTags)
            return;

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());

        // Refresh the per-player snapshots used below for distance culling. On the primary thread
        // these run inline; on Folia the stack loop may use a snapshot from the previous cycle,
        // which only delays tag visibility changes by a single update period.
        Set<UUID> onlinePlayerIds = new HashSet<>();
        for (Player player : players)
            onlinePlayerIds.add(player.getUniqueId());
        this.nametagPlayerSnapshots.keySet().retainAll(onlinePlayerIds);

        boolean asyncDisplayUpdates = StackedEntity.isAsyncDisplayUpdates();
        for (Player player : players) {
            // Positions are read racily throughout this pass already, and the stacking tool flag is
            // maintained from the player's own thread, so the snapshot no longer needs a task per player
            if (asyncDisplayUpdates) {
                this.snapshotPlayer(player, true);
            } else {
                ThreadUtils.runOnEntity(player, () -> this.snapshotPlayer(player, false));
            }
        }

        List<PlayerNametagSnapshot> snapshots = new ArrayList<>(this.nametagPlayerSnapshots.values());
        if (snapshots.isEmpty())
            return;

        this.noteTrackEventAvailability();

        this.updatingNametags = true;
        try {
            // Loop stacks in the outer loop so per-stack work (entity location, display name) is
            // done once per stack instead of once per (player, stack) pair
            if (this.dynamicEntityTags)
                for (StackedEntity stackedEntity : new ArrayList<>(this.stackedEntities.values()))
                    this.processEntityNametags(snapshots, stackedEntity, asyncDisplayUpdates);

            if (this.dynamicItemTags)
                for (StackedItem stackedItem : new ArrayList<>(this.stackedItems.values()))
                    this.processItemNametags(snapshots, stackedItem, asyncDisplayUpdates);
        } finally {
            this.updatingNametags = false;
        }
    }

    private void snapshotPlayer(Player player, boolean asyncDisplayUpdates) {
        UUID playerId = player.getUniqueId();
        if (!player.isValid() || !player.getWorld().equals(this.targetWorld)) {
            this.nametagPlayerSnapshots.remove(playerId);
            return;
        }

        boolean holdingStackingTool = asyncDisplayUpdates
                ? ItemUtils.isHoldingStackingTool(playerId)
                : ItemUtils.isStackingTool(player.getInventory().getItemInMainHand());
        this.nametagPlayerSnapshots.put(playerId, new PlayerNametagSnapshot(player, player.getLocation(), holdingStackingTool));
    }

    private static final int TRACK_EVENT_GRACE_TICKS = 20 * 30;
    private static volatile boolean loggedMissingTrackEvents;
    private long nametagPassesWithPlayers;

    /**
     * Logs once, after players have been online for a while without a single track event arriving, that
     * the display passes are running in their fallback mode, so the difference in packet volume is
     * explainable from the log rather than a mystery.
     */
    private void noteTrackEventAvailability() {
        if (loggedMissingTrackEvents || !StackedEntity.isAsyncDisplayUpdates() || StackedEntity.areTrackEventsObserved())
            return;

        this.nametagPassesWithPlayers++;
        if (this.nametagPassesWithPlayers * SettingKey.NAMETAG_UPDATE_FREQUENCY.get() < TRACK_EVENT_GRACE_TICKS)
            return;

        loggedMissingTrackEvents = true;
        this.rosePlugin.getLogger().info("No PlayerTrackEntityEvent has been observed with players online; this server's entity tracker "
                + "does not appear to fire Paper's track events. Stack nametags are being sent to every nearby player and "
                + "resent each cycle instead of only to tracking players. This is only a performance note.");
    }

    /**
     * Picks who the async display path should send to. Once the server has proven it fires the track events
     * the tracked set is exact; before that (or on a server that never fires them) the set may be empty for
     * every stack, so every nearby player is a recipient. Sending to a client that does not have the entity
     * is harmless: the client drops metadata for unknown entity ids.
     *
     * @param tracking The stack's tracked player set
     * @return the recipient filter, or null if there is nobody to send to
     */
    private static Predicate<Player> recipientFilter(Set<UUID> tracking) {
        if (!StackedEntity.areTrackEventsObserved())
            return player -> true;

        return player -> tracking.contains(player.getUniqueId());
    }

    private void processEntityNametags(List<PlayerNametagSnapshot> snapshots, StackedEntity stackedEntity, boolean asyncDisplayUpdates) {
        LivingEntity entity = stackedEntity.getEntity();
        if (entity == null)
            return;

        // Coarse cull off-thread first. With thousands of stacks and a hundred players, scheduling an
        // entity task per stack just to discover nobody is near it was most of this pass's cost.
        // Reading the position here is racy but harmless; the exact check happens below.
        List<PlayerNametagSnapshot> nearby = this.collectNearbySnapshots(snapshots, entity.getLocation());
        if (nearby == null)
            return;

        // Both the nametag and the stacking tool particle are packets, and the tracked player set is
        // maintained by the track/untrack events, so on Paper this whole pass stays off the main thread
        if (asyncDisplayUpdates) {
            if (!entity.isValid() || entity.isDead())
                return;

            Set<UUID> tracking = stackedEntity.getTrackingPlayers();
            if (tracking.isEmpty() && StackedEntity.areTrackEventsObserved())
                return;

            this.updateEntityNametags(stackedEntity, entity, nearby, recipientFilter(tracking), true);
            return;
        }

        ThreadUtils.runOnEntity(entity, () -> {
            if (!entity.isValid() || entity.isDead())
                return;

            // Only players whose client has this entity can see its tag
            Set<Player> tracking = entity.getTrackedBy();
            if (tracking.isEmpty())
                return;

            this.updateEntityNametags(stackedEntity, entity, nearby, tracking::contains, false);
        });
    }

    /**
     * Sends each nearby player the nametag state this stack should currently have for them.
     *
     * @param stackedEntity The stack being updated
     * @param entity The stack's entity
     * @param nearby The players close enough to possibly see the tag
     * @param tracking Tests whether a player's client currently has the entity
     * @param sendDirectly true to send the packets from the calling thread, false to schedule them
     */
    private void updateEntityNametags(StackedEntity stackedEntity, LivingEntity entity, List<PlayerNametagSnapshot> nearby,
                                      Predicate<Player> tracking, boolean sendDirectly) {
        Location entityLocation = entity.getLocation();
        World entityWorld = entityLocation.getWorld();
        double targetX = entityLocation.getX(), targetY = entityLocation.getY() + entity.getEyeHeight(), targetZ = entityLocation.getZ();
        String displayName = null;
        boolean displayNameComputed = false;
        for (PlayerNametagSnapshot snapshot : nearby) {
            Player player = snapshot.player();
            if (!player.isValid() || !tracking.test(player))
                continue;

            Location playerLocation = snapshot.location();
            if (!entityWorld.equals(playerLocation.getWorld()))
                continue;

            double distanceSqrd = playerLocation.distanceSquared(entityLocation);
            if (distanceSqrd > StackerUtils.ASSUMED_ENTITY_VISIBILITY_RANGE)
                continue;

            boolean visible = distanceSqrd < this.entityDynamicViewRangeSqrd;
            if (visible && this.entityDynamicWallDetection) {
                double playerX = playerLocation.getX(), playerY = playerLocation.getY(), playerZ = playerLocation.getZ();
                visible = stackedEntity.checkLineOfSight(player.getUniqueId(), playerX, playerY, playerZ, targetX, targetY, targetZ,
                        () -> EntityUtils.hasLineOfSight(entityWorld, playerX, playerY, playerZ, targetX, targetY, targetZ, 0.75, true));
            }

            if (!displayNameComputed) {
                // Also computes the isDisplayNameVisible state, so this must be called first
                displayName = stackedEntity.getDisplayName();
                displayNameComputed = true;
            }

            boolean displayNameVisible = stackedEntity.isDisplayNameVisible() && visible;
            boolean sendNametag = stackedEntity.markNametagSent(player.getUniqueId(), displayName, displayNameVisible);
            boolean spawnParticles = visible && snapshot.holdingStackingTool();
            if (!sendNametag && !spawnParticles)
                continue; // Player already has this exact tag, nothing to send

            Location particleLocation = null;
            DustOptions dustOptions = null;
            if (spawnParticles) {
                particleLocation = entityLocation.clone().add(0, entity.getEyeHeight(true) + 0.75, 0);
                dustOptions = stackedEntity.isUnstackable() ? StackerUtils.UNSTACKABLE_DUST_OPTIONS : StackerUtils.STACKABLE_DUST_OPTIONS;
            }

            if (sendDirectly) {
                if (sendNametag)
                    NMSAdapter.getHandler().updateEntityNameTagForPlayer(player, entity, displayName, displayNameVisible);
                if (spawnParticles)
                    player.spawnParticle(VersionUtils.DUST, particleLocation, 1, 0.0, 0.0, 0.0, 0.0, dustOptions);
                continue;
            }

            String finalDisplayName = displayName;
            boolean finalSpawnParticles = spawnParticles;
            Location finalParticleLocation = particleLocation;
            DustOptions finalDustOptions = dustOptions;
            ThreadUtils.runOnEntity(player, () -> {
                if (!player.isValid())
                    return;

                if (sendNametag)
                    NMSAdapter.getHandler().updateEntityNameTagForPlayer(player, entity, finalDisplayName, displayNameVisible);
                if (finalSpawnParticles)
                    player.spawnParticle(VersionUtils.DUST, finalParticleLocation, 1, 0.0, 0.0, 0.0, 0.0, finalDustOptions);
            });
        }
    }

    private void processItemNametags(List<PlayerNametagSnapshot> snapshots, StackedItem stackedItem, boolean asyncDisplayUpdates) {
        Item item = stackedItem.getItem();
        if (item == null)
            return;

        List<PlayerNametagSnapshot> nearby = this.collectNearbySnapshots(snapshots, item.getLocation());
        if (nearby == null)
            return;

        if (asyncDisplayUpdates) {
            if (!item.isValid() || !item.isCustomNameVisible())
                return;

            Set<UUID> tracking = stackedItem.getTrackingPlayers();
            if (tracking.isEmpty() && StackedEntity.areTrackEventsObserved())
                return;

            this.updateItemNametags(item, nearby, recipientFilter(tracking), true);
            return;
        }

        ThreadUtils.runOnEntity(item, () -> {
            if (!item.isValid() || !item.isCustomNameVisible())
                return;

            Set<Player> tracking = item.getTrackedBy();
            if (tracking.isEmpty())
                return;

            this.updateItemNametags(item, nearby, tracking::contains, false);
        });
    }

    /**
     * Sends each nearby player the nametag visibility this item should currently have for them.
     *
     * @param item The item being updated
     * @param nearby The players close enough to possibly see the tag
     * @param tracking Tests whether a player's client currently has the item
     * @param sendDirectly true to send the packets from the calling thread, false to schedule them
     */
    private void updateItemNametags(Item item, List<PlayerNametagSnapshot> nearby, Predicate<Player> tracking, boolean sendDirectly) {
        Location itemLocation = item.getLocation();
        World itemWorld = itemLocation.getWorld();
        for (PlayerNametagSnapshot snapshot : nearby) {
            Player player = snapshot.player();
            if (!player.isValid() || !tracking.test(player))
                continue;

            Location playerLocation = snapshot.location();
            if (!itemWorld.equals(playerLocation.getWorld()))
                continue;

            double distanceSqrd = playerLocation.distanceSquared(itemLocation);
            if (distanceSqrd > StackerUtils.ASSUMED_ENTITY_VISIBILITY_RANGE)
                continue;

            boolean visible = distanceSqrd < this.itemDynamicViewRangeSqrd;
            if (visible && this.itemDynamicWallDetection)
                visible = EntityUtils.hasLineOfSight(itemWorld, playerLocation.getX(), playerLocation.getY(), playerLocation.getZ(), itemLocation.getX(), itemLocation.getY(), itemLocation.getZ(), 0.75, true);

            if (sendDirectly) {
                NMSAdapter.getHandler().updateEntityNameTagVisibilityForPlayer(player, item, visible);
                continue;
            }

            boolean finalVisible = visible;
            ThreadUtils.runOnEntity(player, () -> {
                if (player.isValid())
                    NMSAdapter.getHandler().updateEntityNameTagVisibilityForPlayer(player, item, finalVisible);
            });
        }
    }

    /**
     * Picks the player snapshots that could possibly see something at a location. This is the cheap
     * off-thread filter; callers re-check on the entity's thread.
     *
     * @return the snapshots within the assumed visibility range, or null if there are none
     */
    private List<PlayerNametagSnapshot> collectNearbySnapshots(List<PlayerNametagSnapshot> snapshots, Location location) {
        World world = location.getWorld();
        if (world == null)
            return null;

        List<PlayerNametagSnapshot> nearby = null;
        for (PlayerNametagSnapshot snapshot : snapshots) {
            Location playerLocation = snapshot.location();
            if (!world.equals(playerLocation.getWorld()))
                continue;

            double dx = playerLocation.getX() - location.getX();
            double dy = playerLocation.getY() - location.getY();
            double dz = playerLocation.getZ() - location.getZ();
            if (dx * dx + dy * dy + dz * dz > StackerUtils.ASSUMED_ENTITY_VISIBILITY_RANGE)
                continue;

            if (nearby == null)
                nearby = new ArrayList<>(4);
            nearby.add(snapshot);
        }

        return nearby;
    }

    private record PlayerNametagSnapshot(Player player, Location location, boolean holdingStackingTool) {}

    /**
     * Forgets everything every stack in this world holds for a player: the nametag state, so it is resent
     * the next time they can see the stack, and the tracking record, since a disconnecting client stops
     * tracking everything at once. Called when a player disconnects.
     *
     * @param playerId The player to forget
     */
    public void forgetNametagPlayer(UUID playerId) {
        for (StackedEntity stackedEntity : this.stackedEntities.values()) {
            stackedEntity.forgetNametagState(playerId);
            stackedEntity.removeTrackingPlayer(playerId);
        }

        for (StackedItem stackedItem : this.stackedItems.values())
            stackedItem.removeTrackingPlayer(playerId);
    }

    private void updateHolograms() {
        if (this.updatingHolograms)
            return;

        this.updatingHolograms = true;
        try {
            this.stackChunkData.values().stream()
                    .flatMap(x -> x.getSpawners().values().stream())
                    .filter(StackedSpawner::needsDisplayUpdate)
                    .forEach(StackedSpawner::updateDisplaySafely);
        } finally {
            this.updatingHolograms = false;
        }
    }

    @Override
    public void close() {
        // Cancel tasks
        if (this.entityStackTask != null)
            this.entityStackTask.cancel();

        if (this.itemStackTask != null)
            this.itemStackTask.cancel();

        if (this.nametagTask != null)
            this.nametagTask.cancel();

        if (this.hologramTask != null)
            this.hologramTask.cancel();

        if (this.entityUnstackTask != null)
            this.entityUnstackTask.cancel();

        if (this.entityCleanupTask != null)
            this.entityCleanupTask.cancel();

        if (this.entityTtlTask != null)
            this.entityTtlTask.cancel();

        // Save all data
        this.saveAllData(true);
    }

    @Override
    public Map<UUID, StackedEntity> getStackedEntities() {
        return this.stackedEntities;
    }

    @Override
    public Map<UUID, StackedItem> getStackedItems() {
        return this.stackedItems;
    }

    @Override
    public Map<Block, StackedBlock> getStackedBlocks() {
        Map<Block, StackedBlock> stackedBlocks = new HashMap<>();
        for (StackChunkData stackChunkData : this.stackChunkData.values())
            stackedBlocks.putAll(stackChunkData.getBlocks());
        return stackedBlocks;
    }

    @Override
    public Map<Block, StackedSpawner> getStackedSpawners() {
        Map<Block, StackedSpawner> stackedSpawners = new HashMap<>();
        for (StackChunkData stackChunkData : this.stackChunkData.values())
            stackedSpawners.putAll(stackChunkData.getSpawners());
        return stackedSpawners;
    }

    @Override
    public StackedEntity getStackedEntity(LivingEntity livingEntity) {
        return this.stackedEntities.get(livingEntity.getUniqueId());
    }

    @Override
    public StackedItem getStackedItem(Item item) {
        return this.stackedItems.get(item.getUniqueId());
    }

    @Override
    public StackedBlock getStackedBlock(Block block) {
        StackChunkData stackChunkData = this.stackChunkData.get(block.getChunk());
        if (stackChunkData == null)
            return null;
        return stackChunkData.getBlock(block);
    }

    @Override
    public StackedSpawner getStackedSpawner(Block block) {
        StackChunkData stackChunkData = this.stackChunkData.get(block.getChunk());
        if (stackChunkData == null)
            return null;
        return stackChunkData.getSpawner(block);
    }

    @Override
    public boolean isEntityStacked(LivingEntity livingEntity) {
        return this.getStackedEntity(livingEntity) != null;
    }

    @Override
    public boolean isItemStacked(Item item) {
        return this.getStackedItem(item) != null;
    }

    @Override
    public boolean isBlockStacked(Block block) {
        return this.getStackedBlock(block) != null;
    }

    @Override
    public boolean isSpawnerStacked(Block block) {
        return this.getStackedSpawner(block) != null;
    }

    @Override
    public void removeEntityStack(StackedEntity stackedEntity) {
        LivingEntity entity = stackedEntity.getEntity();
        if (entity != null) {
            UUID key = stackedEntity.getEntity().getUniqueId();
            this.stackedEntities.remove(key);
            this.setRemoved(entity);
        } else {
            // Entity is null so we have to remove by value instead
            for (Entry<UUID, StackedEntity> entry : this.stackedEntities.entrySet()) {
                if (entry.getValue() == stackedEntity) {
                    this.stackedEntities.remove(entry.getKey());
                    break;
                }
            }
        }
    }

    @Override
    public void removeItemStack(StackedItem stackedItem) {
        Item item = stackedItem.getItem();
        if (item != null) {
            UUID key = stackedItem.getItem().getUniqueId();
            this.stackedItems.remove(key);
            this.setRemoved(item);
        } else {
            // Item is null so we have to remove by value instead
            for (Entry<UUID, StackedItem> entry : this.stackedItems.entrySet()) {
                if (entry.getValue() == stackedItem) {
                    this.stackedItems.remove(entry.getKey());
                    break;
                }
            }
        }
    }

    @Override
    public void removeBlockStack(StackedBlock stackedBlock) {
        Block key = stackedBlock.getBlock();
        stackedBlock.kickOutGuiViewers();

        StackChunkData stackChunkData = this.stackChunkData.get(key.getChunk());
        if (stackChunkData != null)
            stackChunkData.removeBlock(stackedBlock);
    }

    @Override
    public void removeSpawnerStack(StackedSpawner stackedSpawner) {
        Block key = stackedSpawner.getBlock();
        stackedSpawner.kickOutGuiViewers();

        StackChunkData stackChunkData = this.stackChunkData.get(key.getChunk());
        if (stackChunkData != null)
            stackChunkData.removeSpawner(stackedSpawner);
    }

    @Override
    public int removeAllEntityStacks() {
        List<StackedEntity> toRemove = this.stackedEntities.values().stream()
                .filter(x -> x.getEntity() != null && x.getEntity().getType() != EntityType.PLAYER)
                .filter(x -> x.getStackSize() != 1 || SettingKey.MISC_CLEARALL_REMOVE_SINGLE.get())
                .toList();

        EntityStackClearEvent entityStackClearEvent = new EntityStackClearEvent(this.targetWorld, toRemove);
        Bukkit.getPluginManager().callEvent(entityStackClearEvent);
        if (entityStackClearEvent.isCancelled())
            return 0;

        toRemove.stream().map(StackedEntity::getEntity).forEach(this::setRemoved);
        toRemove.stream().map(StackedEntity::getEntity).forEach(LivingEntity::remove);
        this.stackedEntities.values().removeIf(toRemove::contains);

        return toRemove.size();
    }

    @Override
    public int removeAllItemStacks() {
        List<StackedItem> toRemove = new ArrayList<>(this.stackedItems.values());

        ItemStackClearEvent itemStackClearEvent = new ItemStackClearEvent(this.targetWorld, toRemove);
        Bukkit.getPluginManager().callEvent(itemStackClearEvent);
        if (itemStackClearEvent.isCancelled())
            return 0;

        toRemove.stream().map(StackedItem::getItem).forEach(this::setRemoved);
        toRemove.stream().map(StackedItem::getItem).forEach(Item::remove);
        this.stackedItems.values().removeIf(toRemove::contains);

        return toRemove.size();
    }

    @Override
    public void updateStackedEntityKey(LivingEntity oldKey, StackedEntity stackedEntity) {
        this.stackedEntities.remove(oldKey.getUniqueId());
        this.stackedEntities.put(stackedEntity.getEntity().getUniqueId(), stackedEntity);
    }

    @Override
    public StackedEntity splitEntityStack(StackedEntity stackedEntity) {
        EntityUnstackEvent entityUnstackEvent = new EntityUnstackEvent(stackedEntity, new StackedEntity(stackedEntity.getEntity()));
        Bukkit.getPluginManager().callEvent(entityUnstackEvent);
        if (entityUnstackEvent.isCancelled())
            return null;

        LivingEntity oldEntity = stackedEntity.getEntity();
        if (SettingKey.SPAWNER_DISABLE_MOB_AI_OPTIONS_REENABLE_AI_ON_SPLIT.get())
            PersistentDataUtils.reenableEntityAi(oldEntity);

        StackedEntity newlySplit = stackedEntity.decreaseStackSize();
        this.stackedEntities.put(newlySplit.getEntity().getUniqueId(), newlySplit);
        this.tryStackEntity(newlySplit);
        return newlySplit;
    }

    @Override
    public StackedItem splitItemStack(StackedItem stackedItem, int newSize) {
        World world = stackedItem.getLocation().getWorld();
        if (world == null)
            return null;

        int splitSize = stackedItem.getStackSize() - newSize;
        stackedItem.setStackSize(newSize);
        stackedItem.getItem().setPickupDelay(60);

        ItemStack newItemStack = stackedItem.getItem().getItemStack().clone();
        return this.dropItemStack(newItemStack, splitSize, stackedItem.getLocation(), true);
    }

    @Override
    public StackedEntity createEntityStack(LivingEntity livingEntity, boolean tryStack) {
        if (!this.stackManager.isEntityStackingEnabled())
            return null;

        if (livingEntity instanceof Player || livingEntity instanceof ArmorStand)
            return null;

        StackedEntity newStackedEntity = new StackedEntity(livingEntity);
        this.stackedEntities.put(livingEntity.getUniqueId(), newStackedEntity);

        if (tryStack && this.canEntityInstantStack()) {
            newStackedEntity.setNewlyCreated(true);
            try {
                this.tryStackEntity(newStackedEntity);
            } finally {
                newStackedEntity.setNewlyCreated(false);
            }
        }

        return newStackedEntity;
    }

    private boolean canEntityInstantStack() {
        return SettingKey.ENTITY_INSTANT_STACK.get() && (SettingKey.MISC_MYTHICMOBS_ALLOW_STACKING.get());
    }

    @Override
    public StackedItem createItemStack(Item item, boolean tryStack) {
        if (!this.stackManager.isItemStackingEnabled())
            return null;

        ItemStackSettings itemStackSettings = this.rosePlugin.getManager(StackSettingManager.class).getItemStackSettings(item);
        if (itemStackSettings != null && !itemStackSettings.isStackingEnabled())
            return null;

        StackedItem newStackedItem = new StackedItem(item.getItemStack().getAmount(), item, false);
        this.stackedItems.put(item.getUniqueId(), newStackedItem);

        if (tryStack && SettingKey.ITEM_INSTANT_STACK.get()) {
            newStackedItem.setNewlyCreated(true);
            try {
                this.tryStackItem(newStackedItem);
            } finally {
                newStackedItem.setNewlyCreated(false);
            }
        }

        // Only update the display after stacking to avoid needing to calculate the name unnecessarily
        if (newStackedItem.getStackSize() > 0)
            newStackedItem.updateDisplaySafely();

        return newStackedItem;
    }

    @Override
    public StackedBlock createBlockStack(Block block, int amount) {
        if (!this.stackManager.isBlockStackingEnabled() || !this.stackManager.isBlockTypeStackable(block))
            return null;

        StackChunkData stackChunkData = this.stackChunkData.computeIfAbsent(block.getChunk(), x -> new StackChunkData());
        StackedBlock newStackedBlock = new StackedBlock(amount, block);
        stackChunkData.addBlock(newStackedBlock);
        return newStackedBlock;
    }

    @Override
    public StackedSpawner createSpawnerStack(Block block, int amount, boolean placedByPlayer) {
        if (block.getType() != Material.SPAWNER)
            return null;

        CreatureSpawner creatureSpawner = (CreatureSpawner) block.getState();
        EntityType spawnedType = CompatibilityAdapter.getCreatureSpawnerHandler().getSpawnedType(creatureSpawner);
        if (!this.stackManager.isSpawnerStackingEnabled() || !this.stackManager.isSpawnerTypeStackable(spawnedType))
            return null;

        StackChunkData stackChunkData = this.stackChunkData.computeIfAbsent(block.getChunk(), x -> new StackChunkData());
        StackedSpawner newStackedSpawner = new StackedSpawner(amount, block, placedByPlayer);
        stackChunkData.addSpawner(newStackedSpawner);
        return newStackedSpawner;
    }

    @Override
    public void addEntityStack(StackedEntity stackedEntity) {
        if (!this.stackManager.isEntityStackingEnabled())
            return;

        this.stackedEntities.put(stackedEntity.getEntity().getUniqueId(), stackedEntity);

        if (this.canEntityInstantStack())
            this.tryStackEntity(stackedEntity);
    }

    @Override
    public void addItemStack(StackedItem stackedItem) {
        if (!this.stackManager.isItemStackingEnabled())
            return;

        this.stackedItems.put(stackedItem.getItem().getUniqueId(), stackedItem);
        this.tryStackItem(stackedItem);
    }

    @Override
    public void preStackEntities(EntityType entityType, int amount, Location location, SpawnReason spawnReason) {
        World world = location.getWorld();
        if (world == null)
            return;

        ThreadUtils.runOnLocation(location, () -> {
            EntityStackSettings stackSettings = this.rosePlugin.getManager(StackSettingManager.class).getEntityStackSettings(entityType);
            NMSHandler nmsHandler = NMSAdapter.getHandler();
            boolean removeAi = stackSettings.isMobAIDisabled();

            Collection<Entity> nearbyEntities = this.entityCacheManager.getNearbyEntities(location, stackSettings.getMergeRadius(), x -> x.getType() == entityType);
            Set<StackedEntity> nearbyStackedEntities = new HashSet<>();
            for (Entity entity : nearbyEntities) {
                StackedEntity stackedEntity = this.stackManager.getStackedEntity((LivingEntity) entity);
                if (stackedEntity != null)
                    nearbyStackedEntities.add(stackedEntity);
            }

            Set<StackedEntity> updatedEntities = new HashSet<>();
            Set<StackedEntity> newStackedEntities = new HashSet<>();
            switch (this.stackManager.getEntityDataStorageType(entityType)) {
                case NBT -> {
                    for (int i = 0; i < amount; i++) {
                        StackedEntity newStack = this.createNewEntity(nmsHandler, entityType, location, spawnReason, removeAi);
                        Optional<StackedEntity> matchingEntity = nearbyStackedEntities.stream().filter(x ->
                                stackSettings.testCanStackWith(x, newStack, false, true)).findFirst();
                        if (matchingEntity.isPresent()) {
                            matchingEntity.get().increaseStackSize(newStack.getEntity(), false);
                            updatedEntities.add(matchingEntity.get());
                        } else {
                            nearbyStackedEntities.add(newStack);
                            newStackedEntities.add(newStack);
                        }
                    }
                }

                case SIMPLE -> {
                    for (int i = amount; i > 0; i--) {
                        Optional<StackedEntity> matchingEntity = nearbyStackedEntities.stream().filter(x -> stackSettings.testCanStackWith(x, x, false, true)).findFirst();
                        if (matchingEntity.isPresent()) {
                            // Increase stack size by as much as we can
                            int amountToIncrease = Math.min(i, stackSettings.getMaxStackSize() - matchingEntity.get().getStackSize());
                            matchingEntity.get().increaseStackSize(amountToIncrease, false);
                            updatedEntities.add(matchingEntity.get());
                            i -= amountToIncrease;
                        } else {
                            StackedEntity newStack = this.createNewEntity(nmsHandler, entityType, location, spawnReason, removeAi);
                            nearbyStackedEntities.add(newStack);
                            newStackedEntities.add(newStack);
                        }
                    }
                }
            }

            updatedEntities.forEach(StackedEntity::updateDisplaySafely);

            ThreadUtils.runSync(() -> {
                this.stackManager.setEntityStackingTemporarilyDisabled(true);
                for (StackedEntity stackedEntity : newStackedEntities) {
                    LivingEntity entity = stackedEntity.getEntity();
                    this.entityCacheManager.preCacheEntity(entity);
                    nmsHandler.spawnExistingEntity(stackedEntity.getEntity(), spawnReason, SettingKey.SPAWNER_BYPASS_REGION_SPAWNING_RULES.get());
                    if (removeAi)
                        PersistentDataUtils.removeEntityAi(entity);
                    entity.setVelocity(Vector.getRandom().multiply(0.01));
                    this.addEntityStack(stackedEntity);
                    stackedEntity.updateDisplaySafely();
                }
                this.stackManager.setEntityStackingTemporarilyDisabled(false);
            });
        });
    }

    private StackedEntity createNewEntity(NMSHandler nmsHandler, EntityType entityType, Location location, SpawnReason spawnReason, boolean removeAi) {
        LivingEntity entity = nmsHandler.createNewEntityUnspawned(entityType, location, spawnReason);
        if (removeAi)
            PersistentDataUtils.removeEntityAi(entity);

        return new StackedEntity(entity);
    }

    @Override
    public void preStackEntities(EntityType entityType, int amount, Location location) {
        this.preStackEntities(entityType, amount, location, SpawnReason.CUSTOM);
    }

    @Override
    public void preStackItems(Collection<ItemStack> items, Location location, boolean dropNaturally) {
        if (location.getWorld() == null)
            return;

        // Merge items and store their amounts
        Map<ItemStack, Integer> itemStackAmounts = ItemUtils.reduceItemsByCounts(items);

        // Fire the event to allow other plugins to manipulate the items before we stack and drop them
        PreDropStackedItemsEvent event = new PreDropStackedItemsEvent(itemStackAmounts, location);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled())
            return;

        if (itemStackAmounts.isEmpty())
            return;

        // If stacking is disabled, drop the items separated by their max stack size
        if (!this.stackManager.isItemStackingEnabled()) {
            for (Map.Entry<ItemStack, Integer> entry : itemStackAmounts.entrySet()) {
                ItemStack itemStack = entry.getKey();
                int amount = entry.getValue();
                while (amount > 0) {
                    int maxStackSize = itemStack.getMaxStackSize();
                    int stackSize = Math.min(amount, maxStackSize);
                    amount -= stackSize;
                    ItemStack toDrop = itemStack.clone();
                    toDrop.setAmount(stackSize);
                    if (dropNaturally) {
                        location.getWorld().dropItemNaturally(location, toDrop);
                    } else {
                        location.getWorld().dropItem(location, toDrop);
                    }
                }
            }
            return;
        }

        // Drop all the items stacked with the correct amounts
        this.stackManager.setEntityStackingTemporarilyDisabled(true);

        for (Map.Entry<ItemStack, Integer> entry : itemStackAmounts.entrySet()) {
            if (entry.getValue() <= 0)
                continue;

            Item item;
            if (dropNaturally) {
                item = location.getWorld().dropItemNaturally(location, entry.getKey());
            } else {
                item = location.getWorld().dropItem(location, entry.getKey());
            }

            StackedItem stackedItem = new StackedItem(entry.getValue(), item);
            this.addItemStack(stackedItem);
            stackedItem.updateDisplaySafely();
        }

        this.stackManager.setEntityStackingTemporarilyDisabled(false);
    }

    @Override
    public StackedItem dropItemStack(ItemStack itemStack, int amount, Location location, boolean dropNaturally) {
        if (location.getWorld() == null)
            return null;

        if (!this.stackManager.isItemStackingEnabled()) {
            ItemStack clone = itemStack.clone();
            clone.setAmount(amount);
            this.preStackItems(List.of(clone), location, dropNaturally);
            return null;
        }

        this.stackManager.setEntityStackingTemporarilyDisabled(true);

        Item item;
        if (dropNaturally) {
            item = location.getWorld().dropItemNaturally(location, itemStack);
        } else {
            item = location.getWorld().dropItem(location, itemStack);
        }

        StackedItem stackedItem = this.createItemStack(item, false);
        if (stackedItem != null)
            stackedItem.setStackSize(amount);

        this.stackManager.setEntityStackingTemporarilyDisabled(false);

        return stackedItem;
    }

    @Override
    public void loadChunkBlocks(Chunk chunk) {
        if (!chunk.isLoaded())
            return;

        Map<Block, StackedSpawner> stackedSpawners = new ConcurrentHashMap<>();
        if (this.stackManager.isSpawnerStackingEnabled())
            for (StackedSpawner stackedSpawner : DataUtils.readStackedSpawners(chunk))
                stackedSpawners.put(stackedSpawner.getBlock(), stackedSpawner);

        Map<Block, StackedBlock> stackedBlocks = new ConcurrentHashMap<>();
        if (this.stackManager.isBlockStackingEnabled())
            for (StackedBlock stackedBlock : DataUtils.readStackedBlocks(chunk))
                stackedBlocks.put(stackedBlock.getBlock(), stackedBlock);

        if (!stackedSpawners.isEmpty() || !stackedBlocks.isEmpty()) {
            this.stackChunkData.put(chunk, new StackChunkData(stackedSpawners, stackedBlocks));
            Location chunkLocation = new Location(chunk.getWorld(), chunk.getX() << 4, chunk.getWorld().getMinHeight(), chunk.getZ() << 4);
            ThreadUtils.runOnLocation(chunkLocation, () -> {
                stackedSpawners.values().forEach(StackedSpawner::updateDisplaySafely);
                stackedBlocks.values().forEach(StackedBlock::updateDisplaySafely);
            });
        }
    }

    @Override
    public void loadChunkEntities(List<Entity> entities) {
        if (entities.isEmpty())
            return;

        List<StackedEntity> stackedEntities = new ArrayList<>();
        if (this.stackManager.isEntityStackingEnabled()) {
            for (Entity entity : entities) {
                if (!(entity instanceof LivingEntity livingEntity) || entity.getType() == EntityType.ARMOR_STAND || entity.getType() == EntityType.PLAYER)
                    continue;

                StackedEntity stackedEntity = DataUtils.readStackedEntity(livingEntity, this.stackManager.getEntityDataStorageType(entity.getType()));
                if (stackedEntity != null) {
                    this.stackedEntities.put(stackedEntity.getEntity().getUniqueId(), stackedEntity);
                    stackedEntities.add(stackedEntity);
                } else {
                    this.createEntityStack(livingEntity, true);
                }
            }
        }

        List<StackedItem> stackedItems = new ArrayList<>();
        if (this.stackManager.isItemStackingEnabled()) {
            for (Entity entity : entities) {
                if (entity.getType() != VersionUtils.ITEM)
                    continue;

                Item item = (Item) entity;
                StackedItem stackedItem = DataUtils.readStackedItem(item);
                if (stackedItem != null) {
                    this.stackedItems.put(stackedItem.getItem().getUniqueId(), stackedItem);
                    stackedItems.add(stackedItem);
                } else {
                    this.createItemStack(item, true);
                }
            }
        }

        if (!stackedEntities.isEmpty() || !stackedItems.isEmpty()) {
            Entity entity = !stackedEntities.isEmpty() ? stackedEntities.get(0).getEntity() : stackedItems.get(0).getItem();
            ThreadUtils.runOnEntity(entity, () -> {
                stackedEntities.forEach(StackedEntity::updateDisplaySafely);
                stackedItems.forEach(StackedItem::updateDisplaySafely);
            });
        }
    }

    @Override
    public void saveChunkBlocks(Chunk chunk, boolean clearStored) {
        StackChunkData stackChunkData = this.stackChunkData.get(chunk);
        if (stackChunkData == null)
            return;

        if (this.stackManager.isSpawnerStackingEnabled()) {
            if (clearStored) {
                stackChunkData.getSpawners().values().forEach(stack -> {
                    this.hologramManager.deleteHologram(stack.getHologramLocation());
                    stack.kickOutGuiViewers();
                });
            }
            DataUtils.writeStackedSpawners(stackChunkData.getSpawners().values(), chunk);
        }

        if (this.stackManager.isBlockStackingEnabled()) {
            if (clearStored) {
                stackChunkData.getBlocks().values().forEach(stack -> {
                    this.hologramManager.deleteHologram(stack.getHologramLocation());
                    stack.kickOutGuiViewers();
                });
            }
            DataUtils.writeStackedBlocks(stackChunkData.getBlocks().values(), chunk);
        }

        if (clearStored)
            this.stackChunkData.remove(chunk);
    }

    @Override
    public void saveChunkEntities(List<Entity> entities, boolean clearStored) {
        // Direct loops; this runs on the critical path of chunk unloading, where the stream chains this
        // replaces built several intermediate lists per unload
        List<Stack<?>> stacks = new ArrayList<>(entities.size());
        boolean entityStackingEnabled = this.stackManager.isEntityStackingEnabled();
        boolean itemStackingEnabled = this.stackManager.isItemStackingEnabled();
        for (Entity entity : entities) {
            if (entityStackingEnabled && entity instanceof LivingEntity && entity.getType() != EntityType.ARMOR_STAND && entity.getType() != EntityType.PLAYER) {
                StackedEntity stackedEntity = this.stackedEntities.get(entity.getUniqueId());
                if (stackedEntity != null)
                    stacks.add(stackedEntity);
            } else if (itemStackingEnabled && entity.getType() == VersionUtils.ITEM) {
                StackedItem stackedItem = this.stackedItems.get(entity.getUniqueId());
                if (stackedItem != null)
                    stacks.add(stackedItem);
            }
        }

        this.saveChunkEntityStacks(stacks, clearStored);
    }

    @Override
    public <T extends Stack<?>> void saveChunkEntityStacks(List<T> stacks, boolean clearStored) {
        boolean entityStackingEnabled = this.stackManager.isEntityStackingEnabled();
        boolean itemStackingEnabled = this.stackManager.isItemStackingEnabled();
        for (Stack<?> stack : stacks) {
            runGuarded(() -> {
                if (entityStackingEnabled && stack instanceof StackedEntity stackedEntity) {
                    // Unloading and shutdown always write, no matter what the dirty state says
                    DataUtils.writeStackedEntity(stackedEntity);
                    if (clearStored)
                        this.stackedEntities.remove(stackedEntity.getEntity().getUniqueId());
                } else if (itemStackingEnabled && stack instanceof StackedItem stackedItem) {
                    DataUtils.writeStackedItem(stackedItem);
                    if (clearStored)
                        this.stackedItems.remove(stackedItem.getItem().getUniqueId());
                }
            });
        }
    }

    /**
     * Runs one stack's share of a batched main-thread job, so a failure stays with the stack that caused it.
     * <p>
     * A batch is a single job covering up to {@value #MAIN_THREAD_BATCH_SIZE} stacks, and an exception
     * escaping one of them used to take every stack behind it in the batch with it: their saves never ran,
     * and for the unstack pass their claims in {@link #pendingEntityUnstackChecks} were never released, so
     * they stopped being checked for as long as their entity lived.
     *
     * @param action The work for one stack
     */
    private static void runGuarded(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            RoseStacker.getInstance().getLogger().log(Level.SEVERE, "An error occurred while processing a stack", t);
        }
    }

    @Override
    public void saveAllData(boolean clearStored) {
        // Save stacked blocks and spawners
        for (Chunk chunk : this.stackChunkData.keySet())
            this.saveChunkBlocks(chunk, false);

        if (clearStored) {
            // Shutdown and reload: write everything, now, in this call
            List<Stack<?>> stacks = new ArrayList<>(this.stackedEntities.size() + this.stackedItems.size());
            stacks.addAll(this.stackedEntities.values());
            stacks.addAll(this.stackedItems.values());
            this.saveChunkEntityStacks(stacks, false);

            this.stackChunkData.clear();
            this.stackedEntities.clear();
            this.stackedItems.clear();
            return;
        }

        this.saveAutosaveStacks();
    }

    /**
     * The periodic autosave. Serializing and GZIPing every stack in the world in a single tick is one of
     * the largest stalls the plugin can produce, so this skips the stacks that have not changed since they
     * were last written and spreads whatever is left across ticks under the main thread budget.
     */
    private void saveAutosaveStacks() {
        List<Stack<?>> toSave = null;
        if (this.stackManager.isEntityStackingEnabled()) {
            for (StackedEntity stackedEntity : this.stackedEntities.values()) {
                if (!stackedEntity.needsSave())
                    continue;

                if (toSave == null)
                    toSave = new ArrayList<>();
                toSave.add(stackedEntity);
            }
        }

        if (this.stackManager.isItemStackingEnabled()) {
            for (StackedItem stackedItem : this.stackedItems.values()) {
                if (toSave == null)
                    toSave = new ArrayList<>();
                toSave.add(stackedItem);
            }
        }

        if (toSave == null)
            return;

        List<Stack<?>> stacks = toSave;
        BatchedMainThreadExecutor executor = BatchedMainThreadExecutor.getInstance();
        if (executor.isPerRegion()) {
            this.saveChunkEntityStacks(stacks, false);
            return;
        }

        for (int i = 0; i < stacks.size(); i += MAIN_THREAD_BATCH_SIZE) {
            List<Stack<?>> batch = stacks.subList(i, Math.min(i + MAIN_THREAD_BATCH_SIZE, stacks.size()));
            executor.submit(() -> this.saveChunkEntityStacks(batch, false));
        }
    }

    /**
     * Tries to stack a StackedEntity with all other StackedEntities
     *
     * @param stackedEntity the StackedEntity to try to stack
     */
    @Override
    public void tryStackEntity(StackedEntity stackedEntity) {
        if (!this.passesStackPreChecks(stackedEntity))
            return;

        this.tryStackEntityChecked(stackedEntity);
    }

    /**
     * Runs the culls that gate all of the real stacking work. None of them need the entity's thread, so the
     * periodic pass evaluates them off-thread and only schedules the stacks that survive.
     * <p>
     * {@link StackedEntity#hasMoved()} records the position it sees, so this must be called exactly once
     * per stack per cycle; {@link #tryStackEntityChecked} deliberately does not repeat it.
     *
     * @param stackedEntity The stack to check
     * @return true if the stack is worth looking for neighbours for, otherwise false
     */
    private boolean passesStackPreChecks(StackedEntity stackedEntity) {
        if (this.disabled)
            return false;

        EntityStackSettings stackSettings = stackedEntity.getStackSettings();
        if (stackSettings == null)
            return false;

        if (stackedEntity.checkNPC())
            return false;

        if (this.isRemoved(stackedEntity) || !stackedEntity.hasMoved())
            return false;

        return WorldGuardHook.testLocation(stackedEntity.getEntity().getLocation());
    }

    /**
     * Stacks an entity that has already passed {@link #passesStackPreChecks}. Must run on the entity's
     * thread when line of sight or water checks are enabled.
     */
    private void tryStackEntityChecked(StackedEntity stackedEntity) {
        EntityStackSettings stackSettings = stackedEntity.getStackSettings();
        if (stackSettings == null)
            return;

        // The pre-checks may have run several ticks ago if the batch had to be spread over ticks
        if (this.isRemoved(stackedEntity))
            return;

        LivingEntity entity = stackedEntity.getEntity();

        Collection<Entity> nearbyEntities;
        Predicate<Entity> predicate = x -> x.getType() == entity.getType();
        if (!SettingKey.ENTITY_MERGE_ENTIRE_CHUNK.get()) {
            nearbyEntities = this.entityCacheManager.getNearbyEntities(entity.getLocation(), stackSettings.getMergeRadius(), predicate);
        } else {
            nearbyEntities = this.entityCacheManager.getEntitiesInChunk(entity.getLocation(), predicate);
        }

        Set<StackedEntity> targetEntities = new HashSet<>();
        targetEntities.add(stackedEntity);

        for (Entity otherEntity : nearbyEntities) {
            if (entity == otherEntity)
                continue;

            // Looking the stack up first lets the removal check use the stack's own flags instead of
            // Bukkit metadata; the outcome is the same, an entity with no stack is skipped either way
            StackedEntity other = this.stackedEntities.get(otherEntity.getUniqueId());
            if (other == null || this.isRemoved(other))
                continue;

            if (stackSettings.testCanStackWith(stackedEntity, other, false)
                    && (!SettingKey.ENTITY_REQUIRE_LINE_OF_SIGHT.get() || EntityUtils.hasLineOfSight(entity, otherEntity, 0.75, false))
                    && WorldGuardHook.testLocation(otherEntity.getLocation()))
                targetEntities.add(other);
        }

        StackedEntity increased;
        int totalSize;
        List<StackedEntity> removable = new ArrayList<>(targetEntities.size());
        if (!SettingKey.ENTITY_MIN_STACK_COUNT_ONLY_INDIVIDUALS.get()) {
            increased = targetEntities.stream().max(StackedEntity::compareTo).orElse(stackedEntity);
            targetEntities.remove(increased);
            totalSize = increased.getStackSize();
            for (StackedEntity target : targetEntities) {
                if (totalSize + target.getStackSize() <= stackSettings.getMaxStackSize()) {
                    totalSize += target.getStackSize();
                    removable.add(target);
                }
            }
        } else {
            increased = stackedEntity;
            targetEntities.remove(increased);
            totalSize = 1;
            int totalStackSize = increased.getStackSize();
            for (StackedEntity target : targetEntities) {
                if (totalStackSize + target.getStackSize() <= stackSettings.getMaxStackSize()) {
                    totalSize++;
                    totalStackSize += target.getStackSize();
                    removable.add(target);
                }
            }
        }

        if (removable.isEmpty() || totalSize < stackSettings.getMinStackSize())
            return;

        EntityStackEvent entityStackEvent = new EntityStackEvent(removable, increased);
        Bukkit.getPluginManager().callEvent(entityStackEvent);
        if (entityStackEvent.isCancelled())
            return;

        for (StackedEntity toStack : removable) {
            stackSettings.applyStackProperties(toStack.getEntity(), increased.getEntity());
            increased.increaseStackSize(toStack.getEntity());
            increased.increaseStackSize(toStack.getDataStorage());
            this.removeEntityStack(toStack);
        }

        ThreadUtils.runOnPrimary(() -> removable.stream().map(StackedEntity::getEntity).forEach(Entity::remove));
    }

    @Override
    public void tryStackItem(StackedItem stackedItem) {
        if (this.disabled)
            return;

        // Everything here is a cull, and they run before the nearby scan because the scan and the item
        // comparisons behind it are the expensive part of this pass
        ItemStackSettings stackSettings = stackedItem.getStackSettings();
        Item item = stackedItem.getItem();
        if (stackSettings == null
                || !stackSettings.isStackingEnabled()
                || item == null
                || item.getPickupDelay() > 40
                || !stackedItem.hasMoved()
                || stackedItem.isUnstackable()
                || this.isRemoved(stackedItem))
            return;

        ItemStack itemStack = item.getItemStack();
        Material itemType = itemStack.getType();
        boolean itemHasMeta = itemStack.hasItemMeta();

        Set<StackedItem> targetItems = new HashSet<>();
        for (Entity nearbyEntity : this.entityCacheManager.getNearbyEntities(stackedItem.getLocation(), SettingKey.ITEM_MERGE_RADIUS.get(), ITEM_PREDICATE)) {
            if (nearbyEntity == item)
                continue;

            Item otherItem = (Item) nearbyEntity;
            if (otherItem.getPickupDelay() > 40)
                continue;

            // ItemStack#isSimilar compares the full item meta, which is by far the most expensive thing
            // this pass does. Type and "has meta" are a strict refinement of it - two items that differ in
            // either can never be similar - so they reject the common case up front for free, and isSimilar
            // still has the final say below.
            ItemStack otherItemStack = otherItem.getItemStack();
            if (otherItemStack.getType() != itemType || otherItemStack.hasItemMeta() != itemHasMeta)
                continue;

            StackedItem other = this.stackedItems.get(otherItem.getUniqueId());
            if (other == null || other.isUnstackable() || this.isRemoved(other))
                continue;

            if (!Objects.equals(item.getOwner(), otherItem.getOwner()) || !itemStack.isSimilar(otherItemStack))
                continue;

            targetItems.add(other);
        }

        if (targetItems.isEmpty())
            return;

        int totalSize = stackedItem.getStackSize();
        Set<StackedItem> removable = new HashSet<>();
        for (StackedItem target : targetItems) {
            if (totalSize + target.getStackSize() <= stackSettings.getMaxStackSize()) {
                totalSize += target.getStackSize();
                removable.add(target);
            }
        }

        StackedItem headStack = stackedItem;
        for (StackedItem other : removable) {
            StackedItem increased = headStack.compareTo(other) > 0 ? headStack : other;
            StackedItem removed = increased == headStack ? other : headStack;

            headStack = increased;

            ItemStackEvent itemStackEvent = new ItemStackEvent(removed, increased);
            Bukkit.getPluginManager().callEvent(itemStackEvent);
            if (itemStackEvent.isCancelled())
                continue;

            increased.increaseStackSize(removed.getStackSize(), false);
            removed.increaseStackSize(-removed.getStackSize(), false);
            if (SettingKey.ITEM_RESET_DESPAWN_TIMER_ON_MERGE.get())
                increased.getItem().setTicksLived(1); // Reset the 5 minute pickup timer

            increased.getItem().setPickupDelay(Math.max(increased.getItem().getPickupDelay(), removed.getItem().getPickupDelay()));
            removed.getItem().setPickupDelay(100); // Don't allow the item we just merged to get picked up or stacked

            ThreadUtils.runOnPrimary(() -> removed.getItem().remove());
            this.removeItemStack(removed);
        }

        headStack.updateDisplaySafely();
    }

    public void transferExistingEntityStack(UUID entityUUID, StackedEntity stackedEntity, StackingThread toThread) {
        this.stackedEntities.remove(entityUUID);
        toThread.loadExistingEntityStack(entityUUID, stackedEntity);
    }

    public void transferExistingItemStack(UUID entityUUID, StackedItem stackedItem, StackingThread toThread) {
        this.stackedItems.remove(entityUUID);
        toThread.loadExistingItemStack(entityUUID, stackedItem);
    }

    private void loadExistingEntityStack(UUID entityUUID, StackedEntity stackedEntity) {
        stackedEntity.updateEntity();
        this.stackedEntities.put(entityUUID, stackedEntity);
    }

    private void loadExistingItemStack(UUID entityUUID, StackedItem stackedItem) {
        stackedItem.updateItem();
        this.stackedItems.put(entityUUID, stackedItem);
    }

    /**
     * Checks whether an entity that no stack is known for should be treated as gone.
     *
     * @param entity The entity to check
     * @return true if the entity is gone, otherwise false
     */
    private boolean isRemoved(Entity entity) {
        return entity == null || !entity.isValid() || this.wasRecentlyRemoved(entity.getUniqueId());
    }

    private boolean isRemoved(StackedEntity stackedEntity) {
        LivingEntity entity = stackedEntity.getEntity();
        // A stack being instant-stacked right after creation has an entity that has not finished spawning
        // and so is not valid yet; that used to be flagged with Bukkit metadata, which cost a
        // UUID.toString() concatenation and a synchronized lookup on every single cull
        return entity == null || (!stackedEntity.isNewlyCreated() && !entity.isValid()) || this.wasRecentlyRemoved(entity.getUniqueId());
    }

    private boolean isRemoved(StackedItem stackedItem) {
        Item item = stackedItem.getItem();
        return item == null || (!stackedItem.isNewlyCreated() && !item.isValid()) || this.wasRecentlyRemoved(item.getUniqueId());
    }

    private boolean wasRecentlyRemoved(UUID entityId) {
        Long removedAt = this.removedEntities.get(entityId);
        return removedAt != null && System.currentTimeMillis() - removedAt < REMOVED_ENTITY_MEMORY_MS;
    }

    private void setRemoved(Entity entity) {
        this.removedEntities.put(entity.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * Drops removal timestamps that have aged past the window they are remembered for. Called from the
     * periodic passes; the map is normally tiny, so this costs nothing when nothing was removed.
     */
    private void pruneRemovedEntities() {
        if (this.removedEntities.isEmpty())
            return;

        long expiration = System.currentTimeMillis() - REMOVED_ENTITY_MEMORY_MS;
        this.removedEntities.values().removeIf(removedAt -> removedAt < expiration);
    }

    /**
     * @return the world that this StackingThread is acting on
     */
    public World getTargetWorld() {
        return this.targetWorld;
    }

}
