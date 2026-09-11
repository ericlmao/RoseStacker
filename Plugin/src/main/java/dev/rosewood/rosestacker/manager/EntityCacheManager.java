package dev.rosewood.rosestacker.manager;

import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosegarden.manager.Manager;
import dev.rosewood.rosegarden.scheduler.task.ScheduledTask;
import dev.rosewood.rosegarden.utils.NMSUtil;
import dev.rosewood.rosestacker.nms.NMSAdapter;
import dev.rosewood.rosestacker.nms.NMSHandler;
import dev.rosewood.rosestacker.stack.StackingThread;
import dev.rosewood.rosestacker.utils.VersionUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.util.BoundingBox;

public class EntityCacheManager extends Manager {

    private static final boolean DIRECT_GETTERS = NMSUtil.isPaper() && (NMSUtil.getVersionNumber() > 20 || (NMSUtil.getVersionNumber() == 20 && NMSUtil.getMinorVersionNumber() >= 4));

    /**
     * The largest world index the cell key can hold. Indices are handed out per snapshot over the worlds
     * that are stacking at that moment, so this is a limit on worlds loaded at once, not over time.
     */
    private static final int MAX_WORLD_INDEX = 0xFFF;

    /**
     * The cache is rebuilt from scratch every refresh and published by swapping this reference, so readers
     * always iterate a complete cache instead of one that is being cleared and refilled underneath them.
     * Grab the reference once at the start of a read and use it for the whole pass.
     */
    private volatile CacheSnapshot snapshot;
    private boolean worldLimitWarned;
    private ScheduledTask refreshTask;

    public EntityCacheManager(RosePlugin rosePlugin) {
        super(rosePlugin);
        this.snapshot = CacheSnapshot.empty();
    }

    @Override
    public void reload() {
        this.refreshTask = this.rosePlugin.getScheduler().runTaskTimer(this::refresh, 5L, 60L);
    }

    @Override
    public void disable() {
        this.snapshot = CacheSnapshot.empty();

        if (this.refreshTask != null) {
            this.refreshTask.cancel();
            this.refreshTask = null;
        }
    }

    /**
     * Gets nearby entities from cache
     *
     * @param center The center of the area to check
     * @param radius The radius to check around
     * @param predicate Conditions to be met
     * @return A Set of nearby entities
     */
    public Collection<Entity> getNearbyEntities(Location center, double radius, Predicate<Entity> predicate) {
        Set<Entity> nearbyEntities = new HashSet<>();
        World world = center.getWorld();
        if (world == null)
            return nearbyEntities;

        BoundingBox boundingBox = new BoundingBox(
                center.getX() - radius,
                center.getY() - radius,
                center.getZ() - radius,
                center.getX() + radius,
                center.getY() + radius,
                center.getZ() + radius
        );

        int minX = (int) boundingBox.getMinX() >> 4;
        int maxX = (int) boundingBox.getMaxX() >> 4;
        int minY = (int) boundingBox.getMinY() >> 4;
        int maxY = (int) boundingBox.getMaxY() >> 4;
        int minZ = (int) boundingBox.getMinZ() >> 4;
        int maxZ = (int) boundingBox.getMaxZ() >> 4;

        CacheSnapshot snapshot = this.snapshot;
        int worldIndex = snapshot.worldIndex(world);
        if (worldIndex < 0)
            return nearbyEntities; // A world the last refresh did not cover simply has nothing cached yet

        Map<Long, Collection<Entity>> entityCache = snapshot.cells();
        Location location = center.clone(); // re-use location object to dump positions so we aren't constantly remaking Location objects
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Collection<Entity> entities = entityCache.get(cellKey(worldIndex, x, y, z));
                    if (entities == null)
                        continue;

                    this.filter(location, boundingBox, entities, predicate, nearbyEntities);
                }
            }
        }

        return nearbyEntities;
    }

    private void filter(Location location, BoundingBox boundingBox, Collection<Entity> entities, Predicate<Entity> predicate, Set<Entity> collector) {
        if (DIRECT_GETTERS) {
            for (Entity entity : entities) {
                if (boundingBox.contains(entity.getX(), entity.getY(), entity.getZ())
                        && predicate.test(entity)
                        && entity.isValid())
                    collector.add(entity);
            }
        } else {
            for (Entity entity : entities) {
                entity.getLocation(location);
                if (boundingBox.contains(location.getX(), location.getY(), location.getZ())
                        && predicate.test(entity)
                        && entity.isValid())
                    collector.add(entity);
            }
        }
    }

    /**
     * Counts the matching entities near a point, stopping as soon as the count reaches a limit.
     * Callers that only need to compare the number of nearby entities against a threshold can use this
     * instead of {@link #getNearbyEntities}, which allocates a Location, a BoundingBox and a result Set
     * just to have its result thrown away after a size comparison.
     *
     * @param world The world to check in
     * @param x The x coordinate of the center of the area to check
     * @param y The y coordinate of the center of the area to check
     * @param z The z coordinate of the center of the area to check
     * @param radius The radius to check around
     * @param predicate Conditions to be met
     * @param limit The count to stop at, the returned value never exceeds it
     * @return the number of matching nearby entities, capped at the limit
     */
    public int countNearbyEntities(World world, double x, double y, double z, double radius, Predicate<Entity> predicate, int limit) {
        return this.countNearbyEntities(world, x, y, z, radius, predicate, null, limit);
    }

    /**
     * Counts the matching entities near a point, stopping as soon as the count reaches a limit.
     * Each matching entity contributes the weight given by the weigher, which allows summing something
     * other than one per entity (such as stack sizes) while keeping the early exit.
     *
     * @param world The world to check in
     * @param x The x coordinate of the center of the area to check
     * @param y The y coordinate of the center of the area to check
     * @param z The z coordinate of the center of the area to check
     * @param radius The radius to check around
     * @param predicate Conditions to be met
     * @param weigher How much each matching entity counts for, or null to count one per entity
     * @param limit The count to stop at, the returned value never exceeds it
     * @return the summed weight of the matching nearby entities, capped at the limit
     */
    public int countNearbyEntities(World world, double x, double y, double z, double radius, Predicate<Entity> predicate, ToIntFunction<Entity> weigher, int limit) {
        if (world == null || limit <= 0)
            return 0;

        double minX = x - radius, minY = y - radius, minZ = z - radius;
        double maxX = x + radius, maxY = y + radius, maxZ = z + radius;

        int minCellX = (int) minX >> 4, maxCellX = (int) maxX >> 4;
        int minCellY = (int) minY >> 4, maxCellY = (int) maxY >> 4;
        int minCellZ = (int) minZ >> 4, maxCellZ = (int) maxZ >> 4;

        CacheSnapshot snapshot = this.snapshot;
        int worldIndex = snapshot.worldIndex(world);
        if (worldIndex < 0)
            return 0;

        Map<Long, Collection<Entity>> entityCache = snapshot.cells();
        Location location = DIRECT_GETTERS ? null : new Location(world, 0, 0, 0);
        int count = 0;
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellY = minCellY; cellY <= maxCellY; cellY++) {
                for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                    Collection<Entity> entities = entityCache.get(cellKey(worldIndex, cellX, cellY, cellZ));
                    if (entities == null)
                        continue;

                    for (Entity entity : entities) {
                        double entityX, entityY, entityZ;
                        if (location == null) {
                            entityX = entity.getX();
                            entityY = entity.getY();
                            entityZ = entity.getZ();
                        } else {
                            entity.getLocation(location);
                            entityX = location.getX();
                            entityY = location.getY();
                            entityZ = location.getZ();
                        }

                        // Matches BoundingBox#contains, which is inclusive of the minimum and exclusive of the maximum
                        if (entityX < minX || entityX >= maxX || entityY < minY || entityY >= maxY || entityZ < minZ || entityZ >= maxZ)
                            continue;

                        if (!predicate.test(entity) || !entity.isValid())
                            continue;

                        count += weigher == null ? 1 : weigher.applyAsInt(entity);
                        if (count >= limit)
                            return limit;
                    }
                }
            }
        }

        return count;
    }

    /**
     * Gets entities in the Chunk of a Location
     *
     * @param location The Location of the Chunk
     * @param predicate Conditions to be met
     * @return A Set of entities in the chunk
     */
    public Collection<Entity> getEntitiesInChunk(Location location, Predicate<Entity> predicate) {
        World world = location.getWorld();
        if (world == null)
            return new ArrayList<>();

        int x = location.getBlockX() >> 4;
        int z = location.getBlockZ() >> 4;
        int minY = world.getMinHeight() >> 4;
        int maxY = world.getMaxHeight() >> 4;

        Set<Entity> entities = new HashSet<>();
        CacheSnapshot snapshot = this.snapshot;
        int worldIndex = snapshot.worldIndex(world);
        if (worldIndex < 0)
            return entities;

        Map<Long, Collection<Entity>> entityCache = snapshot.cells();
        for (int y = minY; y <= maxY; y++) {
            Collection<Entity> chunkEntities = entityCache.get(cellKey(worldIndex, x, y, z));
            if (chunkEntities != null)
                entities.addAll(chunkEntities);
        }

        Set<Entity> nearbyEntities = new HashSet<>();
        for (Entity entity : entities)
            if (predicate.test(entity) && entity.isValid())
                nearbyEntities.add(entity);

        return nearbyEntities;
    }

    /**
     * Forces an entry into the cache, used for newly spawned entities
     *
     * @param entity The entity to cache
     */
    public void preCacheEntity(Entity entity) {
        CacheSnapshot snapshot = this.snapshot;
        int worldIndex = snapshot.worldIndex(entity.getWorld());
        if (worldIndex < 0)
            return; // Nothing to pre-cache into; the next refresh picks the world and its entities up

        Location location = entity.getLocation();
        long key = cellKey(worldIndex, (int) location.getX() >> 4, (int) location.getY() >> 4, (int) location.getZ() >> 4);
        // The cells the refresh publishes are plain lists that nothing mutates afterwards, so replace the
        // cell with a copy that includes the new entity rather than adding to a list a reader may be
        // iterating. A reader holding the old cell simply misses an entity that spawned mid-pass, which is
        // the same staleness it already tolerates between refreshes.
        snapshot.cells().compute(key, (k, existing) -> {
            if (existing == null)
                return List.of(entity);

            if (existing.contains(entity))
                return existing;

            List<Entity> updated = new ArrayList<>(existing.size() + 1);
            updated.addAll(existing);
            updated.add(entity);
            return updated;
        });
    }

    private void refresh() {
        // Build the replacement cache off to the side and swap it in when it is complete; clearing and
        // refilling the live cache made every reader in that window see a partially built cache.
        // The world indices the cell keys are packed with are rebuilt along with the cells and published
        // with them, numbered 0..n over the worlds that are stacking right now. Handing them out
        // monotonically instead meant a server that loads and unloads worlds forever eventually wrapped
        // the index field and started answering one world's lookups out of another world's cells.
        Map<Long, Collection<Entity>> cells = new ConcurrentHashMap<>(Math.max(16, this.snapshot.cells().size()));
        Map<UUID, Integer> worldIndices = new HashMap<>();
        NMSHandler nmsHandler = NMSAdapter.getHandler();
        for (StackingThread stackingThread : this.rosePlugin.getManager(StackManager.class).getStackingThreads().values()) {
            World world = stackingThread.getTargetWorld();
            int worldIndex = worldIndices.size();
            if (worldIndex > MAX_WORLD_INDEX) {
                // Far more worlds than the key has room for; cache the ones that fit rather than alias them
                if (!this.worldLimitWarned) {
                    this.worldLimitWarned = true;
                    this.rosePlugin.getLogger().log(Level.WARNING, "More than " + (MAX_WORLD_INDEX + 1)
                            + " worlds are being stacked at once; entities in the worlds past that are not being cached.");
                }
                break;
            }

            worldIndices.put(world.getUID(), worldIndex);
            this.addWorldEntities(cells, worldIndex, world, nmsHandler.getEntities(world));
        }
        this.snapshot = new CacheSnapshot(Map.copyOf(worldIndices), cells);
    }

    private void addWorldEntities(Map<Long, Collection<Entity>> entityCache, int worldIndex, World world, List<Entity> worldEntities) {
        // The server hands entities back grouped by the chunk they live in, so consecutive entities almost
        // always land in the same cell. Remembering the last cell turns a cell key allocation plus a
        // map lookup per entity into one per cell.
        int lastX = 0, lastY = 0, lastZ = 0;
        Collection<Entity> lastEntities = null;

        if (DIRECT_GETTERS) {
            for (Entity entity : worldEntities) {
                EntityType type = entity.getType();
                if (type != VersionUtils.ITEM && (!type.isAlive() || type == EntityType.PLAYER || type == EntityType.ARMOR_STAND))
                    continue;

                int x = (int) entity.getX() >> 4, y = (int) entity.getY() >> 4, z = (int) entity.getZ() >> 4;
                if (lastEntities == null || x != lastX || y != lastY || z != lastZ) {
                    lastEntities = entityCache.computeIfAbsent(cellKey(worldIndex, x, y, z), k -> this.createCollection());
                    lastX = x;
                    lastY = y;
                    lastZ = z;
                }

                lastEntities.add(entity);
            }
        } else {
            Location location = new Location(world, 0, 0, 0);
            for (Entity entity : worldEntities) {
                EntityType type = entity.getType();
                if (type != VersionUtils.ITEM && (!type.isAlive() || type == EntityType.PLAYER || type == EntityType.ARMOR_STAND))
                    continue;

                entity.getLocation(location); // re-use location object to dump positions so we aren't constantly remaking Location objects
                int x = (int) location.getX() >> 4, y = (int) location.getY() >> 4, z = (int) location.getZ() >> 4;
                if (lastEntities == null || x != lastX || y != lastY || z != lastZ) {
                    lastEntities = entityCache.computeIfAbsent(cellKey(worldIndex, x, y, z), k -> this.createCollection());
                    lastX = x;
                    lastY = y;
                    lastZ = z;
                }

                lastEntities.add(entity);
            }
        }
    }

    /**
     * @return a collection for one cell of the cache
     * @implNote This used to be a LinkedBlockingDeque, which allocates a ReentrantLock and two Conditions
     *           per cell, and then a ConcurrentLinkedQueue. Cells are filled by the refresh before the
     *           cache is published and only ever iterated afterwards, so a plain ArrayList is enough;
     *           {@link #preCacheEntity} replaces a cell wholesale instead of mutating it.
     */
    private Collection<Entity> createCollection() {
        return new ArrayList<>();
    }

    /**
     * Packs a world index and the coordinates of a 16x16x16 cell into a single long key.
     * The world index gets the top 12 bits, the cell x and z 22 bits each (the full range of a 30 million
     * block world) and the cell y the bottom 8 bits, which covers a build range of +/-2048 blocks, twice
     * what any current version allows. The world index used to get 10 bits and the cell y 10; y never
     * needed them and the world index is the field a server can plausibly run out of.
     */
    private static long cellKey(int worldIndex, int x, int y, int z) {
        return ((long) (worldIndex & 0xFFF) << 52)
                | ((long) (x & 0x3FFFFF) << 30)
                | ((long) (z & 0x3FFFFF) << 8)
                | (y & 0xFFL);
    }

    /**
     * One published view of the cache: the cells, and the world indices their keys were packed with. The
     * two are rebuilt and swapped together, so a key can never be read with indices other than the ones it
     * was written with, and a world the snapshot does not list is simply reported as having nothing cached
     * until the next refresh includes it.
     */
    private record CacheSnapshot(Map<UUID, Integer> worldIndices, Map<Long, Collection<Entity>> cells) {

        private static CacheSnapshot empty() {
            return new CacheSnapshot(Map.of(), new ConcurrentHashMap<>());
        }

        /**
         * @param world The world to look up
         * @return the index this snapshot packed the world's cell keys with, or -1 if it has no cells for it
         */
        private int worldIndex(World world) {
            Integer index = this.worldIndices.get(world.getUID());
            return index != null ? index : -1;
        }

    }

}
