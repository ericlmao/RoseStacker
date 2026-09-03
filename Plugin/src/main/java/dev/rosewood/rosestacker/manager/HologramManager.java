package dev.rosewood.rosestacker.manager;

import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosegarden.manager.Manager;
import dev.rosewood.rosegarden.scheduler.task.ScheduledTask;
import dev.rosewood.rosestacker.config.SettingKey;
import dev.rosewood.rosestacker.nms.NMSAdapter;
import dev.rosewood.rosestacker.nms.NMSHandler;
import dev.rosewood.rosestacker.nms.hologram.Hologram;
import dev.rosewood.rosestacker.utils.EntityUtils;
import dev.rosewood.rosestacker.utils.ThreadUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;

public class HologramManager extends Manager implements Listener {

    /**
     * Holograms are bucketed into square cells of this many blocks so a player's watcher update only
     * looks at holograms near them instead of every hologram on the server.
     */
    private static final int CELL_SHIFT = 4; // 16 block cells
    /**
     * A player who has not moved can only have their line of sight change when blocks change, so their
     * wall checks are skipped and only re-run this many update cycles later.
     */
    private static final int IDLE_LINE_OF_SIGHT_RECHECK_CYCLES = 5;
    private static final double MOVEMENT_THRESHOLD_SQRD = 0.25 * 0.25;

    private final Map<Location, Hologram> holograms;
    private final Map<UUID, Map<Long, List<Hologram>>> hologramCells; // world id -> cell key -> holograms
    private final Map<UUID, PlayerHologramState> playerStates;
    private final NMSHandler nmsHandler;
    private ScheduledTask watcherTask;
    private double renderDistanceSqrd;
    private int renderDistanceCells;
    private boolean hideThroughWalls;

    public HologramManager(RosePlugin rosePlugin) {
        super(rosePlugin);

        this.holograms = new ConcurrentHashMap<>();
        this.hologramCells = new ConcurrentHashMap<>();
        this.playerStates = new ConcurrentHashMap<>();
        this.nmsHandler = NMSAdapter.getHandler();

        Bukkit.getPluginManager().registerEvents(this, this.rosePlugin);
    }

    @Override
    public void reload() {
        this.watcherTask = this.rosePlugin.getScheduler().runTaskTimerAsync(this::updateWatchers, 0L, SettingKey.HOLOGRAM_UPDATE_FREQUENCY.get());
        int renderDistance = SettingKey.BLOCK_DYNAMIC_TAG_VIEW_RANGE.get();
        this.renderDistanceSqrd = (double) renderDistance * renderDistance;
        this.renderDistanceCells = (renderDistance >> CELL_SHIFT) + 1;
        this.hideThroughWalls = SettingKey.BLOCK_DYNAMIC_TAG_VIEW_RANGE_WALL_DETECTION_ENABLED.get();
    }

    @Override
    public void disable() {
        if (this.watcherTask != null) {
            this.watcherTask.cancel();
            this.watcherTask = null;
        }

        this.holograms.values().forEach(Hologram::delete);
        this.holograms.clear();
        this.hologramCells.clear();
        this.playerStates.clear();
    }

    private void updateWatchers() {
        Collection<? extends Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (this.holograms.isEmpty() && this.playerStates.isEmpty())
            return;

        // Drop state for anyone who left without a quit event reaching us
        if (!this.playerStates.isEmpty()) {
            Set<UUID> online = ConcurrentHashMap.newKeySet();
            for (Player player : players)
                online.add(player.getUniqueId());
            this.playerStates.keySet().removeIf(id -> !online.contains(id));
        }

        for (Player player : players)
            ThreadUtils.runOnEntity(player, () -> this.updateWatcher(player));
    }

    /**
     * Brings one player's hologram watchers up to date. Runs on the player's thread.
     * <p>
     * Instead of testing this player against every hologram (players x holograms work per cycle, most of
     * it calling removeWatcher on holograms they were never watching), this walks only the holograms the
     * player currently watches plus the holograms in the cells around them, and skips wall checks
     * entirely while the player stands still.
     */
    private void updateWatcher(Player player) {
        if (!player.isValid())
            return;

        PlayerHologramState state = this.playerStates.computeIfAbsent(player.getUniqueId(), x -> new PlayerHologramState());
        Location eye = player.getEyeLocation();
        World world = eye.getWorld();
        if (world == null)
            return;

        UUID worldId = world.getUID();
        boolean moved = !worldId.equals(state.worldId)
                || distanceSquared(eye.getX(), eye.getY(), eye.getZ(), state.eyeX, state.eyeY, state.eyeZ) > MOVEMENT_THRESHOLD_SQRD;
        boolean recheckLineOfSight = moved || ++state.idleCycles >= IDLE_LINE_OF_SIGHT_RECHECK_CYCLES;
        if (recheckLineOfSight)
            state.idleCycles = 0;

        if (moved) {
            state.worldId = worldId;
            state.eyeX = eye.getX();
            state.eyeY = eye.getY();
            state.eyeZ = eye.getZ();

            // Holograms never move, so watched holograms can only fall out of range when the player moves
            Iterator<Hologram> iterator = state.watching.iterator();
            while (iterator.hasNext()) {
                Hologram hologram = iterator.next();
                Location location = hologram.getLocation();
                if (this.holograms.get(location) != hologram || !worldId.equals(location.getWorld().getUID())
                        || distanceSquared(eye.getX(), eye.getY(), eye.getZ(), location.getX(), location.getY(), location.getZ()) > this.renderDistanceSqrd) {
                    iterator.remove();
                    hologram.removeWatcher(player);
                }
            }
        }

        if (!recheckLineOfSight)
            return;

        Map<Long, List<Hologram>> cells = this.hologramCells.get(worldId);
        if (cells == null || cells.isEmpty())
            return;

        int centerCellX = eye.getBlockX() >> CELL_SHIFT;
        int centerCellZ = eye.getBlockZ() >> CELL_SHIFT;
        for (int cellX = centerCellX - this.renderDistanceCells; cellX <= centerCellX + this.renderDistanceCells; cellX++) {
            for (int cellZ = centerCellZ - this.renderDistanceCells; cellZ <= centerCellZ + this.renderDistanceCells; cellZ++) {
                List<Hologram> cell = cells.get(cellKey(cellX, cellZ));
                if (cell == null)
                    continue;

                for (Hologram hologram : cell)
                    this.updateWatcher(player, state, eye, hologram);
            }
        }
    }

    private void updateWatcher(Player player, PlayerHologramState state, Location eye, Hologram hologram) {
        Location location = hologram.getLocation();
        if (distanceSquared(eye.getX(), eye.getY(), eye.getZ(), location.getX(), location.getY(), location.getZ()) > this.renderDistanceSqrd) {
            if (state.watching.remove(hologram))
                hologram.removeWatcher(player);
            return;
        }

        if (state.watching.add(hologram))
            hologram.addWatcher(player);

        if (this.hideThroughWalls) {
            // Display location is one block above the hologram's base location
            boolean visible = EntityUtils.hasLineOfSight(eye.getWorld(), eye.getX(), eye.getY(), eye.getZ(),
                    location.getX(), location.getY() + 1, location.getZ(), 0.75, true);
            hologram.setVisibility(player, visible); // Only sends a packet if the visibility actually changed
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        this.playerStates.remove(player.getUniqueId());
        ThreadUtils.runOnEntity(player, () -> this.updateWatcher(player));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerHologramState state = this.playerStates.remove(player.getUniqueId());
        if (state == null)
            return;

        for (Hologram hologram : state.watching)
            hologram.removeWatcher(player);
        state.watching.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        new ArrayList<>(this.holograms.keySet()).stream()
                .filter(x -> x.getWorld().equals(event.getWorld()))
                .forEach(this::deleteHologram);
        this.hologramCells.remove(event.getWorld().getUID());
    }

    /**
     * Creates or updates a hologram at the given location
     *
     * @param location The location of the hologram
     * @param text The text for the hologram
     */
    public void createOrUpdateHologram(Location location, List<String> text) {
        Hologram hologram = this.holograms.get(location);
        if (hologram == null) {
            hologram = this.nmsHandler.createHologram(location, text);
            this.holograms.put(location, hologram);
            this.indexHologram(hologram);
            for (Player player : Bukkit.getOnlinePlayers())
                this.updateWatcherSafely(player, hologram);
        } else {
            boolean recreate = hologram.setTextSilently(text);
            boolean changed = hologram.consumeDirty();
            if (!recreate && !changed)
                return; // Nothing to send; don't schedule per-watcher tasks

            for (Player player : new ArrayList<>(hologram.getWatchers()))
                this.updateTextSafely(player, hologram, recreate);
        }
    }

    /**
     * Gets the hologram at the given location if one exists
     *
     * @param location The location of the hologram
     * @return the hologram, or null if none exists
     */
    public Hologram getHologram(Location location) {
        return this.holograms.get(location);
    }

    /**
     * Deletes a hologram at a given location if one exists
     *
     * @param location The location of the hologram
     */
    public void deleteHologram(Location location) {
        Hologram hologram = this.holograms.remove(location);
        if (hologram == null)
            return;

        this.unindexHologram(hologram);
        for (Player player : new ArrayList<>(hologram.getWatchers())) {
            PlayerHologramState state = this.playerStates.get(player.getUniqueId());
            if (state != null)
                state.watching.remove(hologram);
            ThreadUtils.runOnEntity(player, () -> hologram.removeWatcher(player));
        }
    }

    private void updateWatcherSafely(Player player, Hologram hologram) {
        ThreadUtils.runOnEntity(player, () -> {
            if (!player.isValid())
                return;

            Location eye = player.getEyeLocation();
            if (!eye.getWorld().equals(hologram.getLocation().getWorld()))
                return;

            PlayerHologramState state = this.playerStates.computeIfAbsent(player.getUniqueId(), x -> new PlayerHologramState());
            this.updateWatcher(player, state, eye, hologram);
        });
    }

    private void updateTextSafely(Player player, Hologram hologram, boolean recreate) {
        ThreadUtils.runOnEntity(player, () -> {
            if (recreate) {
                hologram.refresh(player);
            } else {
                hologram.update(player, true);
            }
        });
    }

    private void indexHologram(Hologram hologram) {
        Location location = hologram.getLocation();
        World world = location.getWorld();
        if (world == null)
            return;

        this.hologramCells.computeIfAbsent(world.getUID(), x -> new ConcurrentHashMap<>())
                .computeIfAbsent(cellKey(location.getBlockX() >> CELL_SHIFT, location.getBlockZ() >> CELL_SHIFT), x -> new CopyOnWriteArrayList<>())
                .add(hologram);
    }

    private void unindexHologram(Hologram hologram) {
        Location location = hologram.getLocation();
        World world = location.getWorld();
        if (world == null)
            return;

        Map<Long, List<Hologram>> cells = this.hologramCells.get(world.getUID());
        if (cells == null)
            return;

        long key = cellKey(location.getBlockX() >> CELL_SHIFT, location.getBlockZ() >> CELL_SHIFT);
        List<Hologram> cell = cells.get(key);
        if (cell != null) {
            cell.remove(hologram);
            if (cell.isEmpty())
                cells.remove(key, cell);
        }
    }

    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    private static double distanceSquared(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Per-player bookkeeping for the watcher loop. Only ever touched from the player's own thread, apart
     * from {@link #deleteHologram} pruning a deleted hologram out of the watched set.
     */
    private static final class PlayerHologramState {

        private final Set<Hologram> watching = ConcurrentHashMap.newKeySet();
        private UUID worldId;
        private double eyeX = Double.NaN, eyeY = Double.NaN, eyeZ = Double.NaN;
        private int idleCycles;

    }

}
