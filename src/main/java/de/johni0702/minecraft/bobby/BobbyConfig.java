package de.johni0702.minecraft.bobby;

import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;

public class BobbyConfig {
    private static ConfigValue<Boolean> enabled;
    private static ConfigValue<Boolean> noBlockEntities;
    private static ConfigValue<Boolean> taintFakeChunks;
    private static ConfigValue<Integer> unloadDelaySecs;
    private static ConfigValue<Integer> deleteUnusedRegionsAfterDays;
    private static ConfigValue<Integer> maxRenderDistance;
    private static ConfigValue<Integer> viewDistanceOverwrite;

    static
    {
        var builder = new ConfigBuilder("Bobby Reforged Settings");

        builder.Block("General Settings", b -> {

            enabled = b.define("Enable Bobby", true);
            noBlockEntities = b.define("Do not load block entities (e.g. chests) in fake chunks", true);
            taintFakeChunks = b.define("Reduce the light levels in fake chunks", true);
            maxRenderDistance = b.define("Max Render Distance", 64);
            viewDistanceOverwrite = b.define("Integrated Server View Distance override", 0);
        });

        builder.Block("Unloading", b -> {

            unloadDelaySecs = b.define("Delay for unloading of chunks which are outside your view distance (seconds)", 60);
            deleteUnusedRegionsAfterDays = b.define("Delay for deleting regions from the disk cache (days)", -1);
            taintFakeChunks = b.define("Reduce the light levels in fake chunks", true);
        });

        builder.Save();
    }

    public static boolean isNoBlockEntities() {
        return noBlockEntities.get();
    }

    public static boolean isEnabled() {return enabled.get();}

    public static boolean isTaintFakeChunks() {
        return taintFakeChunks.get();
    }

    public static int getUnloadDelaySecs() {
        return unloadDelaySecs.get();
    }

    public static int getDeleteUnusedRegionsAfterDays() {
        return deleteUnusedRegionsAfterDays.get();
    }

    public static int getMaxRenderDistance() {
        return maxRenderDistance.get();
    }

    public static int getViewDistanceOverwrite() {
        return viewDistanceOverwrite.get();
    }
}
