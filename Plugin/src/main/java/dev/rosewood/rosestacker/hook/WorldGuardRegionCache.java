package dev.rosewood.rosestacker.hook;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.World;

/**
 * Caches, per chunk, whether any WorldGuard region overlapping that chunk has anything to say about our flag.
 * <p>
 * Our flag defaults to true, so the only way a location can be denied is if a region covering it explicitly sets
 * the flag. Region layouts are sparse compared to the number of chunks a busy server touches, so the overwhelmingly
 * common answer is "no region here cares", which lets {@link WorldGuardHook#testLocation} skip building a
 * RegionQuery and running a spatial lookup for every hopper pickup, damage event, spawn and stack candidate.
 * <p>
 * WorldGuard exposes no region change event that is available across all of the versions we support, so entries
 * expire on a short TTL instead; a newly created or edited region is honored within {@link #CHUNK_ENTRY_TTL_MILLIS}.
 * The cache is also cleared outright when the plugin reloads.
 * <p>
 * This class intentionally contains no WorldGuard types so that it stays loadable without WorldGuard installed;
 * every WorldGuard call lives in {@link WorldGuardFlagHook}.
 */
public final class WorldGuardRegionCache {

    /**
     * How long a per-chunk answer is trusted before it is recomputed
     */
    private static final long CHUNK_ENTRY_TTL_MILLIS = 30000;

    /**
     * How often expired entries are swept out of the cache. Entries for chunks that are never looked at again,
     * such as those in an unloaded world, are only reclaimed by this sweep.
     */
    private static final long SWEEP_INTERVAL_MILLIS = CHUNK_ENTRY_TTL_MILLIS;

    private static final Map<ChunkKey, CachedResult> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong LAST_SWEEP = new AtomicLong();

    private WorldGuardRegionCache() {

    }

    /**
     * Checks if any region overlapping the given chunk could deny stacking, meaning the exact per-location query
     * still has to be run. A false return means no region in the chunk touches our flag at all and the location
     * is definitely allowed.
     *
     * @param world The world the chunk is in
     * @param chunkX The chunk X coordinate
     * @param chunkZ The chunk Z coordinate
     * @return true if an exact query is required, false if stacking is allowed anywhere in this chunk
     */
    public static boolean mayDenyStacking(World world, int chunkX, int chunkZ) {
        long now = System.currentTimeMillis();
        ChunkKey key = new ChunkKey(world.getUID(), chunkX, chunkZ);

        CachedResult cached = CACHE.get(key);
        if (cached != null && cached.expiresAt() > now)
            return cached.flagged();

        // Computed outside of any map lock on purpose; two threads racing to fill the same chunk is cheaper than
        // holding a bin lock across a WorldGuard spatial query
        boolean flagged = WorldGuardFlagHook.chunkContainsFlaggedRegion(world, chunkX, chunkZ);
        CACHE.put(key, new CachedResult(flagged, now + CHUNK_ENTRY_TTL_MILLIS));
        sweepIfNeeded(now);
        return flagged;
    }

    /**
     * Clears all cached chunk results, forcing them to be recomputed on next use
     */
    public static void clearCache() {
        CACHE.clear();
        LAST_SWEEP.set(0);
    }

    /**
     * Removes expired entries, at most once per {@link #SWEEP_INTERVAL_MILLIS}. Only ever called on a cache miss,
     * which happens at most once per chunk per TTL, so the scan cost is negligible.
     *
     * @param now The current time in milliseconds
     */
    private static void sweepIfNeeded(long now) {
        long lastSweep = LAST_SWEEP.get();
        if (now - lastSweep < SWEEP_INTERVAL_MILLIS || !LAST_SWEEP.compareAndSet(lastSweep, now))
            return;

        Iterator<CachedResult> iterator = CACHE.values().iterator();
        while (iterator.hasNext())
            if (iterator.next().expiresAt() <= now)
                iterator.remove();
    }

    /**
     * Identifies a chunk column in a specific world
     */
    private record ChunkKey(UUID world, int x, int z) { }

    /**
     * An immutable cached answer for a chunk, valid until the given timestamp
     */
    private record CachedResult(boolean flagged, long expiresAt) { }

}
