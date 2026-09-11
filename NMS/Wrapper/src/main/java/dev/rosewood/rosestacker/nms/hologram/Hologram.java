package dev.rosewood.rosestacker.nms.hologram;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public abstract class Hologram {

    private static final double LINE_OFFSET = 0.3;

    /**
     * Copy-on-write because the lines are rebuilt by the async display passes through
     * {@link #setTextSilently} and {@link #createLines} while the version-specific subclasses are iterating
     * them to build create and update packets, potentially on another thread. Holograms have a handful of
     * lines and are rewritten only when their text actually changes, so the copies are cheap and rare, and
     * every reader gets a list that is complete rather than one caught mid-refill.
     */
    protected final List<HologramLine> hologramLines;
    protected final Map<Player, Boolean> watchers;
    protected final Location location;
    private final Supplier<Integer> entityIdSupplier;

    public Hologram(List<String> text, Location location, Supplier<Integer> entityIdSupplier) {
        this.location = location.clone();
        this.watchers = Collections.synchronizedMap(new WeakHashMap<>());
        this.entityIdSupplier = entityIdSupplier;
        this.hologramLines = new CopyOnWriteArrayList<>();
        this.createLines(text);
    }

    /**
     * Adds a player to the watchers of this hologram
     *
     * @param player The player to add
     * @param visible true to make the hologram visible, false otherwise
     */
    public void addWatcher(Player player, boolean visible) {
        // Watcher updates can now come from the async hologram timer as well as from the main thread, so
        // the map is only ever touched through atomic operations
        if (this.watchers.putIfAbsent(player, visible) == null) {
            this.create(player);
            this.update(List.of(player), true);
        }
    }

    /**
     * Adds a player to the watchers of this hologram
     *
     * @param player The player to add
     */
    public void addWatcher(Player player) {
        this.addWatcher(player, true);
    }

    /**
     * Removes a player from the watchers of this hologram
     *
     * @param player The player to remove
     */
    public void removeWatcher(Player player) {
        if (this.watchers.remove(player) != null)
            this.delete(player);
    }

    /**
     * @return a set of all players watching this hologram
     */
    public Set<Player> getWatchers() {
        return this.watchers.keySet();
    }

    /**
     * Gets a snapshot of the watchers, safe to iterate from any thread. The backing map is synchronized,
     * but iterating its key set is not, and watcher updates can now come from the hologram timer's own
     * thread as well as from the main thread.
     *
     * @return the players watching this hologram
     */
    public List<Player> getWatcherSnapshot() {
        synchronized (this.watchers) {
            return List.copyOf(this.watchers.keySet());
        }
    }

    /**
     * @return the location of this hologram
     */
    public Location getLocation() {
        return this.location;
    }

    /**
     * @return the display location of this hologram (at the height where the text actually renders)
     */
    public Location getDisplayLocation() {
        return this.location.clone().add(0, 1, 0);
    }

    /**
     * @return the text of this hologram
     */
    public List<String> getText() {
        return this.hologramLines.stream().map(HologramLine::getText).toList();
    }

    /**
     * Sets the visibility of the hologram for a player
     *
     * @param player The player to set the visibility for
     * @param visible true to make the hologram visible, false otherwise
     */
    public void setVisibility(Player player, boolean visible) {
        Boolean alreadyVisible = this.watchers.replace(player, visible);
        if (alreadyVisible == null)
            return; // Not a watcher

        if (alreadyVisible ^ visible)
            this.update(List.of(player), true);
    }

    /**
     * Deletes the hologram for all watchers
     */
    public void delete() {
        this.getWatcherSnapshot().forEach(this::delete);
        this.watchers.clear();
    }

    /**
     * Sets the hologram text and updates it to all watchers
     *
     * @param text The text to set
     * @throws IllegalArgumentException if the text is a different length than the existing text
     */
    public void setText(List<String> text) {
        if (text.size() != this.hologramLines.size()) {
            this.createLines(text);
            return;
        }

        for (int i = 0; i < text.size(); i++)
            this.hologramLines.get(i).setText(text.get(i));

        this.update(this.getWatcherSnapshot(), false);
    }

    /**
     * Sets the hologram text without sending packets.
     *
     * @param text The text to set
     * @return true if the hologram lines need to be recreated for watchers
     */
    public boolean setTextSilently(List<String> text) {
        if (text.size() != this.hologramLines.size()) {
            this.replaceLines(text);
            return true;
        }

        for (int i = 0; i < text.size(); i++)
            this.hologramLines.get(i).setText(text.get(i));

        return false;
    }

    /**
     * Checks whether any line's text has changed since the last update and clears the dirty state.
     * Callers that consume this should send subsequent updates with force=true, since the
     * per-line dirty flags have been reset.
     *
     * @return true if any line needs to be re-sent to watchers, false otherwise
     */
    public boolean consumeDirty() {
        boolean dirty = false;
        for (HologramLine line : this.hologramLines)
            dirty |= line.checkDirty();
        return dirty;
    }

    public void update(Player player, boolean force) {
        this.update(List.of(player), force);
    }

    public void refresh(Player player) {
        this.delete(player);
        this.create(player);
        this.update(List.of(player), true);
    }

    private void createLines(List<String> text) {
        List<Player> watchers = this.getWatcherSnapshot();
        watchers.forEach(this::delete);
        this.replaceLines(text);
        watchers.forEach(this::create);
        this.update(watchers, true);
    }

    /**
     * Swaps in a whole new set of lines. The replacements are built into a local list first and installed
     * with a single clear and addAll, so a reader on another thread sees the old lines or the new ones
     * rather than an empty list or a half-built one.
     *
     * @param text The text to build the lines from
     */
    private void replaceLines(List<String> text) {
        List<HologramLine> lines = new ArrayList<>(text.size());
        for (int i = 0; i < text.size(); i++) {
            double offset = (text.size() - i - 1) * LINE_OFFSET;
            Location lineLocation = this.location.clone().add(0, offset, 0);
            lines.add(new HologramLine(this.entityIdSupplier.get(), lineLocation, text.get(i)));
        }

        this.hologramLines.clear();
        this.hologramLines.addAll(lines);
    }

    /**
     * Creates a new hologram entity for the given player
     *
     * @param player The player to spawn the hologram for
     */
    protected abstract void create(Player player);

    /**
     * Sends the metadata packet for this hologram to the specified players if the line needs to be updated
     *
     * @param players The players to send the packet to
     * @param force true to force the packet to be sent, false otherwise
     */
    protected abstract void update(Collection<Player> players, boolean force);

    /**
     * Deletes the hologram entity for the given player
     *
     * @param player The player to delete the hologram for
     */
    protected abstract void delete(Player player);

}
