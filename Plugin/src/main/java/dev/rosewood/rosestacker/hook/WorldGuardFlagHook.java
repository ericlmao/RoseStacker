package dev.rosewood.rosestacker.hook;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import dev.rosewood.rosestacker.config.SettingKey;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Wrapper class to fix the Flag class trying to load without WorldGuard installed
 */
public class WorldGuardFlagHook {

    /**
     * The vertical bounds used when querying a chunk column for regions. Vanilla allows custom dimensions to
     * range from -2032 to 2032, so this covers any world without needing version-specific world height lookups.
     */
    private static final int CHUNK_QUERY_MIN_Y = -2048;
    private static final int CHUNK_QUERY_MAX_Y = 2048;

    private static final String CHUNK_QUERY_REGION_ID = "rosestacker_chunk_query";

    private static StateFlag flag;

    /**
     * UNCHECKED! Call {@link WorldGuardHook#registerFlag}
     */
    public static void registerFlag() {
        if (!SettingKey.MISC_WORLDGUARD_REGION.get())
            return;

        flag = new StateFlag("rosestacker", true);
        WorldGuard.getInstance().getFlagRegistry().register(flag);
    }

    /**
     * UNCHECKED! Call {@link WorldGuardHook#testLocation}
     *
     * @param location The Location to test
     */
    public static boolean testLocation(Location location) {
        if (!SettingKey.MISC_WORLDGUARD_REGION.get() || flag == null)
            return true;

        RegionQuery regionQuery = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        return regionQuery.testState(BukkitAdapter.adapt(location), null, flag);
    }

    /**
     * UNCHECKED! Call {@link WorldGuardRegionCache#mayDenyStacking}
     * <p>
     * Checks if any region overlapping the given chunk column defines our flag. Regions inherit flags from their
     * parents and the global region applies everywhere, so both are taken into account. If nothing here defines
     * the flag then no location in this chunk can be denied, since our flag defaults to true.
     *
     * @param world The world the chunk is in
     * @param chunkX The chunk X coordinate
     * @param chunkZ The chunk Z coordinate
     * @return true if a region overlapping the chunk defines our flag, false otherwise
     */
    public static boolean chunkContainsFlaggedRegion(World world, int chunkX, int chunkZ) {
        if (flag == null)
            return false;

        RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if (regionManager == null) // WorldGuard isn't managing this world, nothing can be denied in it
            return false;

        if (definesFlag(regionManager.getRegion(ProtectedRegion.GLOBAL_REGION)))
            return true;

        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        // Transient region used purely as a query volume; max is inclusive, so the full 16x16 column is covered
        ProtectedCuboidRegion chunkRegion = new ProtectedCuboidRegion(CHUNK_QUERY_REGION_ID, true,
                BlockVector3.at(minX, CHUNK_QUERY_MIN_Y, minZ),
                BlockVector3.at(minX + 15, CHUNK_QUERY_MAX_Y, minZ + 15));

        for (ProtectedRegion region : regionManager.getApplicableRegions(chunkRegion))
            if (definesFlag(region))
                return true;

        return false;
    }

    /**
     * Checks if the given region or any of its parents sets a value for our flag
     *
     * @param region The region to check, nullable
     * @return true if our flag is set anywhere in the region's inheritance chain, false otherwise
     */
    private static boolean definesFlag(ProtectedRegion region) {
        while (region != null) {
            if (region.getFlag(flag) != null)
                return true;
            region = region.getParent();
        }
        return false;
    }

    /**
     * UNCHECKED! Call {@link WorldGuardHook#testCanDropExperience}
     *
     * @param player The player that is causing the experience to drop, nullable
     * @param location The location the experience is dropping at
     * @return true if the Location is flagged to allow dropping experience, false otherwise
     */
    public static boolean testCanDropExperience(Player player, Location location) {
        return testFlag(player, location, Flags.EXP_DROPS);
    }

    private static boolean testFlag(Player player, Location location, StateFlag flag) {
        if (!SettingKey.MISC_WORLDGUARD_OBEY_FLAGS.get())
            return true;

        LocalPlayer localPlayer = player != null ? WorldGuardPlugin.inst().wrapPlayer(player) : null;
        if (localPlayer != null && WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(localPlayer, BukkitAdapter.adapt(location.getWorld())))
            return true;

        RegionQuery regionQuery = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        return regionQuery.testState(BukkitAdapter.adapt(location), localPlayer, flag);
    }

}
