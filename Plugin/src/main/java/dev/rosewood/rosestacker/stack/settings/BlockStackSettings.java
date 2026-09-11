package dev.rosewood.rosestacker.stack.settings;

import dev.rosewood.rosegarden.config.CommentedFileConfiguration;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import dev.rosewood.rosestacker.RoseStacker;
import dev.rosewood.rosestacker.config.SettingKey;
import dev.rosewood.rosestacker.manager.LocaleManager;
import dev.rosewood.rosestacker.nms.util.BoundedCache;
import dev.rosewood.rosestacker.utils.StackerUtils;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;

public class BlockStackSettings extends StackSettings {

    private static final Set<Material> enabledByDefault;
    static {
        enabledByDefault = EnumSet.of(Material.DIAMOND_BLOCK, Material.GOLD_BLOCK, Material.IRON_BLOCK, Material.EMERALD_BLOCK, Material.LAPIS_BLOCK, Material.NETHERITE_BLOCK);
    }

    private final Material material;
    private final boolean enabled;
    private final String displayName;
    private final int maxStackSize;

    // Same idea as the entity display string cache: every stacked block of a given size shows the same
    // hologram lines, so build them once instead of once per display update.
    private final BoundedCache<Integer, List<String>> hologramTextCache;
    private volatile int hologramTextGeneration;

    public BlockStackSettings(CommentedFileConfiguration settingsConfiguration, Material material) {
        super(settingsConfiguration);
        this.material = material;
        this.hologramTextCache = new BoundedCache<>(512);
        this.hologramTextGeneration = -1;
        this.setDefaults();

        this.enabled = this.settingsConfiguration.getBoolean("enabled");
        this.displayName = this.settingsConfiguration.getString("display-name");
        this.maxStackSize = this.settingsConfiguration.getInt("max-stack-size");
    }

    @Override
    protected void setDefaults() {
        super.setDefaults();

        this.setIfNotExists("enabled", enabledByDefault.contains(this.material));
        this.setIfNotExists("display-name", StackerUtils.formatMaterialName(this.material));
        this.setIfNotExists("max-stack-size", -1);
    }

    @Override
    protected String getConfigurationSectionKey() {
        return this.material.name();
    }

    @Override
    public boolean isStackingEnabled() {
        return this.enabled;
    }

    @Override
    public String getDisplayName() {
        return this.displayName;
    }

    @Override
    public int getMaxStackSize() {
        if (this.maxStackSize != -1)
            return this.maxStackSize;
        return SettingKey.BLOCK_MAX_STACK_SIZE.get();
    }

    public Material getType() {
        return this.material;
    }

    /**
     * Gets the finished hologram lines for a stack of this block type, building them only the first time a
     * given stack size is seen.
     *
     * @param stackSize The size of the stack
     * @return the colorified hologram lines
     */
    public List<String> getHologramText(int stackSize) {
        LocaleManager localeManager = RoseStacker.getInstance().getManager(LocaleManager.class);
        int generation = localeManager.getGeneration();
        if (generation != this.hologramTextGeneration) {
            this.hologramTextCache.clear();
            this.hologramTextGeneration = generation;
        }

        return this.hologramTextCache.get(stackSize, size -> localeManager.getLocaleMessages("block-hologram-display",
                StringPlaceholders.builder("amount", StackerUtils.formatNumber(size))
                        .add("name", this.displayName)
                        .build()));
    }

}
