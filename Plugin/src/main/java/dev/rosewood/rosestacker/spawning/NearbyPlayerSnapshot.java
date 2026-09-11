package dev.rosewood.rosestacker.spawning;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * A snapshot of the positions of the players that can activate a spawner in a world, shared by every
 * spawner in that world and refreshed at most once per tick.
 * <p>
 * Spawner activation used to go through the level's own nearby-player check, which walks every player in
 * the level. Done for every loaded spawner, that is a term that grows with the spawner count multiplied
 * by the player count - the worst scaling shape there is - and it runs on the world tick thread before
 * any spawning work happens at all. Checking a shared array of positions instead is primitive math per
 * spawner and one pass over the player list per world per tick.
 * <p>
 * A snapshot can be one tick out of date, which cannot change the outcome of a check that only runs
 * every few ticks and uses a radius measured in whole blocks.
 */
public final class NearbyPlayerSnapshot {

    /** One tick. The activation check itself only runs every SPAWNER_PLAYER_CHECK_FREQUENCY ticks. */
    private static final long REFRESH_INTERVAL_NANOS = 50_000_000L;
    private static final double[] EMPTY = new double[0];
    /** Keyed by world UID rather than by the World so an unloaded world is not kept alive by the cache */
    private static final Map<UUID, NearbyPlayerSnapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    private volatile double[] positions;
    private volatile long lastRefresh;

    private NearbyPlayerSnapshot() {
        this.positions = EMPTY;
        this.lastRefresh = System.nanoTime() - REFRESH_INTERVAL_NANOS;
    }

    /**
     * Checks whether a player that could activate a spawner is within a distance of a point.
     * Spectators and dead players are ignored, matching the level's own nearby-player check.
     *
     * @param world The world to check in
     * @param x The x coordinate of the point
     * @param y The y coordinate of the point
     * @param z The z coordinate of the point
     * @param distance The distance to check within
     * @return true if a player is within the distance, otherwise false
     */
    public static boolean hasNearbyPlayer(World world, double x, double y, double z, double distance) {
        NearbyPlayerSnapshot snapshot = SNAPSHOTS.get(world.getUID());
        if (snapshot == null)
            snapshot = SNAPSHOTS.computeIfAbsent(world.getUID(), k -> new NearbyPlayerSnapshot());

        double[] positions = snapshot.getPositions(world);
        double distanceSquared = distance * distance;
        for (int i = 0; i < positions.length; i += 3) {
            double deltaX = positions[i] - x;
            double deltaY = positions[i + 1] - y;
            double deltaZ = positions[i + 2] - z;
            if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ < distanceSquared)
                return true;
        }

        return false;
    }

    private double[] getPositions(World world) {
        long now = System.nanoTime();
        if (now - this.lastRefresh < REFRESH_INTERVAL_NANOS)
            return this.positions;

        // A fresh array is published in one write so a reader on another world's tick thread can never
        // see a half-written set of positions
        List<Player> players = world.getPlayers();
        double[] positions = EMPTY;
        int index = 0;
        if (!players.isEmpty()) {
            positions = new double[players.size() * 3];
            Location location = new Location(world, 0, 0, 0); // re-used to read positions without allocating per player
            for (Player player : players) {
                if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR)
                    continue;

                player.getLocation(location);
                positions[index++] = location.getX();
                positions[index++] = location.getY();
                positions[index++] = location.getZ();
            }

            if (index != positions.length)
                positions = Arrays.copyOf(positions, index);
        }

        this.positions = positions;
        this.lastRefresh = now;
        return positions;
    }

}
