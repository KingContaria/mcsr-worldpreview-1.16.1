package me.voidxwalker.worldpreview;

import org.mcsr.speedrunapi.config.api.SpeedrunConfig;
import org.mcsr.speedrunapi.config.api.annotations.Config;

public class WorldPreviewConfig implements SpeedrunConfig {

    public boolean previewOldWorlds = false;

    @Config.Numbers.Whole.Bounds(min = 1, max = 16)
    public int chunkDistance = 16;

    @Config.Numbers.Whole.Bounds(min = 1, max = 100)
    public int dataLimit = 100;

    public boolean chunkDataCulling = true;

    public boolean chunkSectionDataCulling = false;

    public boolean entityDataCulling = true;

    public boolean debug = false;

    {
        WorldPreview.config = this;
    }

    @Override
    public String modID() {
        return "worldpreview";
    }
}
