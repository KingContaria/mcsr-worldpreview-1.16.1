package me.voidxwalker.worldpreview;

import me.contaria.speedrunapi.config.api.SpeedrunConfig;
import me.contaria.speedrunapi.config.api.annotations.Config;

public class WorldPreviewConfig implements SpeedrunConfig {

    @Config.Numbers.Whole.Bounds(min = 1, max = 16)
    public int chunkDistance = 16;

    @Config.Numbers.Whole.Bounds(min = 1, max = 100)
    public int dataLimit = 100;

    {
        WorldPreview.config = this;
    }

    @Override
    public String modID() {
        return "worldpreview";
    }
}
